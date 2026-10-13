package com.cms.admin.faq.dto.request;

import com.cms.admin.site.SiteScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FAQ 카테고리 <b>수정</b>(전체 교체) 요청. 등록({@link FaqCategoryRequest})과 달리 {@code siteScope}와 {@code version}이 필수다 —
 * 전체 교체에서 누락이 조용히 "공통 공개"가 되지 않게 하고, 낡은 화면이 다른 관리자가 바꾼 표시 대상을 옛 값으로 되돌리지 못하게 한다
 * (PLAN-site-menu.md PR ③ S6·S6b).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "FAQ 카테고리 수정 요청 (전체 교체)")
public class FaqCategoryUpdateRequest {

    @NotBlank
    @Size(max = 50)
    @Schema(description = "카테고리 이름", example = "배송")
    private String name;

    @NotNull
    @Schema(description = "노출 여부. false면 그 안의 FAQ가 공개 화면에서 모두 숨는다")
    private Boolean useYn;

    @NotNull
    @Pattern(regexp = SiteScope.PATTERN, message = "표시 대상은 COMMON 또는 사이트 ID여야 합니다.")
    @Schema(description = "표시 대상 — COMMON(모든 공개 사이트 공통) 또는 공개 사이트 ID. 전체 교체라 필수", example = "COMMON")
    private String siteScope;

    @NotNull
    @Positive
    @Schema(description = "편집을 시작할 때 응답으로 받은 카테고리 version. 다르면 409(낡은 화면)", example = "1")
    private Integer version;
}
