package com.cms.publicweb.notice.dto;

import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.domain.NoticeAttachment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 공개 상세. admin {@code NoticeResponse}를 재사용하지 않는다 — 재사용하면 {@code authorId}·
 * {@code useYn}이 딸려 들어온다(PLAN-public-notice.md 결정 4).
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PublicNoticeDetail {

    private Long id;
    private String title;
    private String content;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;

    @Builder.Default
    private List<PublicNoticeAttachment> attachments = List.of();

    public static PublicNoticeDetail from(Notice notice, List<NoticeAttachment> attachments) {
        return PublicNoticeDetail.builder()
                .id(notice.getId())
                .title(notice.getTitle())
                .content(notice.getContent())
                .createDate(notice.getCreateDate())
                .updateDate(notice.getUpdateDate())
                .attachments(attachments.stream().map(PublicNoticeAttachment::from).toList())
                .build();
    }
}
