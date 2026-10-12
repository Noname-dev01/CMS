package com.cms.publicweb.contentimage;

/**
 * 공개 판정을 통과한 본문 이미지의 파일 위치·형식. storageKey는 응답·화면에 내보내지 않는다. {@code namespace}가 빈 문자열이면 루트 영역이고,
 * 아니면 {@code banner}·{@code popup}처럼 이관된 배너·팝업 이미지의 네임스페이스다(PLAN-media-library.md R2-4).
 */
public record ContentImageMeta(String storageKey, String namespace, String contentType) {
}
