package com.cms.admin.banner.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** 배너 표시 순서 일괄 저장 요청 — 화면이 만든 <b>전체 배너의 최종 순서</b>(앞이 먼저 노출). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "배너 순서 저장 요청")
public class BannerOrderRequest {

    @NotNull
    @Size(max = 10)
    @Schema(description = "전체 배너 ID를 표시 순서대로. 현재 배너 집합과 다르면 409", example = "[3,1,2]")
    private List<@NotNull Long> ids;
}
