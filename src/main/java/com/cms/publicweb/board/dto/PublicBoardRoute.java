package com.cms.publicweb.board.dto;

/**
 * 공개 경로의 게시판 세그먼트 해석 결과(PLAN-board-definition.md "PR 4a 상세 설계" D4). {@link Found}는 열 게시판 id,
 * {@link Moved}는 슬러그를 바꾼 게시판의 옛 기본 슬러그라 새 슬러그로 301, {@link NotFound}는 숫자 id·형식 위반·비공개·삭제·다른 사이트를
 * 모두 같은 404로 흡수한다.
 */
public sealed interface PublicBoardRoute {

    record Found(Long boardId) implements PublicBoardRoute {
    }

    record Moved(String slug) implements PublicBoardRoute {
    }

    record NotFound() implements PublicBoardRoute {
    }
}
