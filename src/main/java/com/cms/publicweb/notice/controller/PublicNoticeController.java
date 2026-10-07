package com.cms.publicweb.notice.controller;

import com.cms.publicweb.notice.dto.PublicNoticeAttachmentDownload;
import com.cms.publicweb.notice.dto.PublicNoticeAttachmentRef;
import com.cms.publicweb.notice.dto.PublicNoticeDetail;
import com.cms.publicweb.notice.dto.PublicNoticeListResult;
import com.cms.publicweb.notice.dto.PublicNoticeSummary;
import com.cms.publicweb.notice.service.PublicNoticeService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    /**
     * 다운로드 복사 버퍼 — 요청당 힙 점유의 상한(파일 크기와 무관). 서블릿 컨테이너의 출력 버퍼(Tomcat
     * 기본 8KB)보다 작게 잡아, 첫 청크를 쓴 직후에는 컨테이너와 무관하게 항상 응답이 미커밋이도록 한다
     * ({@link #writeBody}의 "미커밋이지만 오염" 구간을 결정적으로 만들기 위함).
     */
    private static final int COPY_BUFFER_SIZE = 4096;

    private final PublicNoticeService publicNoticeService;

    /**
     * {@code keyword}는 문자열 그대로 받아 서비스에 넘긴다 — 문자열 바인딩은 실패하지 않으므로 전역 JSON
     * 핸들러로 샐 경로가 없고, 정규화(공백 제거·길이 상한)는 서비스 책임이다(PLAN-public-notice-search.md 쟁점 3·4).
     */
    @GetMapping
    public String list(@RequestParam(defaultValue = "0") String page,
                       @RequestParam(required = false) String keyword,
                       Model model) {
        PublicNoticeListResult listResult = publicNoticeService.getPublishedNotices(parsePageOrZero(page), keyword);
        Page<PublicNoticeSummary> result = listResult.page();
        model.addAttribute("keyword", listResult.keyword());
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
     * 첨부 다운로드(스트리밍). 비숫자 id/attachmentId, 비공개·삭제 notice, 없는 첨부, 타 notice의
     * attachmentId를 전부 동일한 404로 응답한다(존재 여부 열거 방지 — 상세와 동일 원칙).
     *
     * <p>404는 {@code response.sendError}로 처리한다 — 컨테이너 에러 디스패치({@code /error} →
     * {@code CustomErrorController})를 유발해 기존 {@code error/404.html}을 그대로 렌더링한다(상세 404와
     * 동일 UX). 반환 타입이 {@code void}이고 {@code HttpServletResponse}를 받으므로 Spring은 뷰 해석을
     * 하지 않는다.
     *
     * <p>파일은 힙에 통째로 올리지 않고 고정 크기 버퍼로 응답에 흘려보낸다
     * (PLAN-public-notice-attachment.md 후속 작업). 열린 스트림은 이 메서드가 {@code try-with-resources}로
     * 단독 소유·close한다. 전송·close 중 실패의 응답 상태별 처리는 {@link #writeBody}·{@link #resetIfUncommitted} 참조.
     */
    @GetMapping("/{id}/attachments/{attachmentId}")
    public void attachment(@PathVariable String id,
                           @PathVariable String attachmentId,
                           HttpServletResponse response) throws IOException {
        Optional<PublicNoticeAttachmentDownload> download = openDownload(id, attachmentId, response);
        if (download.isEmpty()) {
            return;
        }
        try (PublicNoticeAttachmentDownload opened = download.get()) {
            writeBody(opened, response);
        } catch (IOException e) {
            resetIfUncommitted(response);
            throw e;
        }
    }

    /**
     * 첨부 다운로드 HEAD. GET과 <b>정확히 같은 경로</b>(공개 조건 재검증·IDOR·파일 존재 확인)로 404를
     * 판정하고, 본문 바이트는 읽지 않는다 — 파일 핸들을 열어 크기만 얻은 뒤 곧바로 닫는다
     * (PLAN-public-notice-attachment.md 결정 S4). 같은 경로에 명시 HEAD 매핑이 있으면 Spring은
     * {@code @GetMapping}의 암묵 HEAD 처리보다 이 핸들러를 우선한다.
     */
    @RequestMapping(value = "/{id}/attachments/{attachmentId}", method = RequestMethod.HEAD)
    public void attachmentHead(@PathVariable String id,
                               @PathVariable String attachmentId,
                               HttpServletResponse response) throws IOException {
        Optional<PublicNoticeAttachmentDownload> download = openDownload(id, attachmentId, response);
        if (download.isEmpty()) {
            return;
        }
        try (PublicNoticeAttachmentDownload opened = download.get()) {
            applyDownloadHeaders(opened, response);
        } catch (IOException e) {
            resetIfUncommitted(response);
            throw e;
        }
    }

    /**
     * id 파싱 → 공개 조건 재검증(트랜잭션) → 파일 열기(트랜잭션 밖). 어느 단계든 실패하면 동일한 404를
     * 보내고 empty를 반환한다. 예외를 던지지 않는다(던지면 {@code PublicWebExceptionAdvice}가 404가 아니라
     * HTML 500으로 만든다). 반환된 DTO는 호출자가 곧바로 try-with-resources로 닫아야 한다.
     */
    private Optional<PublicNoticeAttachmentDownload> openDownload(String id, String attachmentId,
                                                                  HttpServletResponse response) throws IOException {
        Long noticeId = parseId(id);
        Long parsedAttachmentId = parseId(attachmentId);
        if (noticeId == null || parsedAttachmentId == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return Optional.empty();
        }

        Optional<PublicNoticeAttachmentRef> ref =
                publicNoticeService.findPublishedAttachment(noticeId, parsedAttachmentId);
        Optional<PublicNoticeAttachmentDownload> download = ref.flatMap(publicNoticeService::openAttachment);
        if (download.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
        return download;
    }

    /**
     * 응답 상태를 세 구간으로 나눠 전송 중 실패를 처리한다(PLAN-public-notice-attachment.md 결정 S5).
     * <ol>
     *   <li><b>응답 무손대</b>: 헤더·출력 스트림에 손대기 전에 첫 청크를 먼저 읽는다. 이 읽기가 실패하면
     *       응답이 그대로라 예외가 {@code PublicWebExceptionAdvice}의 HTML 500으로 이어진다.</li>
     *   <li><b>미커밋이지만 오염</b>(헤더·Content-Length 설정, 출력 스트림 획득, 버퍼에 일부 기록 뒤 실패):
     *       {@code response.reset()}으로 상태·헤더·버퍼·출력 방식을 초기화한 뒤 재던진다 — advice가
     *       HTML 500을 정상 렌더링할 수 있게 한다.</li>
     *   <li><b>커밋 후</b>: 그대로 재던진다 — advice가 뷰를 렌더링하지 않고 다시 던져 컨테이너가
     *       연결을 중단하게 한다(잘린 파일이 정상 응답처럼 보이지 않도록).</li>
     * </ol>
     */
    private void writeBody(PublicNoticeAttachmentDownload download, HttpServletResponse response) throws IOException {
        InputStream in = download.content();
        byte[] buffer = new byte[COPY_BUFFER_SIZE];

        int first = in.readNBytes(buffer, 0, COPY_BUFFER_SIZE);

        applyDownloadHeaders(download, response);
        OutputStream out = response.getOutputStream();
        out.write(buffer, 0, first);
        if (first == COPY_BUFFER_SIZE) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    /**
     * 미커밋이면 상태·헤더(Content-Length 포함)·버퍼·출력 방식을 전부 초기화한다 — 뒤이어 재던져지는
     * 예외를 advice가 HTML 500으로 렌더링할 수 있게 한다. 전송 중 읽기·쓰기 실패뿐 아니라 스트림
     * close 실패(try-with-resources가 던짐)에도 적용하려고 GET·HEAD의 try 전체를 감싸는 catch에서 호출한다.
     */
    private void resetIfUncommitted(HttpServletResponse response) {
        if (!response.isCommitted()) {
            response.reset();
        }
    }

    private void applyDownloadHeaders(PublicNoticeAttachmentDownload download, HttpServletResponse response) {
        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(download.originalFilename(), StandardCharsets.UTF_8)
                .build();

        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentLengthLong(download.contentLength());
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
