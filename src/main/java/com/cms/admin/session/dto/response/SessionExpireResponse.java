package com.cms.admin.session.dto.response;

/** 회원 단위 만료 응답. {@code expiredCount}는 이번 요청이 조회 시점의 활성 세션에 만료를 호출한 수(참고용). */
public record SessionExpireResponse(int expiredCount) {
}
