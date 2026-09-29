package com.cms.publicweb.notice.dto;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * 공개 다운로드 응답 구성에 필요한 최소 정보 — 컨트롤러가 헤더를 구성한다.
 * admin {@code NoticeAttachmentDownload}와 동형이지만 재사용하지 않는다(publicweb DTO 경계 유지).
 * 응답 Content-Type은 항상 {@code application/octet-stream}으로 강제하므로
 * {@code contentType} 필드를 두지 않는다.
 *
 * <p>{@code content}는 <b>열린 파일 스트림</b>이다(전량 메모리 로딩 방지 — PLAN-public-notice-attachment.md
 * 후속 작업). 받은 쪽(컨트롤러)이 반드시 {@link #close()}한다. {@code contentLength}는 스트림을 연 것과
 * 같은 파일 핸들에서 읽은 크기다.
 */
public record PublicNoticeAttachmentDownload(String originalFilename, long contentLength, InputStream content)
        implements Closeable {

    @Override
    public void close() throws IOException {
        content.close();
    }
}
