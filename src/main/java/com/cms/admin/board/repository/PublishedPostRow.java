package com.cms.admin.board.repository;

import java.time.LocalDateTime;

/** 공개 메인 최신 글 한 줄 — 게시판 이름을 함께 읽는다. 본문(MEDIUMTEXT)·작성자는 담지 않는다. */
public record PublishedPostRow(Long id, Long boardId, String boardName, String title, LocalDateTime createDate) {
}
