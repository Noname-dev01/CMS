package com.cms.admin.faq.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import com.cms.admin.site.SiteScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** FAQ 카테고리 <b>등록</b> 요청. 순서는 요청에 없다 — 등록은 맨 끝, 변경은 순서 반영 API만 한다. 수정은 {@link FaqCategoryUpdateRequest}. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "FAQ 카테고리 등록 요청")
public class FaqCategoryRequest {

    @NotBlank
    @Size(max = 50)
    @Schema(description = "카테고리 이름", example = "배송")
    private String name;

    @NotNull
    @Schema(description = "노출 여부. false면 그 안의 FAQ가 공개 화면에서 모두 숨는다")
    private Boolean useYn;

    @Pattern(regexp = SiteScope.PATTERN, message = "표시 대상은 COMMON 또는 사이트 ID여야 합니다.")
    @Schema(description = "표시 대상 — COMMON(모든 공개 사이트 공통) 또는 공개 사이트 ID. 누락 시 COMMON", example = "COMMON")
    private String siteScope;
}
