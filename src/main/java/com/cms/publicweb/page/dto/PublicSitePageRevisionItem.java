package com.cms.publicweb.page.dto;

import java.time.LocalDateTime;

/** 공개 개정 목록의 한 줄 — 본문 없음. */
public record PublicSitePageRevisionItem(int revisionNo, String title, LocalDateTime revisionDate, boolean current) {
}
