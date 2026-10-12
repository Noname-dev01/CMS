package com.cms.admin.faq.repository;

/** 공개 페이지 조회 행(PLAN-faq.md 쟁점 8) — 공개 조건을 통과한 카테고리·FAQ만 담긴다. */
public record PublicFaqRow(Long categoryId, String categoryName, Long faqId, String question, String answerHtml) {
}
