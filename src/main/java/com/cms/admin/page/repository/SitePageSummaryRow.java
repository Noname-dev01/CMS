package com.cms.admin.page.repository;

import java.time.LocalDateTime;

/** 관리 목록 한 행 — 본문({@code MEDIUMTEXT})을 읽지 않는 프로젝션(PLAN-static-page.md R1-4). */
public record SitePageSummaryRow(Long id, String slug, String title, Integer currentRevisionNo, Integer version,
                                 Boolean useYn, Boolean footerYn, LocalDateTime createDate, LocalDateTime updateDate) {
}
