package com.cms.admin.message.controller;

import com.cms.admin.AdminPage;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 쪽지함 페이지 — 받은·보낸 쪽지, 작성, 상세. 데이터는 화면이 {@code /admin/api/members/me/messages} API로 읽는다.
 *
 * <p>경로 {@code /admin/member/messages}는 {@code AdminFeature.MY_INFO}의 게이트에 <b>정확 경로 1개</b>로 추가돼 ADMIN·MANAGER 모두 접근한다
 * (인가 정책 변경, 2026-10-05 승인 — PLAN-admin-message.md D3). ALWAYS 게이트는 HTTP 메서드를 구분하지 않으므로 <b>이 경로에는 GET 핸들러만 둔다</b>
 * — POST 등을 추가하면 MANAGER 접근이 함께 열린다. {@code MessagePageMethodConventionTest}가 등록된 매핑 전체를 검사해 CI가 막는다.
 * {@code @AdminPage}는 인가가 아니라 사이드바 모델 주입 마커다. 메서드 보안은 걸지 않는다(페이지 차단은 URL 게이트의 HTML 403).
 */
@Controller
@AdminPage
@RequestMapping("/admin/member/messages")
public class AdminMessagePageController {

    @GetMapping
    public String messagesPage() {
        return "admin/member/messages";
    }
}
