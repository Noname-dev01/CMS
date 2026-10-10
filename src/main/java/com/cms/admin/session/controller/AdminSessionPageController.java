package com.cms.admin.session.controller;

import com.cms.admin.AdminPage;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 세션 관리 화면. 페이지에는 메서드 보안을 걸지 않는다 — 차단은 URL 캐치올(`/admin/**` ADMIN)이 HTML 403으로 낸다. */
@Controller
@AdminPage
public class AdminSessionPageController {

    @GetMapping("/admin/session/manage")
    public String sessionManagePage() {
        return "admin/session/manage";
    }
}
