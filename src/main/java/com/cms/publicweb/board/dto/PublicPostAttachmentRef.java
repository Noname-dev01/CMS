package com.cms.publicweb.board.dto;

/**
 * 공개 조건 재검증을 통과한 첨부의 파일 참조 — {@code PublicBoardService.findPublishedAttachment}가 트랜잭션 안에서 만들고, 트랜잭션 밖의
 * {@code openAttachment}가 소비한다(열린 스트림이 트랜잭션 프록시를 통과하지 않도록 서비스를 둘로 나눈다).
 *
 * <p>{@code storageKey}는 서버 내부 경로이므로 <b>Model·뷰에 절대 넣지 않는다</b> — 화면용 {@link PublicPostAttachment}에는 이 필드가 없다.
 */
public record PublicPostAttachmentRef(String originalFilename, String storageKey) {
}
