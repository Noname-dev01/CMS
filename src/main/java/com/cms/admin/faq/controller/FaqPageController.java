package com.cms.admin.faq.controller;

import com.cms.admin.AdminPage;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** FAQ 관리 화면. 페이지에는 메서드 보안을 걸지 않는다 — 차단은 URL 게이트(FAQ 기능 단위 READ)가 HTML 403으로 낸다. */
@Controller
@AdminPage
public class FaqPageController {

    @GetMapping("/admin/faq/manage")
    public String faqManagePage() {
        return "admin/faq/manage";
    }
}
