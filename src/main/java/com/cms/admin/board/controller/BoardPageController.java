package com.cms.admin.board.controller;

import com.cms.admin.AdminPage;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 게시판 정의 관리 화면(ADMIN 전용 — {@code /admin/**} 캐치올). 페이지에는 메서드 보안을 걸지 않는다(HTML 403은 URL 게이트 몫). */
@Controller
@AdminPage
@RequestMapping("/admin/board")
public class BoardPageController {

    @GetMapping("/manage")
    public String boardManagePage() {
        return "admin/board/manage";
    }
}
