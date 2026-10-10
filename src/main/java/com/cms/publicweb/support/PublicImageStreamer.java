package com.cms.publicweb.support;

import com.cms.common.storage.StoredFileStream;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 무인증 공개 이미지의 스트리밍 응답 공통부(배너가 먼저 쓰고 ⑦ 팝업이 재사용한다 — PLAN-public-home-banner.md 쟁점 11).
 * {@code PublicContentImageController}의 전송 방식과 같다: 첫 청크를 먼저 읽어 보고(읽기 실패가 응답을 오염시키기 전에) 헤더를 정한 뒤
 * 쓰고, 전송 중 {@link IOException}이면 <b>응답이 아직 커밋되지 않은 경우에만</b> {@code reset()}한 뒤 다시 던진다(컨테이너가 연결을
 * 중단하게 — 잘린 이미지가 정상 응답처럼 보이지 않도록). 응답 헤더는 저장된 {@code image/*} 인라인, {@code nosniff}, {@code no-store}다.
 *
 * <p>버퍼(4KB)는 컨테이너 출력 버퍼(Tomcat 8KB)보다 작아야 "첫 청크 직후 미커밋"이 성립한다. 호출자가 열린
 * {@link StoredFileStream}의 소유권을 이 메서드에 넘기며, 이 메서드가 닫는다.
 */
public final class PublicImageStreamer {

    private static final int COPY_BUFFER_SIZE = 4096;

    private PublicImageStreamer() {
    }

    public static void stream(StoredFileStream opened, String contentType, HttpServletResponse response) throws IOException {
        try (opened) {
            InputStream in = opened.inputStream();
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int first = in.readNBytes(buffer, 0, COPY_BUFFER_SIZE);
            applyHeaders(contentType, opened.size(), response);
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

    /** HEAD — GET과 같은 경로로 404를 판정한 뒤 헤더만 쓰고 본문은 읽지 않는다. */
    public static void headOnly(StoredFileStream opened, String contentType, HttpServletResponse response) throws IOException {
        try (opened) {
            applyHeaders(contentType, opened.size(), response);
        } catch (IOException e) {
            resetIfUncommitted(response);
            throw e;
        }
    }

    private static void applyHeaders(String contentType, long contentLength, HttpServletResponse response) {
        response.setContentType(contentType);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentLengthLong(contentLength);
    }

    private static void resetIfUncommitted(HttpServletResponse response) {
        if (!response.isCommitted()) {
            response.reset();
        }
    }
}
