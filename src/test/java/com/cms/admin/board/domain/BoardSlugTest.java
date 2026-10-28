package com.cms.admin.board.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 게시판 슬러그 규칙(PLAN-board-definition.md PR 4a D1·D2). */
class BoardSlugTest {

    @ParameterizedTest
    @ValueSource(strings = {"ab", "press", "notice-2026", "board-a", "board", "a1", "1a", "boards-12", "my-board-12"})
    @DisplayName("소문자·숫자·하이픈 2~50자는 지정할 수 있다")
    void assignable(String slug) {
        assertThat(BoardSlug.isAssignable(slug)).isTrue();
    }

    @Test
    @DisplayName("길이 경계 — 50자 허용, 51자·1자 거부")
    void lengthBoundary() {
        assertThat(BoardSlug.isAssignable("a".repeat(50))).isTrue();
        assertThat(BoardSlug.isAssignable("a".repeat(51))).isFalse();
        assertThat(BoardSlug.isAssignable("a")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12", "007", "board-12", "board-0", "board-007", "News", "-ab", "ab-", "a--b", "a_b", "a b", "한글", "ab.c", ""})
    @DisplayName("숫자만·시스템 이름 공간(board-숫자)·대문자·하이픈 위치·다른 문자는 거부")
    void notAssignable(String slug) {
        assertThat(BoardSlug.isAssignable(slug)).isFalse();
    }

    @Test
    @DisplayName("기본 슬러그는 정규형 board-{id}만 id로 읽는다")
    void parseDefault() {
        assertThat(BoardSlug.parseDefault("board-12")).isEqualTo(12L);
        assertThat(BoardSlug.parseDefault("board-0")).isEqualTo(0L);
        assertThat(BoardSlug.parseDefault("board-012")).isNull();
        assertThat(BoardSlug.parseDefault("board-+12")).isNull();
        assertThat(BoardSlug.parseDefault("board-１２")).isNull();
        assertThat(BoardSlug.parseDefault("board-")).isNull();
        assertThat(BoardSlug.parseDefault("board--1")).isNull();
        assertThat(BoardSlug.parseDefault("12")).isNull();
        assertThat(BoardSlug.parseDefault("press")).isNull();
        assertThat(BoardSlug.parseDefault(null)).isNull();
    }

    @Test
    @DisplayName("현재 슬러그 — 사용자 슬러그가 없으면 board-{id}")
    void effective() {
        assertThat(BoardSlug.effective(7L, null)).isEqualTo("board-7");
        assertThat(BoardSlug.effective(7L, "press")).isEqualTo("press");
    }
}
