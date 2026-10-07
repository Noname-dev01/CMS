package com.cms.publicweb.contentimage;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;

/**
 * 본문 이미지 공개 다운로드 {@code GET/HEAD /content-images/{id}}(PLAN-html-editor.md 쟁점 6). {@code SecurityConfig}가
 * 이 경로의 GET·HEAD만 무인증 공개하고, 공개 여부 판정은 {@link PublicContentImageService}가 한다.
 *
 * <p>{@code id}는 문자열로 받아 직접 파싱한다 — 비숫자·{@code Long} 범위 밖·없는 ID·비공개만 참조하는 이미지를 모두 같은 404로
 * 흡수해 존재 여부를 드러내지 않는다(공개 공지 첨부와 같은 정책). 응답은 저장된 이미지 형식 그대로 인라인({@code Content-Disposition}
 * 없음)이고 {@code nosniff}·{@code no-store}를 붙인다(캐시가 공개 상태 재검증을 우회하지 않게). 전송 중 실패 처리는
 * {@code PublicNoticeController} 첨부 다운로드와 같다(첫 청크 선읽기 → 미커밋이면 reset 후 재던짐).
 */
@Controller
@RequestMapping("/content-images")
@RequiredArgsConstructor
public class PublicContentImageController {

    /** 컨테이너 출력 버퍼(Tomcat 8KB)보다 작아야 "첫 청크 직후 미커밋"이 성립한다. */
    private static final int COPY_BUFFER_SIZE = 4096;

    private final PublicContentImageService publicContentImageService;

    @GetMapping("/{id}")
    public void image(@PathVariable String id, HttpServletResponse response) throws IOException {
        Optional<ContentImageDownload> download = openDownload(id, response);
        if (download.isEmpty()) {
            return;
        }
        try (ContentImageDownload opened = download.get()) {
            InputStream in = opened.content();
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int first = in.readNBytes(buffer, 0, COPY_BUFFER_SIZE);
            applyHeaders(opened, response);
            OutputStream out = response.getOutputStream();
            out.write(buffer, 0, first);
            if (first == COPY_BUFFER_SIZE) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
        } catch (IOException e) {
            resetIfUncommitted(response);
            throw e;
        }
    }

    /** HEAD는 GET과 같은 경로로 404를 판정하고 본문은 읽지 않는다. */
    @RequestMapping(value = "/{id}", method = RequestMethod.HEAD)
    public void imageHead(@PathVariable String id, HttpServletResponse response) throws IOException {
        Optional<ContentImageDownload> download = openDownload(id, response);
        if (download.isEmpty()) {
            return;
        }
        try (ContentImageDownload opened = download.get()) {
            applyHeaders(opened, response);
        } catch (IOException e) {
            resetIfUncommitted(response);
            throw e;
        }
    }

    private Optional<ContentImageDownload> openDownload(String id, HttpServletResponse response) throws IOException {
        Long imageId = parseId(id);
        Optional<ContentImageDownload> download = imageId == null
                ? Optional.empty()
                : publicContentImageService
                        .findViewable(imageId, SecurityContextHolder.getContext().getAuthentication())
                        .flatMap(publicContentImageService::open);
        if (download.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
        return download;
    }

    private void applyHeaders(ContentImageDownload download, HttpServletResponse response) {
        response.setContentType(download.contentType());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentLengthLong(download.contentLength());
    }

    private void resetIfUncommitted(HttpServletResponse response) {
        if (!response.isCommitted()) {
            response.reset();
        }
    }

    private Long parseId(String raw) {
        try {
            long value = Long.parseLong(raw);
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
