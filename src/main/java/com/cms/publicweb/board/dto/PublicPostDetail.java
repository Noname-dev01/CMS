package com.cms.publicweb.board.dto;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.domain.Post;
import com.cms.admin.board.domain.PostAttachment;
import com.cms.common.html.HtmlContentSanitizer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** 공개 상세. 본문은 출력 시에도 sanitize한 HTML이다(저장 시 + 출력 시 이중). 작성자·노출 여부는 담지 않는다. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PublicPostDetail {

    private Long id;
    private Long boardId;
    /** 공개 링크용 현재 슬러그(목록·첨부 링크) — 사용자 슬러그가 없으면 board-{id}. */
    private String boardSlug;
    private String boardName;
    private String title;
    private String content;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;

    /** 게시판 단위 고정 작성자 표시명 — 로그인 ID가 아니다. null = 표시 안 함. */
    private String authorLabel;

    /** 조회수 표시를 켠 게시판일 때만 값이 있다(서비스가 거른다). 이번 조회는 집계 전 값이라 포함하지 않는다. */
    private Long viewCount;

    @Builder.Default
    private List<PublicPostAttachment> attachments = List.of();

    public static PublicPostDetail from(Board board, Post post, List<PostAttachment> attachments) {
        return PublicPostDetail.builder()
                .id(post.getId())
                .boardId(board.getId())
                .boardSlug(board.publicSlug())
                .boardName(board.getName())
                .title(post.getTitle())
                .content(HtmlContentSanitizer.sanitize(post.getContent()).html())
                .createDate(post.getCreateDate())
                .updateDate(post.getUpdateDate())
                .authorLabel(board.getAuthorLabel())
                .viewCount(Boolean.TRUE.equals(board.getShowViewCount()) ? post.getViewCount() : null)
                .attachments(attachments.stream().map(PublicPostAttachment::from).toList())
                .build();
    }
}
