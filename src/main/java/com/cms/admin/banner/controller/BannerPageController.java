package com.cms.admin.banner.controller;

import com.cms.admin.AdminPage;
import com.cms.admin.site.ContentScopeOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.ui.Model;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 배너 관리 화면. 페이지에는 메서드 보안을 걸지 않는다 — 차단은 URL 게이트(BANNER 기능 단위 READ)가 HTML 403으로 낸다. */
@Controller
@AdminPage
@RequiredArgsConstructor
public class BannerPageController {

    private final ContentScopeOptions contentScopeOptions;

    @GetMapping("/admin/banner/manage")
    public String bannerManagePage(Model model) {
        // 표시 대상 선택지 — 위임받은 MANAGER는 사이트 목록 API를 쓸 수 없어 서버가 렌더링한다(PLAN-site-menu.md PR ③ S7)
        ContentScopeOptions.Options options = contentScopeOptions.load();
        model.addAttribute("scopeSites", options.sites());
        model.addAttribute("scopeDefaultKey", options.defaultKey());
        return "admin/banner/manage";
    }
}
