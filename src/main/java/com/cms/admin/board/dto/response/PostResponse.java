package com.cms.admin.board.dto.response;

import com.cms.admin.board.domain.Post;
import com.cms.common.display.DisplayPeriod;
import com.cms.common.display.DisplayStatus;
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
    @Schema(description = "공개 목록 1페이지 상단 고정 여부")
    private Boolean pinned;
    private String authorId;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;
    @Schema(description = "최종 수정자 userId — 마지막으로 생성·수정·첨부 변경을 한 관리자. V5 이전에 만들어져 이후 손대지 않은 글은 null", nullable = true)
    private String updaterId;
    @Schema(description = "노출 기간 시작(포함). null이면 시작 제한 없음", nullable = true)
    private LocalDateTime publishStart;
    @Schema(description = "노출 기간 종료(미포함). null이면 종료 제한 없음", nullable = true)
    private LocalDateTime publishEnd;
    @Schema(description = "노출 상태 — HIDDEN(노출 여부 꺼짐) > SCHEDULED(시작 전) > EXPIRED(종료됨) > ACTIVE(노출 중). 서버 Clock 기준")
    private DisplayStatus status;
    @Schema(description = "공개 조회 누적 횟수(읽기 전용, 최선 노력 지표)")
    private long viewCount;
    @Schema(description = "대표 이미지 ID — 본문이 참조하는 이미지 중 하나. null이면 대표 없음", nullable = true)
    private Long coverImageId;

    /** @param now 앱 Clock 기준 현재 시각 — 노출 상태 계산용(서비스가 요청당 1회 산출해 전달) */
    public static PostResponse from(Post post, LocalDateTime now) {
        return PostResponse.builder()
                .id(post.getId())
                .boardId(post.getBoardId())
                .title(post.getTitle())
                .content(HtmlContentSanitizer.sanitize(post.getContent()).html())
                .useYn(post.getUseYn())
                .pinned(post.getPinned())
                .authorId(post.getAuthorId())
                .createDate(post.getCreateDate())
                .updateDate(post.getUpdateDate())
                .updaterId(post.getUpdaterId())
                .publishStart(post.getPublishStart())
                .publishEnd(post.getPublishEnd())
                .status(DisplayPeriod.statusOf(Boolean.TRUE.equals(post.getUseYn()), post.getPublishStart(), post.getPublishEnd(), now))
                .viewCount(post.getViewCount())
                .coverImageId(post.getCoverImageId())
                .build();
    }
}
