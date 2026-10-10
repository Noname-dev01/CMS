package com.cms.publicweb.home.dto;

import com.cms.admin.board.repository.PublishedPostRow;

import java.time.LocalDateTime;

/** 공개 메인의 최신 글 한 줄 — 제목·날짜·게시판 이름만(본문·작성자·노출 여부는 담지 않는다). */
public record PublicHomePost(Long id, Long boardId, String boardName, String title, LocalDateTime createDate) {

    public static PublicHomePost from(PublishedPostRow row) {
        return new PublicHomePost(row.id(), row.boardId(), row.boardName(), row.title(), row.createDate());
    }
}
