package com.cms.admin.site.dto.response;

import com.cms.admin.site.Site;
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
@Schema(description = "사이트 응답")
public class SiteResponse {

    private Long id;
    private String siteKey;
    private String siteName;
    private String siteType;
    private Boolean allowExternalLink;
    private Boolean useYn;
    @Schema(description = "시드 사이트 여부 — 삭제·비활성화 불가(서버 판정이 최종)")
    private Boolean seed;
    @Schema(description = "기본 공개 사이트 여부 — 접두어 없는 /가 이 사이트로 이동한다")
    private Boolean defaultSite;
    @Schema(description = "이 사이트에 속한 메뉴 수(비활성 포함) — 0일 때만 삭제 가능(서버 판정이 최종)")
    private Long menuCount;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;

    public static SiteResponse from(Site site, long menuCount) {
        return SiteResponse.builder()
                .id(site.getId())
                .siteKey(site.getSiteKey())
                .siteName(site.getSiteName())
                .siteType(site.getSiteType().name())
                .allowExternalLink(site.permitsExternalLink())
                .useYn(site.getUseYn())
                .seed(site.isSeed())
                .defaultSite(site.isDefaultYn())
                .menuCount(menuCount)
                .createDate(site.getCreateDate())
                .updateDate(site.getUpdateDate())
                .build();
    }
}
