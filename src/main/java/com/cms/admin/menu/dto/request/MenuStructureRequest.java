package com.cms.admin.menu.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 메뉴 구조 일괄 반영 요청. 화면에서 드래그로 만든 초안(최종 트리 모양)을 한 번에 보낸다.
 *
 * <p>{@code menus}는 <b>전체 메뉴(비활성 포함)</b>를 담고, 같은 {@code upMenuNo} 안의 <b>배열 순서가 새
 * {@code ord}(0..n-1)</b>가 된다. 각 항목의 {@code baseUpMenuNo}·{@code baseOrd}는 초안을 만든 시점(트리 조회
 * 시점)의 값으로, 서버가 현재 값과 비교해 낡은 초안을 409로 거부하는 데 쓴다.
 *
 * <p>항목의 네 필드({@code menuNo}·{@code baseUpMenuNo}·{@code baseOrd}·{@code upMenuNo})를 모두
 * {@link JsonNode}로 받는 이유(메뉴 부모 이동 요청과 같다): 자바 {@code Long}으로 받으면 필드가 아예 없는 본문이
 * 명시적 {@code null}(최상위/값 없음)과 구분되지 않고, Jackson 기본 강제 변환이 {@code ""}·{@code "null"} 문자열을
 * {@code null}로, {@code "5"}를 {@code 5}로, {@code 10.9}를 {@code 10}으로 바꿔 잘못된 입력이 최상위 이동이나 다른
 * 부모 이동으로 둔갑한다. {@code menuNo}도 예외가 아니다 — {@code 1.9}가 {@code 1}로 잘려 엉뚱한 기존 메뉴를 옮기는
 * 것이 코드 리뷰에서 재현됐다. 원본 토큰을 검사해 "필드 없음"과 정수·명시적 null 이외의 값을 모두 거부한다.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "메뉴 구조 일괄 반영 요청 — 전체 메뉴의 최종 구조(부모·순서)")
public class MenuStructureRequest {

    @NotEmpty
    @Schema(description = "전체 메뉴(비활성 포함). 같은 upMenuNo 안의 배열 순서가 새 순서가 된다")
    private List<@NotNull @Valid Item> menus;

    @Getter
    @Setter
    @NoArgsConstructor
    @Schema(description = "메뉴 한 건의 기준값(base)과 새 부모")
    public static class Item {

        /** 필수 정수 — null·소수·문자열·불리언은 거부(자바 null = 본문에 필드 없음). */
        @NotNull
        @Schema(description = "메뉴 번호(정수만 허용)", type = "integer", format = "int64", example = "5")
        private JsonNode menuNo;

        /** 자바 null = 본문에 필드 없음, NullNode = 명시적 JSON null(최상위). */
        @Schema(description = "초안을 만든 시점의 부모 메뉴 번호(최상위면 명시적 null)", type = "integer", format = "int64",
                nullable = true, example = "3")
        private JsonNode baseUpMenuNo;

        @Schema(description = "초안을 만든 시점의 ord(레거시 행은 명시적 null)", type = "integer", format = "int32",
                nullable = true, example = "0")
        private JsonNode baseOrd;

        @Schema(description = "새 부모 메뉴 번호(최상위면 명시적 null)", type = "integer", format = "int64",
                nullable = true, example = "1")
        private JsonNode upMenuNo;

        /** 테스트·내부 호출용 팩토리. null은 각각 최상위/값 없음. */
        public static Item of(Long menuNo, Long baseUpMenuNo, Integer baseOrd, Long upMenuNo) {
            Item item = new Item();
            item.menuNo = menuNo == null ? null : JsonNodeFactory.instance.numberNode(menuNo);
            item.baseUpMenuNo = longNode(baseUpMenuNo);
            item.baseOrd = baseOrd == null
                    ? JsonNodeFactory.instance.nullNode()
                    : JsonNodeFactory.instance.numberNode(baseOrd);
            item.upMenuNo = longNode(upMenuNo);
            return item;
        }

        private static JsonNode longNode(Long value) {
            return value == null
                    ? JsonNodeFactory.instance.nullNode()
                    : JsonNodeFactory.instance.numberNode(value);
        }

        @JsonIgnore
        @AssertTrue(message = "menuNo는 필수이며 정수만, baseUpMenuNo·upMenuNo는 필수이며 정수 또는 null만, baseOrd는 필수이며 정수(int 범위) 또는 null만 허용됩니다.")
        public boolean isTokensValid() {
            return isLong(menuNo) && isLongOrNull(baseUpMenuNo) && isLongOrNull(upMenuNo) && isIntOrNull(baseOrd);
        }

        private static boolean isLong(JsonNode node) {
            return node != null && node.isIntegralNumber() && node.canConvertToLong();
        }

        private static boolean isLongOrNull(JsonNode node) {
            if (node == null) {
                return false;
            }
            return node.isNull() || (node.isIntegralNumber() && node.canConvertToLong());
        }

        private static boolean isIntOrNull(JsonNode node) {
            if (node == null) {
                return false;
            }
            return node.isNull() || (node.isIntegralNumber() && node.canConvertToInt());
        }

        /** 검증을 통과한 요청에서 메뉴 번호를 꺼낸다. */
        @JsonIgnore
        public Long resolvedMenuNo() {
            return menuNo.asLong();
        }

        /** 검증을 통과한 요청에서 새 부모 번호를 꺼낸다. 최상위면 null. */
        @JsonIgnore
        public Long resolvedUpMenuNo() {
            return upMenuNo == null || upMenuNo.isNull() ? null : upMenuNo.asLong();
        }

        @JsonIgnore
        public Long resolvedBaseUpMenuNo() {
            return baseUpMenuNo == null || baseUpMenuNo.isNull() ? null : baseUpMenuNo.asLong();
        }

        @JsonIgnore
        public Integer resolvedBaseOrd() {
            return baseOrd == null || baseOrd.isNull() ? null : baseOrd.asInt();
        }
    }
}
