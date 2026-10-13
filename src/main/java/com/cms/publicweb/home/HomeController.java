package com.cms.publicweb.home;

import com.cms.publicweb.page.PublicSitePageService;
import com.cms.publicweb.page.dto.PublicFooterLink;
import com.cms.publicweb.popup.PublicPopupService;
import com.cms.publicweb.popup.dto.PublicPopup;
import com.cms.publicweb.support.PublicSiteRoutes;
import com.cms.publicweb.support.SiteScopedPublic;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * 공개 메인 {@code GET /}. {@code @AdminPage}를 붙이지 않는다(공개 요청마다 사이드바 메뉴 조회가 나가지 않게). {@code SecurityConfig}가
 * 정확 경로 {@code /}의 GET·HEAD만 무인증 공개한다(2026-10-09 승인, PLAN-public-home-banner.md 쟁점 1) — HEAD는 Spring MVC가 GET 핸들러로
 * 처리한다. 예외는 {@code PublicWebExceptionAdvice}가 HTML 500으로 처리한다.
 */
@Slf4j
@SiteScopedPublic
@Controller
@RequiredArgsConstructor
public class HomeController {

    private final PublicHomeService publicHomeService;
    private final PublicSitePageService publicSitePageService;
    private final PublicPopupService publicPopupService;

    @GetMapping(PublicSiteRoutes.HOME)
    public String home(Model model) {
        model.addAttribute("home", publicHomeService.getHome());
        model.addAttribute("footerLinks", findFooterLinksSafely());
        model.addAttribute("popups", findPopupsSafely());
        return "public/home";
    }

    /**
     * 푸터 링크는 선택 섹션이라 조회 실패가 메인을 깨뜨리면 안 된다. {@code getHome()}과 별도 호출(각자 자체 읽기 트랜잭션)로 두고 예외를
     * 여기서 격리한다 — 메인 조회 트랜잭션 안에서 예외만 잡으면 rollback-only로 표시돼 반환 때 다시 실패할 수 있다(PLAN-static-page.md R1-3).
     */
    private List<PublicFooterLink> findFooterLinksSafely() {
        try {
            return publicSitePageService.findFooterLinks();
        } catch (RuntimeException e) {
            log.warn("공개 메인 푸터 링크 조회 실패 — 푸터 없이 렌더링합니다: {}", e.getClass().getName());
            return List.of();
        }
    }

    /**
     * 팝업은 선택 섹션이라 조회 실패가 메인을 깨뜨리면 안 된다. 푸터와 같은 이유로 {@code getHome()}과 별도 호출로 두고 예외를 여기서 격리한다
     * (PLAN-popup.md 쟁점 8 — 실패 주입 시험 {@code PublicHomePopupIsolationIntegrationTest}).
     */
    private List<PublicPopup> findPopupsSafely() {
        try {
            return publicPopupService.findDisplayablePopups();
        } catch (RuntimeException e) {
            log.warn("공개 메인 팝업 조회 실패 — 팝업 없이 렌더링합니다: {}", e.getClass().getName());
            return List.of();
        }
    }
}
