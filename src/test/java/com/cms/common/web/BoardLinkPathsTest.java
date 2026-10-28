package com.cms.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 숫자 게시판 경로 판정(PLAN-board-definition.md PR 4a D5) — 브라우저가 실제로 이동하는 경로 기준. */
class BoardLinkPathsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/main/boards/12", "/main/boards/12/posts/3", "/x/boards/+12/posts/3?a#b", "/main/boards/0012", "/main/boards/１２",
            "/main/boards/%31%32", "/main/%62oards/12", "/main/%62%6F%61%72%64%73/12",
            "/main/boards/./12", "/main/x/../boards/12", "/main/boards/%2e/12", "/main/boards/%2E/12", "/main/boards/12/.",
            "/main/boards/12?keyword=board-1", "/main/boards/12#x",
    })
    @DisplayName("숫자 게시판 경로 — 비정규 id·퍼센트 인코딩·점 세그먼트 변형도 참")
    void numeric(String url) {
        assertThat(BoardLinkPaths.isNumericBoardPath(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/main/boards/board-12", "/main/boards/news", "/main/boards", "/main/boards/", "/boards/12", "//main/boards/12",
            "/main/boards/-1", "/main/boards/9223372036854775808", "/main/boards/%zz", "/main/boards/1%zz",
            "/main/boards/../boards/board-3", "/main/BOARDS/12", "/main%2Fboards%2F12", "main/boards/12",
            "https://example.com/main/boards/12", "/main/notices/12", "",
    })
    @DisplayName("슬러그 경로·사이트 세그먼트 없음·음수·잘못된 인코딩·대소문자 다름·절대 URL은 거짓")
    void notNumeric(String url) {
        assertThat(BoardLinkPaths.isNumericBoardPath(url)).isFalse();
    }

    @Test
    @DisplayName("사이트 세그먼트는 디코딩·정리된 값으로 돌려준다")
    void siteSegment() {
        assertThat(BoardLinkPaths.numericBoardSiteSegment("/%6Dain/boards/12")).isEqualTo("main");
        assertThat(BoardLinkPaths.numericBoardSiteSegment("/a/../main/boards/12")).isEqualTo("main");
        assertThat(BoardLinkPaths.numericBoardSiteSegment("/main/boards/board-12")).isNull();
        assertThat(BoardLinkPaths.numericBoardSiteSegment(null)).isNull();
    }
}
