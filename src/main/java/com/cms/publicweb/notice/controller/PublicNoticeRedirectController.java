package com.cms.publicweb.notice.controller;

import com.cms.publicweb.board.service.PublicBoardService;
import com.cms.publicweb.support.PublicSite;
import com.cms.publicweb.support.PublicSiteRoutes;
import com.cms.publicweb.support.SiteScopedPublic;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Optional;

/**
 * 공개 공지 목록 {@code /{siteKey}/notices} → 그 사이트의 공지 게시판 {@code /{siteKey}/boards/{슬러그}}로 <b>302</b>(PLAN-board-definition.md PR 4b).
 * 공지는 공지 게시판의 게시글이라 공개 화면은 게시판 한 벌이고, 이 경로는 안정적인 진입점으로만 남는다. 상세·첨부 등 하위 경로는 핸들러가 없어 404다.
 *
 * <p>302인 이유: 대상이 바뀔 수 있다(사이트 전용 공지 게시판 생성·삭제, 공지 게시판 슬러그 1회 변경) — 301은 브라우저가 캐시해 따라가지 못한다.
 * {@code Location}은 서버가 현재 사이트 키·현재 슬러그로 만들고 원래 쿼리스트링(원문)만 붙인다(옛 검색·페이지 링크 유지). 공지 게시판이 없거나 공개
 * 조건을 통과하지 못하면 이전 목록과 같은 404. HEAD는 {@code @GetMapping}이 같은 핸들러로 처리한다. {@code @AdminPage}를 붙이지 않는다.
 */
@Controller
@SiteScopedPublic
@RequiredArgsConstructor
public class PublicNoticeRedirectController {

    private final PublicBoardService publicBoardService;

    @GetMapping(PublicSiteRoutes.NOTICES)
    public String list(HttpServletRequest request, HttpServletResponse response) {
        PublicSite site = PublicSiteRoutes.current(request);
        // Optional.map에 null을 돌려주는 람다를 쓰면 빈 값이 되어 404로 덮인다 — 분기로 쓴다
        Optional<String> slug = publicBoardService.findNoticeBoardSlug(site.id());
        if (slug.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }
        String query = request.getQueryString();
        String location = request.getContextPath() + "/" + site.key() + "/boards/" + slug.get() + (query == null ? "" : "?" + query);
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader(HttpHeaders.LOCATION, location);
        // null을 돌려주면 HttpServletResponse를 받은 핸들러라 Spring이 뷰를 찾지 않는다(요청 처리 완료)
        return null;
    }
}
