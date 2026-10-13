package com.cms.admin.page.dto.request;

import com.cms.admin.member.dto.request.validation.MaxUtf8Bytes;
import com.cms.admin.page.domain.SiteSlug;
import com.cms.admin.site.SiteScope;
import com.cms.common.html.ContentBodyPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "정적 페이지 등록 요청")
public class SitePageCreateRequest {

    @NotBlank
    @Size(min = SiteSlug.MIN_LENGTH, max = SiteSlug.MAX_LENGTH)
    @Pattern(regexp = SiteSlug.PATTERN, message = "슬러그는 소문자·숫자·하이픈만 사용할 수 있습니다(처음·끝·연속 하이픈 불가).")
    @Schema(description = "공개 주소의 슬러그(/pages/{slug}). 생성 후 바꿀 수 없다", example = "privacy-policy")
    private String slug;

    @NotBlank
    @Size(max = 200)
    @Schema(description = "제목", example = "개인정보처리방침")
    private String title;

    // 원문 상한은 sanitizer가 원문을 파싱하기 전에 거른다(PLAN-static-page.md R3-1)
    @NotBlank
    @MaxUtf8Bytes(value = ContentBodyPolicy.PAGE_MAX_RAW_BYTES, message = "본문이 너무 깁니다.")
    @Schema(description = "본문 HTML(편집기 출력). 이미지는 지원하지 않는다 — 서버가 허용 목록으로 정리하며 img는 제거한다. 보이는 글자 50,000자 이하")
    private String content;

    @Schema(description = "본문 형식 표식. \"HTML\" 필수", example = "HTML")
    private String contentFormat;

    @Schema(description = "노출 여부. 누락 시 true", example = "true")
    private Boolean useYn;

    @Schema(description = "공개 메인 푸터에 링크할지. 누락 시 false", example = "false")
    private Boolean footerYn;

    @Pattern(regexp = SiteScope.PATTERN, message = "표시 대상은 COMMON 또는 사이트 ID여야 합니다.")
    @Schema(description = "표시 대상 — COMMON(모든 공개 사이트 공통) 또는 공개 사이트 ID. 누락 시 COMMON", example = "COMMON")
    private String siteScope;
}
