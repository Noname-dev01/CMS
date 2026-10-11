package com.cms.publicweb.page.dto;

import java.time.LocalDateTime;

/**
 * 공개 정적 페이지 한 개정. {@code contentHtml}은 출력 시 {@code ContentBodyPolicy.sanitizeForPageOutput}을 거친 HTML(이미지 없음)이라
 * 템플릿의 이스케이프 없는 출력은 이 값 하나뿐이다. {@code current}는 이 개정이 현재 개정인지(이전 개정이면 안내 문구를 띄운다).
 */
public record PublicSitePageView(String slug, String title, String contentHtml, int revisionNo, LocalDateTime revisionDate,
                                 boolean current) {
}
