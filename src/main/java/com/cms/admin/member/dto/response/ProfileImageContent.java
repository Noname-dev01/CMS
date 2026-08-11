package com.cms.admin.member.dto.response;

/**
 * 서비스↔컨트롤러 내부 전송 전용 — HTTP 응답 바디로 직접(JSON) 노출되지 않는다.
 * 컨트롤러는 {@code content}를 {@code ResponseEntity<byte[]>}의 본문으로 그대로 반환한다
 * (NoticeAttachmentController.content()와 동일 패턴).
 */
public record ProfileImageContent(byte[] content, String contentType) {
}
