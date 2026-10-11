package com.cms.admin.page.dto.response;

import com.cms.admin.page.repository.SitePageSummaryRow;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "정적 페이지 목록 항목")
public class SitePageSummaryResponse {

    private Long id;
    private String slug;
    private String title;
    private Integer currentRevisionNo;
    private Integer version;
    private Boolean useYn;
    private Boolean footerYn;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;

    public static SitePageSummaryResponse from(SitePageSummaryRow row) {
        return SitePageSummaryResponse.builder()
                .id(row.id())
                .slug(row.slug())
                .title(row.title())
                .currentRevisionNo(row.currentRevisionNo())
                .version(row.version())
                .useYn(row.useYn())
                .footerYn(row.footerYn())
                .createDate(row.createDate())
                .updateDate(row.updateDate())
                .build();
    }
}
