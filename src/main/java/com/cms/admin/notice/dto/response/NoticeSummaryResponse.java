package com.cms.admin.notice.dto.response;

import com.cms.admin.notice.domain.Notice;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 목록 조회 전용 응답 — 본문(content)을 제외한다.
 * 목록에서 화면에 쓰지 않는 본문까지 JSON으로 직렬화·전송하는 낭비를 막는다
 * (설계 결정 8). QueryDSL selectFrom(notice) 자체는 content까지 DB에서
 * 읽으므로 DB 조회 절감 효과는 없다 — 이 결정의 효과는 JSON 직렬화·전송량
 * 절감에 한정된다.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "공지사항 목록 항목 응답 (본문 제외)")
public class NoticeSummaryResponse {

    private Long id;
    private String title;
    private Boolean useYn;
    private String authorId;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;

    public static NoticeSummaryResponse from(Notice notice) {
        return NoticeSummaryResponse.builder()
                .id(notice.getId())
                .title(notice.getTitle())
                .useYn(notice.getUseYn())
                .authorId(notice.getAuthorId())
                .createDate(notice.getCreateDate())
                .updateDate(notice.getUpdateDate())
                .build();
    }
}
