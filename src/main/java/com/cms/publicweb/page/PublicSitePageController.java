package com.cms.publicweb.page;

import com.cms.publicweb.support.PublicSiteRoutes;
import com.cms.publicweb.support.SiteScopedPublic;
import com.cms.publicweb.page.dto.PublicSitePageRevisions;
import com.cms.publicweb.page.dto.PublicSitePageView;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.regex.Pattern;

/**
 * 공개(비로그인) 정적 페이지 — 현재 버전 {@code /pages/{slug}}, 개정 목록 {@code /pages/{slug}/revisions}, 특정 개정
 * {@code /pages/{slug}/revisions/{no}}. {@code @AdminPage}를 붙이지 않는다(공개 요청마다 사이드바 메뉴 조회가 나가지 않게).
 *
 * <p>경로 변수·{@code page}는 String으로 받아 직접 파싱한다 — 바인딩에 맡기면 변환 실패가 컨트롤러 진입 전에 전역 JSON advice로 새어 나간다
 * (공개 게시판 컨트롤러와 같은 이유). 이 컨트롤러는 예외를 던지지 않는다(던지면 404가 아니라 HTML 500). {@code SecurityConfig}가
 * {@code /pages/**}의 GET·HEAD만 무인증 공개하므로(2026-10-11 승인) 라우트 추가만으로 공개가 되는 구조다 — 접근 통제는 전량
 * {@link PublicSitePageService}의 공개 조건 재검증이 맡고, 비노출·없는 슬러그·없는 개정·형식 위반은 모두 같은 404다.
 */
@Controller
@SiteScopedPublic
@RequestMapping(PublicSiteRoutes.PAGES)
@RequiredArgsConstructor
public class PublicSitePageController {

    /** 개정 번호는 양의 {@code int}(최대 10자리)만 — 범위를 넘으면 {@code parseInt}가 실패해 404로 흡수한다. */
    private static final Pattern REVISION_NO = Pattern.compile("^[1-9][0-9]{0,9}$");

    private final PublicSitePageService publicSitePageService;

    @GetMapping("/{slug}")
    public String current(@PathVariable String slug, Model model, HttpServletResponse response) {
        return publicSitePageService.findCurrent(slug)
                .map(view -> renderPage(view, model))
                .orElseGet(() -> notFound(response));
    }

    @GetMapping("/{slug}/revisions")
    public String revisions(@PathVariable String slug, @RequestParam(defaultValue = "0") String page,
                            Model model, HttpServletResponse response) {
        return publicSitePageService.findRevisions(slug, parsePageOrZero(page))
                .map(result -> renderRevisions(result, model))
                .orElseGet(() -> notFound(response));
    }

    @GetMapping("/{slug}/revisions/{no}")
    public String revision(@PathVariable String slug, @PathVariable String no, Model model, HttpServletResponse response) {
        Integer revisionNo = parseRevisionNo(no);
        if (revisionNo == null) {
            return notFound(response);
        }
        return publicSitePageService.findRevision(slug, revisionNo)
                .map(view -> renderPage(view, model))
                .orElseGet(() -> notFound(response));
    }

    private String renderPage(PublicSitePageView view, Model model) {
        model.addAttribute("page", view);
        return "public/page/detail";
    }

    private String renderRevisions(PublicSitePageRevisions result, Model model) {
        model.addAttribute("revisions", result);
        return "public/page/revisions";
    }

    private String notFound(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return "error/404";
    }

    /** 파싱 실패(비숫자·범위 초과)만 0으로 흡수한다 — 음수·마지막 쪽 초과 보정은 서비스 책임. */
    private int parsePageOrZero(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Integer parseRevisionNo(String raw) {
        if (raw == null || !REVISION_NO.matcher(raw).matches()) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
