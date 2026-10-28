package com.cms.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 공개 경로 id 해석(PLAN-board-definition.md PR 4a D3) — 옛 공개 컨트롤러의 {@code Long.parseLong}과 비음수 구간에서 같은 의미. */
class PathIdsTest {

    @ParameterizedTest
    @ValueSource(strings = {"12", "+12", "0012", "１２", "١٢", "1２", "0", "9223372036854775807"})
    @DisplayName("Long.parseLong이 읽는 비음수 표기는 같은 값으로 읽는다(전각·아라비아 인도 숫자·혼합 포함)")
    void sameAsParseLong(String segment) {
        assertThat(PathIds.parse(segment)).isEqualTo(Long.parseLong(segment));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-0012", "9223372036854775808", "", "1a", "a", " 12", "12 ", "1.0", "0x1"})
    @DisplayName("음수·long 범위 초과·숫자가 아닌 값은 null")
    void rejects(String segment) {
        assertThat(PathIds.parse(segment)).isNull();
    }

    @Test
    @DisplayName("null은 null")
    void rejectsNull() {
        assertThat(PathIds.parse(null)).isNull();
    }
}
