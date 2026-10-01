package com.cms.admin.menu.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "메뉴 구조 일괄 반영 결과")
public class MenuStructureResponse {

    @Schema(description = "실제로 부모 또는 순서가 바뀐 메뉴 수(무변경 요청이면 0)", example = "3")
    private int changed;
}
