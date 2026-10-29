package com.cms.admin.board.dto.request;

/**
 * 요청의 {@code coverImageId} 엄격 파싱(PLAN-board-definition.md PR 5 D2). 세터가 {@code Object}로 받아 이 함수를 거친다 — {@code Long} 필드로
 * 받으면 Jackson이 {@code ""}를 {@code null}(= 해제)로, {@code "12"}를 12로 바꿔 화면 버그가 대표를 조용히 지우거나 바꾸기 때문이다.
 * 허용은 JSON 정수(1 이상)와 {@code null}뿐이고, 문자열·소수·불리언·배열·객체·0·음수·{@code long} 초과는 예외다. 메시지에 입력 원문을 싣지 않는다.
 */
final class CoverImageIdInput {

    private CoverImageIdInput() {
    }

    static Long parse(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Integer || raw instanceof Long) {
            long value = ((Number) raw).longValue();
            if (value >= 1) {
                return value;
            }
        }
        throw new IllegalArgumentException("coverImageId는 1 이상의 정수 또는 null이어야 합니다.");
    }
}
