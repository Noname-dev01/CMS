package com.cms.admin.board.dto.response;

import com.cms.admin.board.domain.Post;
import com.cms.common.display.DisplayPeriod;
import com.cms.common.display.DisplayStatus;
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
    @Schema(description = "노출 기간 시작(포함). null이면 시작 제한 없음", nullable = true)
    private LocalDateTime publishStart;
    @Schema(description = "노출 기간 종료(미포함). null이면 종료 제한 없음", nullable = true)
    private LocalDateTime publishEnd;
    @Schema(description = "노출 상태 — HIDDEN > SCHEDULED > EXPIRED > ACTIVE. 서버 Clock 기준")
    private DisplayStatus status;
    @Schema(description = "공개 조회 누적 횟수(읽기 전용, 최선 노력 지표)")
    private long viewCount;

    /** @param now 앱 Clock 기준 현재 시각 — 노출 상태 계산용(서비스가 요청당 1회 산출해 전달) */
    public static PostSummaryResponse from(Post post, LocalDateTime now) {
        return PostSummaryResponse.builder()
                .id(post.getId())
                .boardId(post.getBoardId())
                .title(post.getTitle())
                .useYn(post.getUseYn())
                .authorId(post.getAuthorId())
                .createDate(post.getCreateDate())
                .updateDate(post.getUpdateDate())
                .publishStart(post.getPublishStart())
                .publishEnd(post.getPublishEnd())
                .status(DisplayPeriod.statusOf(Boolean.TRUE.equals(post.getUseYn()), post.getPublishStart(), post.getPublishEnd(), now))
                .viewCount(post.getViewCount())
                .build();
    }
}
