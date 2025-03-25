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
}
