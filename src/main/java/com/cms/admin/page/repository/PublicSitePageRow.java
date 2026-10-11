package com.cms.admin.page.repository;

import java.time.LocalDateTime;

/** 공개 화면용 한 개정 — 페이지가 노출 중일 때만 조회된다. {@code currentRevisionNo}는 이 개정이 현재 개정인지 판정하는 데 쓴다. */
public record PublicSitePageRow(Long pageId, String slug, String title, String content, Integer revisionNo,
                                LocalDateTime revisionDate, Integer currentRevisionNo) {
}
