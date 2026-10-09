package com.cms.admin.board.dto.response;

import com.cms.admin.board.domain.Post;
import com.cms.common.html.HtmlContentSanitizer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 상세 조회·생성·수정·삭제 응답 — 본문(content)을 포함한다. 본문은 출력 시에도 sanitize한다(저장 시 + 출력 시 이중 —
 * PLAN-html-editor.md 쟁점 4). 관리 상세 화면이 이 값을 innerHTML로 렌더링하므로 정리되지 않은 값을 담으면 안 된다.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "게시글 상세 응답")
public class PostResponse {

    private Long id;
    private Long boardId;
    private String title;
    private String content;
    private Boolean useYn;
    private String authorId;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;

    public static PostResponse from(Post post) {
        return PostResponse.builder()
                .id(post.getId())
                .boardId(post.getBoardId())
                .title(post.getTitle())
                .content(HtmlContentSanitizer.sanitize(post.getContent()).html())
                .useYn(post.getUseYn())
                .authorId(post.getAuthorId())
                .createDate(post.getCreateDate())
                .updateDate(post.getUpdateDate())
                .build();
    }
}
