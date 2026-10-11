package com.cms.admin.page.repository;

/** 공개 개정 목록·푸터용 — 현재 개정의 제목만(본문 미조회). */
public record PublicSitePageHeaderRow(Long pageId, String slug, String title, Integer currentRevisionNo) {
}
