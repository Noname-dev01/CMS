package com.cms.admin.permission.controller;

import com.cms.admin.AdminPage;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 권한관리 화면. URL 게이트는 카탈로그에 없는 {@code /admin/**}를 ADMIN 전용으로 막는 캐치올이 맡는다(PERMISSION = ADMIN_ONLY). */
@Controller
@AdminPage
@RequestMapping("/admin/permission")
public class PermissionPageController {

    @GetMapping("/manage")
    public String permissionManagePage() {
        return "admin/permission/manage";
    }
}
