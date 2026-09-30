package com.cms.admin.menu.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "형제 메뉴 순서 재조정 요청 (같은 부모 아래 형제만 대상)")
public class MenuOrderRequest {

    @Schema(description = "부모 메뉴 번호. 최상위 메뉴들의 순서를 바꿀 때는 null", example = "3")
    private Long upMenuNo;

    @NotNull
    @Schema(description = "요청이 담은 범위. ALL=형제 전체, ACTIVE=활성 형제만(비활성 형제는 표시 순서상 제자리 유지)",
            example = "ACTIVE")
    private MenuOrderScope scope;

    @NotEmpty
    @Size(max = 1000)
    @Schema(description = "원하는 순서대로 나열한 메뉴 번호. scope가 가리키는 형제 집합과 정확히 같아야 한다")
    private List<@NotNull Long> menuNos;
}
