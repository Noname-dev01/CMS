package com.cms.publicweb.banner.dto;

/** 공개 배너 이미지를 열기 위한 파일 참조(조회 트랜잭션이 끝난 뒤 열린 자원 없이 넘긴다). */
public record PublicBannerImageRef(String storageKey, String contentType) {
}
