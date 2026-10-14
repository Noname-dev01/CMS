package com.cms.admin.board.controller;

import com.cms.admin.AdminPage;
import com.cms.admin.site.ContentScopeOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 게시판 정의 관리 화면(ADMIN 전용 — {@code /admin/**} 캐치올). 페이지에는 메서드 보안을 걸지 않는다(HTML 403은 URL 게이트 몫). */
@Controller
@AdminPage
@RequiredArgsConstructor
@RequestMapping("/admin/board")
public class BoardPageController {

    private final ContentScopeOptions contentScopeOptions;

    @GetMapping("/manage")
    public String boardManagePage(Model model) {
        // 표시 대상 선택지 — 배너·팝업 화면과 같은 서버 렌더링(PLAN-site-menu.md PR ③ S7, PR ④ T6)
        ContentScopeOptions.Options options = contentScopeOptions.load();
        model.addAttribute("scopeSites", options.sites());
        model.addAttribute("scopeDefaultKey", options.defaultKey());
        return "admin/board/manage";
    }

    /** 게시글 관리 화면. URL 게이트(BOARD 기능 단위 READ)가 HTML 403을 낸다 — 게시판별 판정은 API가 한다. {@code ?boardId=&id=} 진입을 지원한다. */
    @GetMapping("/posts")
    public String postManagePage() {
        return "admin/board/posts";
    }
}
