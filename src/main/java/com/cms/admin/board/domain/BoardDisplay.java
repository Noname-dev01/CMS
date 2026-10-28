package com.cms.admin.board.domain;

/**
 * 게시판의 공개 화면 표시·쓰기 설정 묶음(PLAN-board-definition.md PR 1·3). {@link Board#update}에 최종값으로 넘긴다 — 문자열 null은 "없음"이다.
 * {@code pinMaxCount}는 공개 목록 1페이지 상단에 고정할 수 있는 글 수 상한(0 = 고정 끔)이다.
 */
public record BoardDisplay(String description, int listPageSize, String authorLabel, boolean showViewCount,
                           int newBadgeDays, boolean writableYn, int pinMaxCount) {
}
