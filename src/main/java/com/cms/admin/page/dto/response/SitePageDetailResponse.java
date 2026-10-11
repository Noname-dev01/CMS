package com.cms.admin.page.dto.response;

import com.cms.admin.page.domain.SitePage;
import com.cms.admin.page.domain.SitePageRevision;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 정적 페이지 상세(현재 개정의 제목·본문 + 최근 개정 요약). 등록·수정 응답도 같은 형태다 — 화면이 최신 {@code version}을 바로 얻는다.
 * {@code auditLabel}은 감사 로그용 {@code rev=N} 라벨(시스템 생성값)이라 JSON에는 싣지 않는다.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "정적 페이지 상세")
public class SitePageDetailResponse {

    private Long id;
    private String slug;
    private String title;
    private String content;
    private Integer currentRevisionNo;
    private Integer version;
    private Boolean useYn;
    private Boolean footerYn;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;

    @Schema(description = "최근 개정 요약(최대 20건, 최신순)")
    private List<SitePageRevisionSummary> revisions;

    @Schema(description = "전체 개정 수")
    private Integer revisionCount;

    @JsonIgnore
    private String auditLabel;

    public static SitePageDetailResponse of(SitePage page, SitePageRevision current,
                                            List<SitePageRevisionSummary> revisions, String auditLabel) {
        return SitePageDetailResponse.builder()
                .id(page.getId())
                .slug(page.getSlug())
                .title(current.getTitle())
                .content(current.getContent())
                .currentRevisionNo(page.getCurrentRevisionNo())
                .version(page.getVersion())
                .useYn(page.getUseYn())
                .footerYn(page.getFooterYn())
                .createDate(page.getCreateDate())
                .updateDate(page.getUpdateDate())
                .revisions(revisions)
                .revisionCount(page.getCurrentRevisionNo())
                .auditLabel(auditLabel)
                .build();
    }
}
