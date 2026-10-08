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
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PublicPostSummary {

    private Long id;
    private String title;
    private LocalDateTime createDate;

    public static PublicPostSummary from(Post post) {
        return PublicPostSummary.builder()
                .id(post.getId())
                .title(post.getTitle())
                .createDate(post.getCreateDate())
                .build();
    }
}
