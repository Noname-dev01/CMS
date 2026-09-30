package com.cms.admin.menu.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "형제 메뉴 순서 재조정 결과")
public class MenuOrderResponse {

    @Schema(description = "부모 메뉴 번호. 최상위는 null")
    private Long upMenuNo;

    @Schema(description = "적용된 형제 전체의 순서(비활성 포함, 표시 순서대로)")
    private List<Item> menus;

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Item {
        private Long menuNo;
        private Integer ord;
    }
}
