package com.cms.publicweb.faq;

import com.cms.publicweb.support.PublicSiteRoutes;
import com.cms.publicweb.support.SiteScopedPublic;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 공개(비로그인) FAQ 페이지 {@code /faq}. {@code @AdminPage}를 붙이지 않는다(공개 요청마다 사이드바 메뉴 조회가 나가지 않게). {@code SecurityConfig}가
 * {@code /faq} 정확 경로의 GET·HEAD만 무인증 공개하고(2026-10-12 승인) 하위 경로·그 외 메서드는 거부한다 — 라우트는 이 하나뿐이다.
 * 서버는 검색 파라미터를 받지 않는다(즉시 필터는 화면 JS가 한다). 조회 실패는 페이지 자체가 본문이라 공개 예외 advice의 HTML 오류 페이지로 흐른다.
 */
@SiteScopedPublic
@Controller
@RequiredArgsConstructor
public class PublicFaqController {

    private final PublicFaqService publicFaqService;

    @GetMapping(PublicSiteRoutes.FAQ)
    public String list(Model model) {
        model.addAttribute("categories", publicFaqService.findPublicFaqs());
        return "public/faq/list";
    }
}
