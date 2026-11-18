package com.cms.admin.member.controller;

import com.cms.admin.member.service.MemberTotpService;
import com.cms.admin.member.service.TotpEnrollment;
import com.cms.admin.member.totp.Base32;
import com.cms.admin.member.totp.OtpAuthUri;
import com.cms.admin.member.totp.TotpProperties;
import com.cms.admin.member.totp.TotpQrCodes;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.web.ClientIpResolver;
import com.cms.admin.AdminPage;
import com.cms.config.auth.CustomUserDetails;
import com.cms.config.auth.LoginSessionMutexFilter;
import com.cms.config.auth.TotpSessionPromoter;
import com.cms.config.auth.VisitLoggingAuthenticationSuccessHandler;
import jakarta.servlet.http.HttpServletRequest;
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
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * 2단계 인증 강제 등록 화면(PLAN-totp-required-admin.md 결정 2). 2단계가 필수인 역할(ADMIN)이 아직 등록하지 않은 채 비밀번호만 통과한
 * 중간 상태({@code TOTP_ENROLLMENT_REQUIRED})만 들어온다 — URL 규칙과 메서드 선언 둘 다로 막는다. 요청은 {@link LoginSessionMutexFilter}가
 * 세션 뮤텍스 안에서 처리한다(같은 세션의 다른 계정 재로그인과 직렬화).
 *
 * <p>등록 서비스({@link MemberTotpService#startEnrollment}·{@link MemberTotpService#confirmEnrollment})는 보안 설정 화면과 같은 것을 쓴다 —
 * 확정은 현재 비밀번호와 코드를 받고, 성공하면 본인 전 세션을 만료하므로 사용자는 다시 로그인해 코드를 낸다.
 */
@Slf4j
@Controller
@AdminPage
@RequiredArgsConstructor
public class TotpEnrollmentLoginController {

    static final String PATH = VisitLoggingAuthenticationSuccessHandler.TOTP_ENROLLMENT_PAGE;
    private static final String VIEW = "admin/login-totp-enrollment";
    private static final String DONE_VIEW = "admin/login-totp-enrollment-done";

    private final MemberTotpService memberTotpService;
    private final TotpSessionPromoter promoter;
    private final TotpProperties totpProperties;
    private final Clock clock;

    @GetMapping(PATH)
    @PreAuthorize("hasAuthority('TOTP_ENROLLMENT_REQUIRED')")
    public String page(Authentication authentication, HttpServletRequest request, Model model) {
        if (!(authentication.getPrincipal() instanceof CustomUserDetails principal)) {
            return end(request, "redirect:/admin/login-error");
        }
        HttpSession session = request.getSession(false);
        if (session == null || !promoter.isLiveFor(session.getId(), principal.getId())) {
            return end(request, "redirect:/admin/login");
        }
        TotpEnrollment pending;
        try {
            pending = memberTotpService.startEnrollment(principal);
        } catch (RuntimeException e) {
            // 이미 등록됨(다른 세션에서 확정) 등 — 이 중간 상태로는 진행할 수 없다
            log.warn("강제 등록 대기 발급 실패 — 세션을 끝냅니다 (memberId={}): {}", principal.getId(), e.getMessage());
            return end(request, "redirect:/admin/login-error");
        }
        session.setAttribute(TotpEnrollment.SESSION_ATTRIBUTE, pending);
        fillEnrollment(model, principal, pending);
        return VIEW;
    }

    /**
     * 등록 확정. 성공하면 복구 코드를 이 응답에서만 보여 준다(세션·리다이렉트 파라미터에 두지 않는다). 서비스가 본인 전 세션 만료를 커밋 뒤에
     * 실행하므로 다음 요청은 로그인으로 간다.
     */
    @PostMapping(PATH)
    @PreAuthorize("hasAuthority('TOTP_ENROLLMENT_REQUIRED')")
    public String confirm(@RequestParam(name = "enrollmentId", defaultValue = "") String enrollmentId,
                          @RequestParam(name = "code", defaultValue = "") String code,
                          @RequestParam(name = "currentPassword", defaultValue = "") String currentPassword,
                          Authentication authentication, HttpServletRequest request, Model model) {
        if (!(authentication.getPrincipal() instanceof CustomUserDetails principal)) {
            return end(request, "redirect:/admin/login-error");
        }
        HttpSession session = request.getSession(false);
        if (session == null || !promoter.isLiveFor(session.getId(), principal.getId())) {
            return end(request, "redirect:/admin/login");
        }
        // 대기 대조는 컨트롤러가 먼저 한다 — 낡은 대기는 실패가 아니라 "새 QR"이다(서비스의 409와 구분, 카운트 없음)
        if (!(session.getAttribute(TotpEnrollment.SESSION_ATTRIBUTE) instanceof TotpEnrollment pending)
                || !Objects.equals(pending.memberId(), principal.getId())
                || !Objects.equals(pending.enrollmentId(), enrollmentId)
                || pending.issuedAt().plus(MemberTotpService.ENROLLMENT_TTL).isBefore(LocalDateTime.now(clock))) {
            return "redirect:" + PATH + "?expired";
        }

        List<String> recoveryCodes;
        try {
            recoveryCodes = memberTotpService.confirmEnrollment(principal, pending, enrollmentId, code, currentPassword,
                    ClientIpResolver.resolve(request), request.getRequestURI());
        } catch (InvalidRequestException e) {
            // 비밀번호·코드 불일치(로그인 실패 횟수에 반영됨)·이미 쓴 코드 — 같은 대기로 다시 그린다(다시 스캔하지 않게)
            fillEnrollment(model, principal, pending);
            model.addAttribute("error", e.getMessage());
            return VIEW;
        } catch (RuntimeException e) {
            // 상태 변경·이미 등록됨(409) 또는 예상 밖 오류 — 이 중간 상태로는 진행할 수 없다
            log.warn("강제 등록 확정 실패 — 세션을 끝냅니다 (memberId={}): {}", principal.getId(), e.getMessage());
            return end(request, "redirect:/admin/login-error");
        }
        model.addAttribute("recoveryCodes", recoveryCodes);
        // 서비스가 커밋 뒤 본인 전 세션을 레지스트리에서 만료 표시했다. 표시만 남은 세션 쿠키로는 완료 화면의 CSS 요청까지
        // ConcurrentSessionFilter가 로그인으로 302하므로(실기 검증에서 발견) 응답 전에 현재 세션을 직접 무효화한다 — 대기(비밀값)도 함께 사라진다
        return end(request, DONE_VIEW);
    }

    private void fillEnrollment(Model model, CustomUserDetails principal, TotpEnrollment pending) {
        String secret = Base32.encode(pending.secret());
        String uri = OtpAuthUri.build(totpProperties.getIssuer(), principal.getUserId(), secret);
        model.addAttribute("enrollmentId", pending.enrollmentId());
        model.addAttribute("secret", secret);
        model.addAttribute("qrPngBase64", Base64.getEncoder().encodeToString(TotpQrCodes.png(uri)));
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
