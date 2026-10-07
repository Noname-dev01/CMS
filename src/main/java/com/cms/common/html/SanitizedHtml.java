package com.cms.common.html;

import java.util.Set;

/**
 * {@link HtmlContentSanitizer#sanitize(String)} 결과.
 *
 * @param html       허용 목록을 통과하고 정규화된 HTML(저장·출력 공통 형식)
 * @param textLength 보이는 텍스트 길이 — 텍스트 문자 수 + 블록 종료·블록 중간 {@code br}당 1
 *                   (PLAN-html-editor.md 쟁점 5, 기존 평문의 "줄바꿈 1자"와 같은 의미)
 * @param imageIds   본문이 참조하는 본문 이미지 ID(등장 순서 유지, 중복 제거)
 * @param blank      보이는 텍스트가 공백뿐이고 이미지도 없으면 true
 */
public record SanitizedHtml(String html, int textLength, Set<Long> imageIds, boolean blank) {
}
