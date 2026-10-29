package com.cms.admin.board.domain;

/**
 * 게시판의 공개 화면 표시·쓰기 설정 묶음(PLAN-board-definition.md PR 1·3·6). {@link Board#update}에 최종값으로 넘긴다 — 문자열 null은 "없음"이다.
 * {@code pinMaxCount}는 공개 목록 1페이지 상단에 고정할 수 있는 글 수 상한(0 = 고정 끔)이다.
 * {@code boardType}은 <b>저장 원본 문자열</b>이다(해석된 {@link BoardType}이 아니다) — 유형을 언급하지 않은 수정이 알 수 없는 저장값을 {@code LIST}로
 * 바꿔 쓰지 않게 한다(PR 6 리뷰 R1-1).
 */
public record BoardDisplay(String description, int listPageSize, String authorLabel, boolean showViewCount,
                           int newBadgeDays, boolean writableYn, int pinMaxCount, String boardType) {
}
