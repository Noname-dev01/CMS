package com.cms.admin.board.repository;

import java.time.LocalDateTime;

/** 통합 검색용 게시글 한 줄 — 게시판 이름을 함께 읽는다(게시글에 연관관계 매핑이 없어 서비스가 따로 조회하지 않게). 본문은 담지 않는다. */
public record PostSearchRow(Long id, Long boardId, String boardName, String title, Boolean useYn, LocalDateTime createDate) {
}
