package com.cms.publicweb.contentimage;

/** 공개 판정을 통과한 본문 이미지의 파일 위치·형식. storageKey는 응답·화면에 내보내지 않는다. */
public record ContentImageMeta(String storageKey, String contentType) {
}
