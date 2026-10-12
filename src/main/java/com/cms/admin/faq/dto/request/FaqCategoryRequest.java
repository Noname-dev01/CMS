package com.cms.admin.faq.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** FAQ 카테고리 등록·수정(전체 교체) 요청. 순서는 요청에 없다 — 등록은 맨 끝, 변경은 순서 반영 API만 한다. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "FAQ 카테고리 등록/수정 요청")
public class FaqCategoryRequest {

    @NotBlank
    @Size(max = 50)
    @Schema(description = "카테고리 이름", example = "배송")
    private String name;

    @NotNull
    @Schema(description = "노출 여부. false면 그 안의 FAQ가 공개 화면에서 모두 숨는다")
    private Boolean useYn;
}
