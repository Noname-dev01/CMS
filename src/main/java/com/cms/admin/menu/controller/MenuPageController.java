package com.cms.admin.menu.controller;

import com.cms.admin.AdminPage;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@AdminPage
@RequestMapping("/admin/menu")
public class MenuPageController {

    /**
     * 메뉴 구조 편집은 정적 페이지 관리 화면의 "메뉴 구조" 탭으로 옮겨졌다(PLAN-content-menu-unify.md 쟁점 4). 옛 주소(사이드바·검색·북마크)는
     * 새 화면의 메뉴 탭으로 보낸다. URL 게이트는 그대로 {@code /admin/**} ADMIN 캐치올이라 MANAGER는 이 핸들러에 닿기 전에 403이다
     * (메뉴 관리는 위임 불가 — {@code AdminFeature.MENU}). 리다이렉트만 하는 읽기 전용 GET이라 인가 선언 컨벤션의 면제 대상이다.
     */
    @GetMapping("/manage")
    public String menuManagePage() {
        return "redirect:/admin/site-page/manage#menu";
    }
}
