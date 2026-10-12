package com.cms.publicweb.faq.dto;

/**
 * 공개 FAQ 한 건. {@code answerHtml}은 출력 시 한 번 더 이미지 불허 규칙으로 정리한 HTML이다 — 템플릿에서 이스케이프 없이 출력하는 유일한 값이다.
 */
public record PublicFaqItem(Long id, String question, String answerHtml) {
}
