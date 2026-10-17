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
     * 메뉴 관리 화면(사이드바 "사이트 관리" 그룹의 하위 메뉴). URL 게이트는 {@code /admin/**} ADMIN 캐치올이라 MANAGER는 이 핸들러에 닿기 전에
     * 403이다(메뉴 관리는 위임 불가 — {@code AdminFeature.MENU}). 읽기 전용 GET이라 인가 선언 컨벤션의 면제 대상이다.
     */
    @GetMapping("/manage")
    public String menuManagePage() {
        return "admin/menu/manage";
    }
}
