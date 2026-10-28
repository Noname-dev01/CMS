package com.cms.publicweb.board.controller;

import com.cms.publicweb.support.PublicSiteRoutes;
import com.cms.publicweb.support.SiteScopedPublic;
import com.cms.publicweb.board.dto.PublicBoardListResult;
import com.cms.publicweb.board.dto.PublicBoardRoute;
import com.cms.publicweb.board.dto.PublicPostAttachmentDownload;
import com.cms.publicweb.board.dto.PublicPostAttachmentRef;
import com.cms.publicweb.board.dto.PublicPostDetail;
import com.cms.publicweb.board.dto.PublicPostSummary;
import com.cms.publicweb.board.service.PublicBoardService;
import com.cms.admin.board.service.ContentCounterService;
import com.cms.common.web.PathIds;
import jakarta.servlet.http.HttpServletRequest;
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
 * 공개(비로그인) 게시판 목록·게시글 상세·첨부 다운로드. {@code @AdminPage}를 붙이지 않는다 — {@code publicweb}는 관리자 화면이 아니며, 붙이면
 * {@code AdminSidebarAdvice}가 공개 요청마다 메뉴 DB 조회를 날린다.
 *
 * <p>게시판 세그먼트는 슬러그다(PLAN-board-definition.md PR 4a) — 기본 슬러그 {@code board-{id}} 또는 사용자 슬러그이고, 숫자 id 경로는 404, 슬러그를 바꾼 게시판의
 * 옛 기본 슬러그는 새 슬러그로 301이다(해석은 {@link PublicBoardService#resolveBoard}).
 *
 * <p>{@code postId}·{@code attachmentId}·{@code page}를 {@code Long}/{@code Integer}로 바인딩하지 않고 String으로 받아 직접 파싱한다({@link PathIds}) — Spring 바인딩에
 * 맡기면 타입 변환 실패 시 컨트롤러 진입 전에 {@code MethodArgumentTypeMismatchException}이 발생하고, 이는 전역 {@code GlobalApiExceptionHandler}가
 * 잡아 JSON으로 응답해버린다(공지 공개 컨트롤러와 같은 이유). 이 컨트롤러의 책임은 "예외를 던지지 않는 것"뿐이며 값의 비즈니스 검증은
 * {@link PublicBoardService}가 담당한다.
 *
 * <p>{@code SecurityConfig}의 {@code /boards, /boards/**} GET/HEAD {@code permitAll}은 {@code /**}가 하위 세그먼트 전체를 포괄하므로, 이 컨트롤러에
 * 라우트를 추가하는 즉시 별도 인가 정책 변경 없이 무인증 공개가 된다 — 접근 통제는 전량 {@link PublicBoardService}의 공개 조건 재검증이 담당한다.
 * 비공개 게시판·삭제된 게시판·미노출·삭제 게시글·다른 게시판 소속 게시글·숫자 게시판 id·형식 위반 슬러그·비숫자 ID는 모두 같은 404다(존재 여부 열거 방지).
 */
@Controller
@SiteScopedPublic
@RequestMapping(PublicSiteRoutes.BOARDS)
@RequiredArgsConstructor
public class PublicBoardController {

    /**
     * 다운로드 복사 버퍼 — 요청당 힙 점유의 상한(파일 크기와 무관). 서블릿 컨테이너의 출력 버퍼(Tomcat 기본 8KB)보다 작게 잡아, 첫 청크를 쓴 직후에는
     * 컨테이너와 무관하게 항상 응답이 미커밋이도록 한다({@link #writeBody}의 "미커밋이지만 오염" 구간을 결정적으로 만들기 위함).
     */
    private static final int COPY_BUFFER_SIZE = 4096;

    private final PublicBoardService publicBoardService;
    private final ContentCounterService contentCounterService;

    /** {@code keyword}는 문자열 그대로 서비스에 넘긴다 — 정규화(공백 제거·길이 상한)는 서비스 책임이다. */
    @GetMapping("/{boardSlug}")
    public String list(@PathVariable String boardSlug,
                       @RequestParam(defaultValue = "0") String page,
                       @RequestParam(required = false) String keyword,
                       Model model,
                       HttpServletRequest request,
                       HttpServletResponse response) {
        PublicBoardRoute route = publicBoardService.resolveBoard(siteId(request), boardSlug);
        if (route instanceof PublicBoardRoute.Moved moved) {
            return movedPermanently(request, response, moved.slug(), "");
        }
        if (!(route instanceof PublicBoardRoute.Found found)) {
            return notFound(response);
        }
        return publicBoardService.getPublishedPosts(siteId(request), found.boardId(), parsePageOrZero(page), keyword)
                .map(result -> renderList(result, model))
                .orElseGet(() -> notFound(response));
    }

    @GetMapping("/{boardSlug}/posts/{postId}")
    public String detail(@PathVariable String boardSlug, @PathVariable String postId, Model model,
                         HttpServletRequest request, HttpServletResponse response) {
        Long parsedPostId = PathIds.parse(postId);
        if (parsedPostId == null) {
            return notFound(response);
        }
        PublicBoardRoute route = publicBoardService.resolveBoard(siteId(request), boardSlug);
        if (route instanceof PublicBoardRoute.Moved moved) {
            return movedPermanently(request, response, moved.slug(), "/posts/" + parsedPostId);
        }
        if (!(route instanceof PublicBoardRoute.Found found)) {
            return notFound(response);
        }
        Long parsedBoardId = found.boardId();
        return publicBoardService.findPublishedPost(siteId(request), parsedBoardId, parsedPostId)
                .map(post -> {
                    countViewIfGet(parsedPostId, request);
                    return renderDetail(post, model);
                })
                .orElseGet(() -> notFound(response));
    }

    /**
     * 첨부 다운로드(스트리밍). 비숫자 ID, 비공개 게시판·삭제 게시판, 미노출·삭제 게시글, 다른 게시판 소속 게시글, 없는 첨부, 타 게시글의 attachmentId를
     * 전부 동일한 404로 응답한다. 404는 {@code response.sendError}로 처리한다 — 컨테이너 에러 디스패치({@code /error} → {@code CustomErrorController})를
     * 유발해 기존 {@code error/404.html}을 그대로 렌더링한다. 반환 타입이 {@code void}이고 {@code HttpServletResponse}를 받으므로 Spring은 뷰 해석을 하지 않는다.
     *
     * <p>파일은 힙에 통째로 올리지 않고 고정 크기 버퍼로 응답에 흘려보낸다. 열린 스트림은 이 메서드가 {@code try-with-resources}로 단독 소유·close한다.
     * 전송·close 중 실패의 응답 상태별 처리는 {@link #writeBody}·{@link #resetIfUncommitted} 참조.
     */
    @GetMapping("/{boardSlug}/posts/{postId}/attachments/{attachmentId}")
    public void attachment(@PathVariable String boardSlug,
                           @PathVariable String postId,
                           @PathVariable String attachmentId,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException {
        Optional<PublicPostAttachmentDownload> download = openDownload(boardSlug, postId, attachmentId, request, response);
        if (download.isEmpty()) {
            return;
        }
        try (PublicPostAttachmentDownload opened = download.get()) {
            // 파일 열기에 성공한 GET만 센다(HEAD 제외). 예외를 던지지 않으므로 아래 전송 실패 처리에 영향이 없다.
            contentCounterService.recordDownload(PathIds.parse(attachmentId), request.getRemoteAddr());
            writeBody(opened, response);
        } catch (IOException e) {
            resetIfUncommitted(response);
            throw e;
        }
    }

    /**
     * 첨부 다운로드 HEAD. GET과 <b>정확히 같은 경로</b>(공개 조건 재검증·IDOR·파일 존재 확인)로 404를 판정하고, 본문 바이트는 읽지 않는다 —
     * 파일 핸들을 열어 크기만 얻은 뒤 곧바로 닫는다. 같은 경로에 명시 HEAD 매핑이 있으면 Spring은 {@code @GetMapping}의 암묵 HEAD 처리보다
     * 이 핸들러를 우선한다.
     */
    @RequestMapping(value = "/{boardSlug}/posts/{postId}/attachments/{attachmentId}", method = RequestMethod.HEAD)
    public void attachmentHead(@PathVariable String boardSlug,
                               @PathVariable String postId,
                               @PathVariable String attachmentId,
                               HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
        Optional<PublicPostAttachmentDownload> download = openDownload(boardSlug, postId, attachmentId, request, response);
        if (download.isEmpty()) {
            return;
        }
        try (PublicPostAttachmentDownload opened = download.get()) {
            applyDownloadHeaders(opened, response);
        } catch (IOException e) {
            resetIfUncommitted(response);
            throw e;
        }
    }

    /**
     * ID 파싱 → 게시판 세그먼트 해석(옛 기본 슬러그면 301) → 공개 조건 재검증(트랜잭션) → 파일 열기(트랜잭션 밖). 어느 단계든 실패하면 동일한 404를
     * 보내고 empty를 반환한다. 예외를 던지지 않는다(던지면 {@code PublicWebExceptionAdvice}가 404가 아니라 HTML 500으로 만든다). 반환된 DTO는 호출자가
     * 곧바로 try-with-resources로 닫아야 한다. 301은 파일을 열지 않으므로 다운로드로 세지 않는다.
     */
    private Optional<PublicPostAttachmentDownload> openDownload(String boardSlug, String postId, String attachmentId,
                                                                HttpServletRequest request, HttpServletResponse response) throws IOException {
        Long parsedPostId = PathIds.parse(postId);
        Long parsedAttachmentId = PathIds.parse(attachmentId);
        if (parsedPostId == null || parsedAttachmentId == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return Optional.empty();
        }
        PublicBoardRoute route = publicBoardService.resolveBoard(siteId(request), boardSlug);
        if (route instanceof PublicBoardRoute.Moved moved) {
            movedPermanently(request, response, moved.slug(), "/posts/" + parsedPostId + "/attachments/" + parsedAttachmentId);
            return Optional.empty();
        }
        if (!(route instanceof PublicBoardRoute.Found found)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return Optional.empty();
        }
        Long parsedBoardId = found.boardId();

        Optional<PublicPostAttachmentRef> ref =
                publicBoardService.findPublishedAttachment(siteId(request), parsedBoardId, parsedPostId, parsedAttachmentId);
        Optional<PublicPostAttachmentDownload> download = ref.flatMap(publicBoardService::openAttachment);
        if (download.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
        return download;
    }

    /**
     * 응답 상태를 세 구간으로 나눠 전송 중 실패를 처리한다(공지 공개 컨트롤러와 같다).
     * <ol>
     *   <li><b>응답 무손대</b>: 헤더·출력 스트림에 손대기 전에 첫 청크를 먼저 읽는다. 이 읽기가 실패하면 응답이 그대로라 예외가
     *       {@code PublicWebExceptionAdvice}의 HTML 500으로 이어진다.</li>
     *   <li><b>미커밋이지만 오염</b>(헤더·Content-Length 설정, 출력 스트림 획득, 버퍼에 일부 기록 뒤 실패): {@code response.reset()}으로 상태·헤더·버퍼·
     *       출력 방식을 초기화한 뒤 재던진다 — advice가 HTML 500을 정상 렌더링할 수 있게 한다.</li>
     *   <li><b>커밋 후</b>: 그대로 재던진다 — advice가 뷰를 렌더링하지 않고 다시 던져 컨테이너가 연결을 중단하게 한다(잘린 파일이 정상 응답처럼 보이지 않도록).</li>
     * </ol>
     */
    private void writeBody(PublicPostAttachmentDownload download, HttpServletResponse response) throws IOException {
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

    /** 미커밋이면 상태·헤더(Content-Length 포함)·버퍼·출력 방식을 전부 초기화한다 — 뒤이어 재던져지는 예외를 advice가 HTML 500으로 렌더링할 수 있게 한다. */
    private void resetIfUncommitted(HttpServletResponse response) {
        if (!response.isCommitted()) {
            response.reset();
        }
    }

    private void applyDownloadHeaders(PublicPostAttachmentDownload download, HttpServletResponse response) {
        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(download.originalFilename(), StandardCharsets.UTF_8)
                .build();

        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentLengthLong(download.contentLength());
    }

    /** HEAD는 같은 핸들러로 들어오지만 조회로 세지 않는다. 예외를 던지지 않는다. */
    private void countViewIfGet(Long postId, HttpServletRequest request) {
        if (!"HEAD".equals(request.getMethod())) {
            contentCounterService.recordView(postId, request.getRemoteAddr());
        }
    }

    private String renderList(PublicBoardListResult listResult, Model model) {
        Page<PublicPostSummary> result = listResult.page();
        model.addAttribute("boardId", listResult.boardId());
        model.addAttribute("boardSlug", listResult.boardSlug());
        model.addAttribute("boardName", listResult.boardName());
        model.addAttribute("description", listResult.description());
        model.addAttribute("authorLabel", listResult.authorLabel());
        model.addAttribute("keyword", listResult.keyword());
        model.addAttribute("pinnedPosts", listResult.pinned());
        model.addAttribute("posts", result.getContent());
        model.addAttribute("page", result.getNumber());
        model.addAttribute("totalPages", result.getTotalPages());
        model.addAttribute("hasPrevious", result.hasPrevious());
        model.addAttribute("hasNext", result.hasNext());
        return "public/board/list";
    }

    private String renderDetail(PublicPostDetail post, Model model) {
        model.addAttribute("post", post);
        return "public/board/detail";
    }

    /** 현재 요청의 사이트 ID — {@code @SiteScopedPublic} 컨트롤러라 인터셉터가 항상 채운다(PLAN-site-menu.md PR ④). */
    private static Long siteId(HttpServletRequest request) {
        return PublicSiteRoutes.current(request).id();
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

    /**
     * 옛 기본 슬러그 → 현재 슬러그 301(PR 4a D4). {@code Location}은 원문 경로를 잘라 붙이지 않고 서버가 정규형으로 다시 만든다 — 현재 사이트 키·슬러그·
     * {@link PathIds}로 읽은 id(호출자가 {@code suffix}로 넘김) + 원래 쿼리스트링. 슬러그는 한 번만 바뀌고 다시 쓰이지 않으므로 영구 이동이다.
     * {@code null}을 돌려주면 {@code HttpServletResponse}를 받은 핸들러라 Spring이 뷰를 찾지 않는다(요청 처리 완료).
     */
    private static String movedPermanently(HttpServletRequest request, HttpServletResponse response, String slug, String suffix) {
        String query = request.getQueryString();
        String location = request.getContextPath() + "/" + PublicSiteRoutes.current(request).key() + "/boards/" + slug + suffix
                + (query == null ? "" : "?" + query);
        response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
        response.setHeader(HttpHeaders.LOCATION, location);
        return null;
    }
}
