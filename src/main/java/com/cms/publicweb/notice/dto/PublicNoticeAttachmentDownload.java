package com.cms.publicweb.notice.dto;

/**
 * 공개 다운로드 응답 구성에 필요한 최소 정보 — 컨트롤러가 헤더를 구성한다.
 * admin {@code NoticeAttachmentDownload}와 동형이지만 재사용하지 않는다(publicweb DTO 경계 유지).
 * 응답 Content-Type은 항상 {@code application/octet-stream}으로 강제하므로
 * {@code contentType} 필드를 두지 않는다.
 */
public record PublicNoticeAttachmentDownload(String originalFilename, byte[] content) {
}
