package com.cms.admin;

import com.cms.admin.dashboard.service.ContentStatsService;
import com.cms.admin.dashboard.service.DashboardService;
import com.cms.admin.member.passkey.PasskeyJson;
import com.cms.config.auth.PasskeyLoginService;
import com.cms.config.auth.PasskeyLoginState;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Slf4j
@Controller
@AdminPage
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminMainController {

    private final DashboardService dashboardService;
    private final ContentStatsService contentStatsService;
    private final PasskeyLoginService passkeyLoginService;
    private final PasskeyJson passkeyJson;

    @GetMapping
    public String main(Model model, Authentication authentication) {
        model.addAttribute("stats", dashboardService.getDashboardStats());
        model.addAttribute("dailyVisitors", dashboardService.getDailyVisitorCounts());
        model.addAttribute("contentStats", contentStatsService.getContentStats(authentication));
        return "admin/index";
    }

    @GetMapping("/login")
    public String login(Model model, HttpServletRequest request,
                        @RequestParam(name = "totpExpired", required = false) String totpExpired,
                        @RequestParam(name = "passkeyError", required = false) String passkeyError,
                        @RequestParam(name = "passkeyExpired", required = false) String passkeyExpired) {
        // 2단계 인증 대기(5분)가 지나 세션을 끝낸 경우(PLAN-totp-two-factor.md 결정 2)
        if (totpExpired != null) {
            model.addAttribute("error", "인증 시간이 지났습니다. 다시 로그인해 주세요.");
        }
        // 패스키 로그인 실패 — 계정 상태별로 나누지 않는다(PLAN-passkey-login.md 결정 5)
        if (passkeyError != null) {
            model.addAttribute("error", "패스키로 로그인하지 못했습니다. 아이디와 비밀번호로 로그인해 주세요.");
        }
        if (passkeyExpired != null) {
            model.addAttribute("error", "패스키 인증 시간이 지났습니다. 다시 시도해 주세요.");
        }
        return loginPage(model, request);
    }

    @GetMapping("/login-error")
    public String loginError(Model model, HttpServletRequest request) {
        model.addAttribute("error", "아이디 또는 비밀번호가 올바르지 않습니다.");
        return loginPage(model, request);
    }

    /**
     * 로그인 화면 — 패스키 기능이 켜져 있으면 패스키 로그인 챌린지를 세션에 두고(GET마다 대체) 옵션을 화면에 그린다(PLAN-passkey-login.md 결정 2).
     * 옵션을 못 만들어도 비밀번호 로그인은 그대로 쓸 수 있어야 한다.
     */
    private String loginPage(Model model, HttpServletRequest request) {
        HttpSession session = request.getSession();
        try {
            passkeyLoginService.start().ifPresentOrElse(state -> {
                session.setAttribute(PasskeyLoginState.SESSION_ATTRIBUTE, state);
                model.addAttribute("passkeyLoginId", state.loginId());
                model.addAttribute("passkeyOptions", passkeyJson.write(state.options()));
            }, () -> session.removeAttribute(PasskeyLoginState.SESSION_ATTRIBUTE));
        } catch (RuntimeException e) {
            log.error("패스키 로그인 옵션 생성 실패 — 비밀번호 로그인만 보여 줍니다", e);
            session.removeAttribute(PasskeyLoginState.SESSION_ATTRIBUTE);
        }
        return "admin/login";
    }

    @GetMapping("/password-reset")
    public String passwordReset() {
        return "admin/password-reset";
    }

    @GetMapping("/password-reset/confirm")
    public String passwordResetConfirm() {
        return "admin/password-reset-confirm";
    }
}
