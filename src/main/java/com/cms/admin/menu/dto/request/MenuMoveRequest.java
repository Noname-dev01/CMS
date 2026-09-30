package com.cms.admin.menu.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 메뉴 부모 이동 요청. {@code upMenuNo}는 새 부모 메뉴 번호이고, JSON {@code null}은 최상위 승격이다.
 *
 * <p>필드를 {@link JsonNode}로 받는 이유: 자바 {@code Long}으로 받으면 (1) 필드가 아예 없는 본문({@code {}})이
 * 명시적 {@code null}(최상위 승격)과 구분되지 않고, (2) Jackson 기본 강제 변환이 setter 호출 전에
 * {@code ""}·{@code "null"} 문자열을 {@code null}로, {@code 10.9}를 {@code 10}으로 바꿔 잘못된 입력이 실제 승격·이동이
 * 된다. 원본 토큰을 그대로 검사해 "필드 없음"과 정수·명시적 null 이외의 값을 모두 거부한다.
 * 명시 여부를 담는 별도 필드는 두지 않는다(클라이언트가 JSON으로 조작할 수 없게).
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "메뉴 부모 이동 요청 (upMenuNo는 필수 — 정수 또는 null만 허용, null은 최상위 승격)")
public class MenuMoveRequest {

    /** 자바 null = 본문에 필드 없음, NullNode = 명시적 JSON null. */
    @Schema(description = "새 부모 메뉴 번호. 최상위 메뉴로 올리려면 명시적 null", type = "integer", format = "int64",
            nullable = true, example = "3")
    private JsonNode upMenuNo;

    /** 테스트·내부 호출용 팩토리. null이면 최상위 승격. */
    public static MenuMoveRequest toParent(Long upMenuNo) {
        MenuMoveRequest request = new MenuMoveRequest();
        request.upMenuNo = upMenuNo == null
                ? JsonNodeFactory.instance.nullNode()
                : JsonNodeFactory.instance.numberNode(upMenuNo);
        return request;
    }

    @JsonIgnore
    @AssertTrue(message = "upMenuNo는 필수이며 정수 또는 null만 허용됩니다.")
    public boolean isUpMenuNoValid() {
        if (upMenuNo == null) {
            return false;
        }
        if (upMenuNo.isNull()) {
            return true;
        }
        return upMenuNo.isIntegralNumber() && upMenuNo.canConvertToLong();
    }

    /** 검증을 통과한 요청에서 새 부모 번호를 꺼낸다. 최상위 승격이면 null. */
    @JsonIgnore
    public Long resolvedUpMenuNo() {
        return upMenuNo == null || upMenuNo.isNull() ? null : upMenuNo.asLong();
    }
}
