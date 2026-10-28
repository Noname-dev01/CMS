package com.cms.publicweb.board.dto;

import com.cms.admin.board.domain.Post;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 공개 목록 항목. admin {@code PostSummaryResponse}를 재사용하지 않는다 — 재사용하면 {@code authorId}(관리자 로그인 userId)와
 * {@code useYn}이 딸려 들어와 비인증 사용자에게 유효한 로그인 아이디가 노출된다(공지 공개 DTO와 같은 이유).
 *
 * <p>{@code viewCount}는 게시판이 조회수 표시를 켠 경우에만 채운다 — 템플릿이 아니라 서비스가 거른다(꺼진 게시판은 응답 모델에 값이 없다).
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PublicPostSummary {

    private Long id;
    private String title;
    private LocalDateTime createDate;

    /** 조회수 표시를 켠 게시판의 글만 값이 있다. null = 표시하지 않음. */
    private Long viewCount;

    /** "새 글" 배지 여부 — 게시판의 배지 기간 안일 때. 템플릿에서 {@code new}가 예약어와 겹치지 않게 이 이름을 쓴다. */
    private boolean newBadge;

    public static PublicPostSummary from(Post post, boolean showViewCount, boolean newBadge) {
        return PublicPostSummary.builder()
                .id(post.getId())
                .title(post.getTitle())
                .createDate(post.getCreateDate())
                .viewCount(showViewCount ? post.getViewCount() : null)
                .newBadge(newBadge)
                .build();
    }
}
