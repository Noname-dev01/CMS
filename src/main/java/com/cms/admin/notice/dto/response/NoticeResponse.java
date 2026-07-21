package com.cms.admin.notice.dto.response;

import com.cms.admin.notice.domain.Notice;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 상세 조회·생성·수정·삭제 응답 — 본문(content)을 포함한다. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "공지사항 상세 응답")
public class NoticeResponse {

    private Long id;
    private String title;
    private String content;
    private Boolean useYn;
    private String authorId;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;

    public static NoticeResponse from(Notice notice) {
        return NoticeResponse.builder()
                .id(notice.getId())
                .title(notice.getTitle())
                .content(notice.getContent())
                .useYn(notice.getUseYn())
                .authorId(notice.getAuthorId())
                .createDate(notice.getCreateDate())
                .updateDate(notice.getUpdateDate())
                .build();
    }
}
