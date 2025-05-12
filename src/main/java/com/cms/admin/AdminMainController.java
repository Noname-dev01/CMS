package com.cms.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminMainController {


    @GetMapping
    public String main(Model model) {
        return "/admin/index";
    }

    @GetMapping("/login")
    public String login(Model model) {
        return "/admin/login";
    }

    @GetMapping("/login-error")
    public String loginError(Model model) {
        model.addAttribute("error", "아이디 또는 비밀번호가 올바르지 않습니다.");
        return "/admin/login";
    }
}
