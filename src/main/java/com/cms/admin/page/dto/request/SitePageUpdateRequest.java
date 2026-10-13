package com.cms.admin.page.dto.request;

import com.cms.admin.member.dto.request.validation.MaxUtf8Bytes;
import com.cms.admin.site.SiteScope;
import com.cms.common.html.ContentBodyPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 정적 페이지 전체 교체 요청(PUT). 슬러그는 바꿀 수 없어 요청에 없다. 노출·푸터·표시 대상을 누락하면 낡은 화면의 기본값이 값을 덮으므로 필수다. */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "정적 페이지 수정 요청(전체 교체)")
public class SitePageUpdateRequest {

    @NotBlank
    @Size(max = 200)
    @Schema(description = "제목")
    private String title;

    @NotBlank
    @MaxUtf8Bytes(value = ContentBodyPolicy.PAGE_MAX_RAW_BYTES, message = "본문이 너무 깁니다.")
    @Schema(description = "본문 HTML(편집기 출력). 이미지는 제거된다")
    private String content;

    @Schema(description = "본문 형식 표식. \"HTML\" 필수", example = "HTML")
    private String contentFormat;

    @NotNull
    @Schema(description = "노출 여부")
    private Boolean useYn;

    @NotNull
    @Schema(description = "공개 메인 푸터 링크 여부")
    private Boolean footerYn;

    @NotNull
    @Pattern(regexp = SiteScope.PATTERN, message = "표시 대상은 COMMON 또는 사이트 ID여야 합니다.")
    @Schema(description = "표시 대상 — COMMON(모든 공개 사이트 공통) 또는 공개 사이트 ID. 전체 교체라 필수(누락이 공통 공개가 되지 않게 400)", example = "COMMON")
    private String siteScope;

    @NotNull
    @Min(1)
    @Schema(description = "화면이 가진 페이지 변경 버전. 서버 값과 다르면 409(그 사이 다른 관리자가 저장함)", example = "3")
    private Integer version;
}
