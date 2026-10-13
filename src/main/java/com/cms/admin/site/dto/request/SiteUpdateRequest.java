package com.cms.admin.site.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 사이트 수정 요청. null 필드는 기존값 유지. 키·종류는 바뀌지 않는다(요청에 와도 무시된다). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "사이트 수정 요청 — null 필드는 기존값 유지")
public class SiteUpdateRequest {

    @Size(max = 100)
    @Schema(description = "사이트 이름. null이면 기존값 유지", example = "소식 사이트")
    private String siteName;

    @Schema(description = "메뉴에 외부 http(s) 링크를 허용할지. true→false(강화)는 외부 링크 메뉴가 있으면 409, 관리자 사이트는 true 불가")
    private Boolean allowExternalLink;

    @Schema(description = "사용 여부. 시드 사이트(관리자·기본 공개)와 현재 기본 사이트는 비활성화할 수 없다")
    private Boolean useYn;

    @Schema(description = "true면 이 사이트를 기본 공개 사이트로 지정한다(접두어 없는 /가 이동할 곳). 활성 공개 사이트만 가능. false는 400 — 기본 사이트는 다른 사이트를 지정해야만 바뀐다. null이면 유지")
    private Boolean defaultSite;
}
