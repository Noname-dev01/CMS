package com.cms.admin.notice.dto.response;

/**
 * 다운로드 응답 구성에 필요한 최소 정보 — 컨트롤러가 헤더를 구성한다.
 * 응답 Content-Type은 항상 {@code application/octet-stream}으로 강제하므로(스니핑에 의한 실행
 * 유도 차단, PLAN-notice-attachment.md 쟁점 5) 저장된 선언 Content-Type은 여기 포함하지 않는다.
 */
public record NoticeAttachmentDownload(String originalFilename, byte[] content) {
}
