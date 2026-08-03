package com.cms.publicweb.notice.controller;

import com.cms.publicweb.notice.dto.PublicNoticeAttachmentDownload;
import com.cms.publicweb.notice.dto.PublicNoticeDetail;
import com.cms.publicweb.notice.dto.PublicNoticeSummary;
import com.cms.publicweb.notice.service.PublicNoticeService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 공개(비로그인) 공지 목록·상세·첨부 다운로드. {@code @AdminPage}를 붙이지 않는다 — {@code publicweb}
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
 *
 * <p>{@code SecurityConfig}의 {@code /notices, /notices/**} GET/HEAD {@code permitAll}은
 * {@code /**}가 하위 세그먼트 전체를 포괄하므로, 이 컨트롤러에 라우트를 추가하는 즉시
 * 별도 인가 정책 변경 없이 무인증 공개가 된다(PLAN-public-notice-attachment.md 참조) — 접근
 * 통제는 전량 {@link PublicNoticeService}의 공개 조건 재검증이 담당한다.
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

    /**
     * 첨부 다운로드. 비숫자 id/attachmentId, 비공개·삭제 notice, 없는 첨부, 타 notice의
     * attachmentId를 전부 동일한 404로 응답한다(존재 여부 열거 방지 — 상세와 동일 원칙).
     *
     * <p>404는 {@code response.sendError} + {@code null} 반환으로 처리한다. Spring MVC의
     * {@code HttpEntityMethodProcessor}는 핸들러 반환값이 {@code null}이면 {@code requestHandled=true}로
     * 처리하고 종료하므로 {@code ResponseEntity} 반환 타입에서도 안전하게 동작한다 — 이 계약에
     * 의존한다. {@code sendError}는 컨테이너 에러 디스패치({@code /error} → {@code CustomErrorController})를
     * 유발해 기존 {@code error/404.html}을 그대로 렌더링한다(상세 404와 동일 UX).
     *
     * <p>예외를 절대 던지지 않는다 — 던지면 {@code PublicWebExceptionAdvice}의 {@code Exception}
     * 폴백이 먼저 매칭되어 404가 아니라 HTML 500이 된다.
     */
    @GetMapping("/{id}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> attachment(@PathVariable String id,
                                              @PathVariable String attachmentId,
                                              HttpServletResponse response) throws IOException {
        Long noticeId = parseId(id);
        Long parsedAttachmentId = parseId(attachmentId);
        if (noticeId == null || parsedAttachmentId == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }

        Optional<PublicNoticeAttachmentDownload> download =
                publicNoticeService.downloadPublishedAttachment(noticeId, parsedAttachmentId);
        if (download.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }

        return toResponse(download.get());
    }

    private ResponseEntity<byte[]> toResponse(PublicNoticeAttachmentDownload download) {
        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(download.originalFilename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(download.content());
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
