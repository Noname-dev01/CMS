package com.cms.admin.page.controller;

import com.cms.admin.AdminPage;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 정적 페이지 관리 화면. 페이지에는 메서드 보안을 걸지 않는다 — 차단은 URL 게이트(SITE_PAGE 기능 단위 READ)가 HTML 403으로 낸다. */
@Controller
@AdminPage
public class SitePagePageController {

    @GetMapping("/admin/site-page/manage")
    public String sitePageManagePage() {
        return "admin/page/manage";
    }
}
