package com.cms.publicweb.home.dto;

import com.cms.admin.board.domain.BoardSlug;
import com.cms.admin.board.repository.PublishedPostRow;

import java.time.LocalDateTime;

/**
 * 공개 메인의 최신 글 한 줄 — 제목·날짜·게시판 이름만(본문·작성자·노출 여부는 담지 않는다).
 * {@code coverImageId}는 대표 이미지(없으면 null)이고 "새 글" 섹션만 썸네일로 쓴다 — 대표는 그 글이 참조하는 이미지라 공개 판정이 글과 같다.
 */
public record PublicHomePost(Long id, Long boardId, String boardSlug, String boardName, String title, LocalDateTime createDate, Long coverImageId) {

    public static PublicHomePost from(PublishedPostRow row) {
        return new PublicHomePost(row.id(), row.boardId(), BoardSlug.effective(row.boardId(), row.boardSlug()), row.boardName(), row.title(), row.createDate(), row.coverImageId());
    }
}
