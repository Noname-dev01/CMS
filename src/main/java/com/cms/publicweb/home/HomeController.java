package com.cms.publicweb.home;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 공개 메인 {@code GET /}. {@code @AdminPage}를 붙이지 않는다(공개 요청마다 사이드바 메뉴 조회가 나가지 않게). {@code SecurityConfig}가
 * 정확 경로 {@code /}의 GET·HEAD만 무인증 공개한다(2026-10-09 승인, PLAN-public-home-banner.md 쟁점 1) — HEAD는 Spring MVC가 GET 핸들러로
 * 처리한다. 예외는 {@code PublicWebExceptionAdvice}가 HTML 500으로 처리한다.
 */
@Controller
@RequiredArgsConstructor
public class HomeController {

    private final PublicHomeService publicHomeService;

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("home", publicHomeService.getHome());
        return "public/home";
    }
}
