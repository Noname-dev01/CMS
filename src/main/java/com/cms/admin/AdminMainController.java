package com.cms.admin;

import com.cms.admin.dashboard.service.ContentStatsService;
import com.cms.admin.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@AdminPage
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminMainController {

    private final DashboardService dashboardService;
    private final ContentStatsService contentStatsService;

    @GetMapping
    public String main(Model model, Authentication authentication) {
        model.addAttribute("stats", dashboardService.getDashboardStats());
        model.addAttribute("dailyVisitors", dashboardService.getDailyVisitorCounts());
        model.addAttribute("contentStats", contentStatsService.getContentStats(authentication));
        return "admin/index";
    }

    @GetMapping("/login")
    public String login(Model model, @RequestParam(name = "totpExpired", required = false) String totpExpired) {
        // 2단계 인증 대기(5분)가 지나 세션을 끝낸 경우(PLAN-totp-two-factor.md 결정 2)
        if (totpExpired != null) {
            model.addAttribute("error", "인증 시간이 지났습니다. 다시 로그인해 주세요.");
        }
        return "admin/login";
    }

    @GetMapping("/login-error")
    public String loginError(Model model) {
        model.addAttribute("error", "아이디 또는 비밀번호가 올바르지 않습니다.");
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
