package com.cms.publicweb.notice.dto;

import com.cms.admin.notice.domain.Notice;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 공개 목록 항목. admin {@code NoticeSummaryResponse}를 재사용하지 않는다 — 재사용하면
 * {@code authorId}(관리자 로그인 userId)가 딸려 들어와 비인증 사용자에게 유효한 로그인
 * 아이디가 노출된다(PLAN-public-notice.md 결정 4).
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PublicNoticeSummary {

    private Long id;
    private String title;
    private LocalDateTime createDate;

    public static PublicNoticeSummary from(Notice notice) {
        return PublicNoticeSummary.builder()
                .id(notice.getId())
                .title(notice.getTitle())
                .createDate(notice.getCreateDate())
                .build();
    }
}
