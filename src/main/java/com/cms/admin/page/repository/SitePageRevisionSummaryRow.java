package com.cms.admin.page.repository;

import java.time.LocalDateTime;

/** 개정 목록 한 행 — 번호·제목·개정일만(본문 미조회). */
public record SitePageRevisionSummaryRow(Integer revisionNo, String title, LocalDateTime createDate) {
}
