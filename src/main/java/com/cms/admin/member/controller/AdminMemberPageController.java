package com.cms.admin.member.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/member")
public class AdminMemberPageController {

    @GetMapping("/new")
    public String adminCreatePage() {
        return "admin/member/admin-create";
    }
}
