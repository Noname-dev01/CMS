package com.cms.admin.board.dto.response;

import com.cms.admin.board.domain.Post;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 휴지통 목록 항목(PLAN-post-trash.md D2) — 본문은 싣지 않는다. 삭제 시각·삭제자는 전용 컬럼이 없어 삭제된 글의 마지막 수정 시각·수정자를
 * 쓴다(삭제 뒤에는 행을 다시 쓰지 않는 불변식에 기댄다 — 삭제된 글의 행을 고치는 새 쓰기 경로를 만들면 이 표시가 깨진다).
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "휴지통(삭제된 게시글) 목록 항목 응답 (본문 제외)")
public class DeletedPostSummaryResponse {

    private Long id;
    private Long boardId;
    private String title;
    private String authorId;
    private LocalDateTime createDate;
    @Schema(description = "삭제 시점의 최종 수정 시각(= 삭제 시각)")
    private LocalDateTime deletedAt;
    @Schema(description = "삭제 시점의 최종 수정자 userId. V5 이전 삭제 글은 null이고, V5 이전 앱으로 롤백한 기간에 삭제된 글은 직전 수정자가 보일 수 있다(감사 로그 POST_DELETE가 원본)",
            nullable = true)
    private String deletedBy;

    public static DeletedPostSummaryResponse from(Post post) {
        return DeletedPostSummaryResponse.builder()
                .id(post.getId())
                .boardId(post.getBoardId())
                .title(post.getTitle())
                .authorId(post.getAuthorId())
                .createDate(post.getCreateDate())
                .deletedAt(post.getUpdateDate())
                .deletedBy(post.getUpdaterId())
                .build();
    }
}
