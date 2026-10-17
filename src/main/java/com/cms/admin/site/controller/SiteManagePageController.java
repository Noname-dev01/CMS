package com.cms.admin.site.controller;

import com.cms.admin.AdminPage;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 사이트 설정 화면(사이드바 "사이트 관리" 그룹의 하위 메뉴). 카탈로그({@code AdminFeature})에 기능을 만들지 않는다 — 카탈로그 밖 URL은
 * {@code /admin/**} ADMIN 캐치올이 막아 MANAGER가 닿을 수 없다(fail-closed). 읽기 전용 GET이라 인가 선언 컨벤션의 면제 대상이다.
 */
@Controller
@AdminPage
public class SiteManagePageController {

    @GetMapping("/admin/site/manage")
    public String siteManagePage() {
        return "admin/site/manage";
    }
}
