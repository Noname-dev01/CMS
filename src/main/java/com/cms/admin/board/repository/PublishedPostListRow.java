package com.cms.admin.board.repository;

import java.time.LocalDateTime;

/**
 * 공개 목록 한 줄 — 본문(MEDIUMTEXT, 최대 200KB)을 담지 않는다. 게시판 유형과 무관하게 목록 쿼리가 본문을 읽지 않게 하려는 것이고
 * (PLAN-public-list-projection.md D1), 쿼리가 실제로 본문을 선택하지 않음은 생성 SQL 시험이 고정한다. 웹진형 요약의 입력은
 * {@link PostRepository#findContentHeads}가 따로 읽는다. {@code publishStart}는 "새 글" 배지 판정에 쓰인다.
 */
public record PublishedPostListRow(Long id, String title, LocalDateTime createDate, Long viewCount, Long coverImageId, LocalDateTime publishStart) {
}
