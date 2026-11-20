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
        // 패스키 어설션 대기(PLAN-passkey-second-factor.md 결정 4) — 남은 수명이 충분한 같은 회원의 대기는 재사용(같은 회원의 탭들이 나눠 쓴다),
        // 패스키가 없으면 지운다(버튼 없음). 새로 만든 것만 세션에 쓴다 — 이 GET은 세션 뮤텍스 밖이라 재사용 값을 다시 쓰면 그사이 제출이 소비한
        // 대기가 되살아난다(PLAN-passkey-challenge-stale-tab.md 결정 1·3)
        HttpSession session = request.getSession(false);
        if (session != null && authentication.getPrincipal() instanceof CustomUserDetails principal) {
            try {
                PasskeyAssertionState current = session.getAttribute(PasskeyAssertionState.SESSION_ATTRIBUTE) instanceof PasskeyAssertionState found ? found : null;
                totpLoginService.resumeOrStartPasskeyAssertion(principal, current).ifPresentOrElse(state -> {
                    if (state != current) {
                        session.setAttribute(PasskeyAssertionState.SESSION_ATTRIBUTE, state);
                    }
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
            assertion = takeAssertion(session, principal.getId(), assertionId, LocalDateTime.now(clock));
            if (assertion == null) {
                return "redirect:" + PATH + "?passkeyExpired";
            }
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
            // 서명 횟수 역행은 세지 않을 뿐 안내는 같다(PLAN-passkey-login.md 결정 8)
            case WRONG_PASSKEY, PASSKEY_COUNTER_REJECTED -> "redirect:" + PATH + "?passkeyError";
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

    /**
     * 세션의 어설션 대기를 판정한 뒤 판정에 따라 지운다(PLAN-passkey-challenge-stale-tab.md 결정 2·4). 회원·폼의 {@code assertionId}가 맞고 만료 전이면
     * 꺼내며 지운다 — <b>검증에 도달하는 유일한 갈래이고 검증 전에 지우므로 1회용이다</b>. 만료·다른 회원·쓸 수 없는 값이면 지우고 null,
     * {@code assertionId}만 다르면(같은 회원의 다른 탭의 옛 화면) 지우지 않고 null — 만료 안내 GET이 그것을 재사용해 탭들이 한 대기로 모인다.
     * null이면 검증 전에 만료(카운트 없음, 리뷰 2-1). 제출끼리는 세션 뮤텍스 안이다.
     */
    private static PasskeyAssertionState takeAssertion(HttpSession session, Long memberId, String assertionId, LocalDateTime now) {
        Object stored = session.getAttribute(PasskeyAssertionState.SESSION_ATTRIBUTE);
        if (!(stored instanceof PasskeyAssertionState state)) {
            session.removeAttribute(PasskeyAssertionState.SESSION_ATTRIBUTE);
            return null;
        }
        if (state.matches(memberId, assertionId, now)) {
            session.removeAttribute(PasskeyAssertionState.SESSION_ATTRIBUTE);
            return state;
        }
        if (state.isExpired(now) || !Objects.equals(state.memberId(), memberId)) {
            session.removeAttribute(PasskeyAssertionState.SESSION_ATTRIBUTE);
        }
        return null;
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
