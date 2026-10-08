package com.cms.admin.board.dto.response;

import com.cms.admin.board.domain.Post;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 목록 조회 전용 응답 — 본문(content)을 제외해 JSON 직렬화·전송량을 줄인다(공지 목록과 같은 이유). */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "게시글 목록 항목 응답 (본문 제외)")
public class PostSummaryResponse {

    private Long id;
    private Long boardId;
    private String title;
    private Boolean useYn;
    private String authorId;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;

    public static PostSummaryResponse from(Post post) {
        return PostSummaryResponse.builder()
                .id(post.getId())
                .boardId(post.getBoardId())
                .title(post.getTitle())
                .useYn(post.getUseYn())
                .authorId(post.getAuthorId())
                .createDate(post.getCreateDate())
                .updateDate(post.getUpdateDate())
                .build();
    }
}
