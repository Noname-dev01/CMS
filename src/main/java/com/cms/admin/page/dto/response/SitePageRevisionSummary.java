package com.cms.admin.page.dto.response;

import com.cms.admin.page.repository.SitePageRevisionSummaryRow;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "정적 페이지 개정 요약(본문 없음)")
public class SitePageRevisionSummary {

    private Integer revisionNo;
    private String title;
    private LocalDateTime createDate;

    public static SitePageRevisionSummary from(SitePageRevisionSummaryRow row) {
        return new SitePageRevisionSummary(row.revisionNo(), row.title(), row.createDate());
    }
}
