package com.cms.admin.faq.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * FAQ 순서 반영 요청 — 화면이 만든 <b>전체 카테고리 순서</b>와 각 카테고리 안의 <b>전체 FAQ 순서</b>. {@code version}은 순서 초안을 만든 시점의
 * 구조 버전이고 현재와 다르면 409다. 카테고리 간 이동은 이 요청이 아니라 FAQ 수정으로 한다(PLAN-faq.md 쟁점 4).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "FAQ 순서 반영 요청")
public class FaqOrderRequest {

    @NotNull
    @Schema(description = "초안을 만든 시점의 구조 버전(GET /admin/api/faqs의 version)")
    private Long version;

    @NotNull
    @Size(max = 30)
    @Valid
    @Schema(description = "전체 카테고리를 표시 순서대로")
    private List<@NotNull @Valid CategoryOrder> categories;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "카테고리 하나의 순서 항목")
    public static class CategoryOrder {

        @NotNull
        @Schema(description = "카테고리 ID")
        private Long id;

        @NotNull
        @Size(max = 200)
        @Schema(description = "그 카테고리의 전체 FAQ ID를 표시 순서대로")
        private List<@NotNull Long> faqIds;
    }
}
