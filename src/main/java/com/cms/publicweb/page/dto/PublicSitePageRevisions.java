package com.cms.publicweb.page.dto;

import java.util.List;

/** 공개 개정 목록 한 쪽(최신순, 쪽당 20건). {@code title}은 현재 개정의 제목이다. */
public record PublicSitePageRevisions(String slug, String title, List<PublicSitePageRevisionItem> items, int page,
                                      boolean hasPrevious, boolean hasNext) {
}
