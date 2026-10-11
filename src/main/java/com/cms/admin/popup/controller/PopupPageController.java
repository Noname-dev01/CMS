package com.cms.admin.popup.controller;

import com.cms.admin.AdminPage;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 팝업 관리 화면. 페이지에는 메서드 보안을 걸지 않는다 — 차단은 URL 게이트(POPUP 기능 단위 READ)가 HTML 403으로 낸다. */
@Controller
@AdminPage
public class PopupPageController {

    @GetMapping("/admin/popup/manage")
    public String popupManagePage() {
        return "admin/popup/manage";
    }
}
