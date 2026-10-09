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
    private String boardName;
    private String title;
    private String content;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;

    @Builder.Default
    private List<PublicPostAttachment> attachments = List.of();

    public static PublicPostDetail from(Board board, Post post, List<PostAttachment> attachments) {
        return PublicPostDetail.builder()
                .id(post.getId())
                .boardId(board.getId())
                .boardName(board.getName())
                .title(post.getTitle())
                .content(HtmlContentSanitizer.sanitize(post.getContent()).html())
                .createDate(post.getCreateDate())
                .updateDate(post.getUpdateDate())
                .attachments(attachments.stream().map(PublicPostAttachment::from).toList())
                .build();
    }
}
