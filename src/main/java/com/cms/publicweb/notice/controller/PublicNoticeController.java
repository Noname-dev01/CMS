package com.cms.publicweb.notice.controller;

import com.cms.publicweb.notice.dto.PublicNoticeDetail;
import com.cms.publicweb.notice.dto.PublicNoticeSummary;
import com.cms.publicweb.notice.service.PublicNoticeService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 공개(비로그인) 공지 목록·상세. {@code @AdminPage}를 붙이지 않는다 — {@code publicweb}
 * 패키지는 {@code AdminPageAnnotationConventionTest}(스캔 범위 {@code com.cms.admin})의
 * 대상이 아니며, 붙이면 {@code AdminSidebarAdvice}가 공개 요청마다 메뉴 DB 조회를 날린다.
 *
 * <p>{@code id}·{@code page}를 {@code Long}/{@code Integer}로 바인딩하지 않고 String으로
 * 받아 직접 파싱한다 — Spring 바인딩에 맡기면 타입 변환 실패 시 컨트롤러 진입 전에
 * {@code MethodArgumentTypeMismatchException}이 발생하고, 이는 전역
 * {@code GlobalApiExceptionHandler}(모든 컨트롤러에 적용되는 {@code @RestControllerAdvice})가
 * 잡아 JSON으로 응답해버린다(PLAN-public-notice.md 결정 3-1). 이 컨트롤러의 책임은
 * "예외를 던지지 않는 것"뿐이다 — 파싱에 성공한 값(음수 포함)의 비즈니스 검증은
 * {@link PublicNoticeService}가 담당한다.
 */
@Controller
@RequestMapping("/notices")
@RequiredArgsConstructor
public class PublicNoticeController {

    private final PublicNoticeService publicNoticeService;

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") String page, Model model) {
        Page<PublicNoticeSummary> result = publicNoticeService.getPublishedNotices(parsePageOrZero(page));
        model.addAttribute("notices", result.getContent());
        model.addAttribute("page", result.getNumber());
        model.addAttribute("totalPages", result.getTotalPages());
        model.addAttribute("hasPrevious", result.hasPrevious());
        model.addAttribute("hasNext", result.hasNext());
        return "public/notice/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable String id, Model model, HttpServletResponse response) {
        Long noticeId = parseId(id);
        if (noticeId == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }

        return publicNoticeService.findPublishedNotice(noticeId)
                .map(notice -> renderDetail(notice, model))
                .orElseGet(() -> notFound(response));
    }

    private String renderDetail(PublicNoticeDetail notice, Model model) {
        model.addAttribute("notice", notice);
        return "public/notice/detail";
    }

    private String notFound(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return "error/404";
    }

    /** 파싱 실패(비숫자·범위 초과)만 0으로 흡수한다 — 음수 등 값 자체의 검증은 Service 책임. */
    private int parsePageOrZero(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
