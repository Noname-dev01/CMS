package com.cms.admin.board.domain;

/**
 * 게시판의 작성 정책 묶음(PLAN-board-definition.md PR 2). {@link Board#update}에 최종값으로 넘긴다.
 * {@code attachExtensions}는 정규형 문자열(소문자·중복 제거·사전순·쉼표 구분)이고 null은 전역 화이트리스트 전체다.
 */
public record BoardWritePolicy(int attachMaxCount, int attachMaxSizeMb, String attachExtensions,
                               boolean bodyImageYn, boolean publishPeriodYn) {
}
