package com.cms.admin.member.controller;

import com.cms.admin.AdminPage;
import com.cms.common.web.ClientIpResolver;
import com.cms.config.auth.CustomUserDetails;
import com.cms.config.auth.LoginSessionMutexFilter;
import com.cms.config.auth.PasskeyAssertionState;
import com.cms.admin.member.passkey.PasskeyJson;
import com.cms.config.auth.TotpAuthorities;
import com.cms.config.auth.TotpLoginService;
import com.cms.config.auth.TotpLoginService.Result;
import com.cms.config.auth.TotpPendingState;
import com.cms.config.auth.TotpSessionPromoter;
import com.cms.config.auth.VisitLoggingAuthenticationSuccessHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 2단계 인증 코드 화면(PLAN-totp-two-factor.md 결정 2). 비밀번호만 통과한 중간 상태({@link TotpAuthorities#PENDING})만 들어온다 —
 * URL 규칙과 메서드 선언 둘 다로 막는다. 제출은 {@link LoginSessionMutexFilter}가 세션 뮤텍스 안에서 처리한다.
 *
 * <p>순서: 대기 표지(회원·5분) → 레지스트리(강제 만료) → 판정 서비스(행 잠금 트랜잭션, 커밋 완료) → 승격(세션 생성 금지·전후 재확인).
 * 커밋 전에는 완전 권한을 공개하지 않고, 어느 단계든 예상 밖이면 세션을 끝낸다(fail-closed).
 */
@Slf4j
@Controller
@AdminPage
@RequiredArgsConstructor
public class TotpLoginController {

    static final Duration PENDING_TTL = Duration.ofMinutes(5);
    private static final String VIEW = "admin/login-totp";
    private static final String PATH = "/admin/login/totp";

    private final TotpLoginService totpLoginService;
    private final TotpSessionPromoter promoter;
    private final VisitLoggingAuthenticationSuccessHandler successHandler;
    private final PasskeyJson passkeyJson;
    private final Clock clock;

    @GetMapping(PATH)
    @PreAuthorize("hasAuthority('TOTP_PENDING')")
    public String page(Authentication authentication, HttpServletRequest request, Model model) {
        // 패스키 어설션 대기(PLAN-passkey-second-factor.md 결정 4) — GET마다 대체, 패스키가 없으면 지운다(버튼 없음)
        HttpSession session = request.getSession(false);
        if (session != null && authentication.getPrincipal() instanceof CustomUserDetails principal) {
            try {
                totpLoginService.startPasskeyAssertion(principal).ifPresentOrElse(state -> {
                    session.setAttribute(PasskeyAssertionState.SESSION_ATTRIBUTE, state);
                    model.addAttribute("passkeyAssertionId", state.assertionId());
                    model.addAttribute("passkeyOptions", passkeyJson.write(state.options()));
                }, () -> session.removeAttribute(PasskeyAssertionState.SESSION_ATTRIBUTE));
            } catch (RuntimeException e) {
                // 패스키 옵션을 못 만들어도 코드 입력은 그대로 쓸 수 있어야 한다
                log.error("패스키 옵션 생성 실패 — 코드 입력만 보여 줍니다 (memberId={})", principal.getId(), e);
                session.removeAttribute(PasskeyAssertionState.SESSION_ATTRIBUTE);
            }
        }
        return VIEW;
    }

    @PostMapping(PATH)
    @PreAuthorize("hasAuthority('TOTP_PENDING')")
    public String submit(@RequestParam(name = "code", defaultValue = "") String code,
                         @RequestParam(name = "passkeyCredential", required = false) String passkeyCredential,
                         @RequestParam(name = "assertionId", required = false) String assertionId,
                         Authentication authentication,
                         HttpServletRequest request, HttpServletResponse response) {
        if (!(authentication.getPrincipal() instanceof CustomUserDetails principal)) {
            return end(request, "redirect:/admin/login-error");
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return end(request, "redirect:/admin/login");
        }
        String sessionId = session.getId();
        if (!(session.getAttribute(TotpPendingState.SESSION_ATTRIBUTE) instanceof TotpPendingState state)
                || !Objects.equals(state.memberId(), principal.getId())
                || state.since().plus(PENDING_TTL).isBefore(LocalDateTime.now(clock))) {
            return end(request, "redirect:/admin/login?totpExpired");
        }
        if (!promoter.isLive(sessionId)) {
            return end(request, "redirect:/admin/login");
        }

        boolean passkey = passkeyCredential != null && !passkeyCredential.isBlank();
        PasskeyAssertionState assertion = null;
        if (passkey) {
            // 1회용 — 꺼내며 지운다. 회원·폼의 assertionId·5분이 맞지 않으면 검증 전에 만료(카운트 없음, 리뷰 2-1)
            Object stored = session.getAttribute(PasskeyAssertionState.SESSION_ATTRIBUTE);
            session.removeAttribute(PasskeyAssertionState.SESSION_ATTRIBUTE);
            if (!(stored instanceof PasskeyAssertionState found) || !found.matches(principal.getId(), assertionId, LocalDateTime.now(clock))) {
                return "redirect:" + PATH + "?passkeyExpired";
            }
            assertion = found;
        }

        Result result;
        try {
            result = passkey
                    ? totpLoginService.verifyPasskey(principal, assertion.options(), passkeyCredential, ClientIpResolver.resolve(request), request.getRequestURI())
                    : totpLoginService.verify(principal, code, ClientIpResolver.resolve(request), request.getRequestURI());
        } catch (Exception e) {
            log.error("2단계 판정 실패 — 로그인을 거부합니다 (memberId={})", principal.getId(), e);
            return end(request, "redirect:/admin/login-error");
        }

        return switch (result.status()) {
            case WRONG_CODE -> "redirect:" + PATH + "?error";
            case WRONG_PASSKEY -> "redirect:" + PATH + "?passkeyError";
            case REUSED_CODE -> "redirect:" + PATH + "?reused";
            case LOCKED, REJECTED -> end(request, "redirect:/admin/login-error");
            case SUCCESS -> {
                if (!promoter.promote(request, response, principal, sessionId)) {
                    yield end(request, "redirect:/admin/login-error");
                }
                request.getSession(false).removeAttribute(TotpPendingState.SESSION_ATTRIBUTE);
                request.getSession(false).removeAttribute(PasskeyAssertionState.SESSION_ATTRIBUTE);
                successHandler.recordCompletedLogin(request, SecurityContextHolder.getContext().getAuthentication());
                // 복구 코드로 들어왔으면 남은 개수를 보고 새로 등록할 수 있게 보안 설정으로 보낸다
                yield result.usedRecoveryCode() ? "redirect:/admin/member/settings?recoveryCodeUsed" : "redirect:/admin";
            }
        };
    }

    /** 세션을 끝내고(있으면 무효화) 지정한 곳으로 보낸다. */
    private static String end(HttpServletRequest request, String view) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // 이미 무효
            }
        }
        return view;
    }
}
