package com.cms.admin.menu.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
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
@Schema(description = "메뉴 부모 이동 결과")
public class MenuMoveResponse {

    @Schema(description = "이동한 메뉴 번호")
    private Long menuNo;

    /**
     * 최상위 결과에서도 키가 생략되지 않도록 ALWAYS — 전역 {@code default-property-inclusion: non_null}이
     * null 필드의 키를 빼므로 명시한다("최상위"라는 사실을 값 null로 계약한다).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "이동 후 부모 메뉴 번호. 최상위이면 null(키는 항상 존재)", nullable = true)
    private Long upMenuNo;

    @Schema(description = "이동 후 정렬 순서(새 부모 아래 맨 끝)")
    private Integer ord;

    @Schema(description = "이동은 성공했지만 알려야 할 사항(예: 관리자 전용 부모 아래 공용 메뉴). 없으면 빈 배열")
    private List<String> warnings;
}
