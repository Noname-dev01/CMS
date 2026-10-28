package com.cms.common.web;

/**
 * 공개 경로의 숫자 id 세그먼트 해석 — 공개 컨트롤러·기본 게시판 슬러그 식별·숫자 게시판 경로 판정·링크 치환 마이그레이션(V10)이
 * 이 함수 하나를 쓴다(PLAN-board-definition.md "PR 4a 상세 설계" D3).
 *
 * <p>{@link Long#parseLong}과 비음수 구간에서 같은 의미다 — {@code +12}·{@code 0012}·전각 {@code １２}·아라비아 인도 숫자도
 * 같은 값으로 읽는다({@code Character.digit} 기준). 이 표기들을 ASCII 정규식으로 흉내 내면 치환·거부를 빠져나가므로 직접 판정하지 않는다.
 * 음수는 없는 id라 지금도 열리지 않으므로 null로 둔다.
 *
 * <p>V10이 이 함수를 부르므로 규칙을 바꾸면 아직 V10을 적용하지 않은 DB의 치환 결과가 달라진다 — 바꿀 때 {@code BoardSlugLinkUpgradeTest}를 함께 본다.
 */
public final class PathIds {

    private PathIds() {
    }

    /** 세그먼트가 가리키는 비음수 id, 읽을 수 없으면 null. */
    public static Long parse(String segment) {
        if (segment == null) {
            return null;
        }
        try {
            long value = Long.parseLong(segment);
            return value < 0 ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
