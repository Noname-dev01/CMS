package com.cms.publicweb.notice.dto;

import org.springframework.data.domain.Page;

/**
 * 공개 목록 조회 결과. {@code keyword}는 서비스가 정규화한 검색어(검색하지 않았으면 {@code null})이며,
 * 화면이 검색 폼 값·페이지 링크에 그대로 쓴다 — 컨트롤러가 정규화를 다시 하지 않기 위해서다
 * (adversarial-review/plan/PLAN-public-notice-search.md 쟁점 4).
 */
public record PublicNoticeListResult(Page<PublicNoticeSummary> page, String keyword) {
}
