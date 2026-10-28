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

    @ParameterizedTest
    @ValueSource(strings = {
            "/main/notices/5", "/x/notices/+5/attachments/2?a#b", "/main/notices/0005", "/main/notices/５",
            "/main/notices/%35", "/main/%6Eotices/5", "/main/notices/./5", "/main/x/../notices/5", "/main/notices/5/../../notices/6",
    })
    @DisplayName("숫자 공지 경로(PR 4b) — 비정규 id·퍼센트 인코딩·점 세그먼트 변형도 참")
    void numericNotice(String url) {
        assertThat(BoardLinkPaths.isNumericNoticePath(url)).isTrue();
        assertThat(BoardLinkPaths.isRetiredNumericPath(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/main/notices", "/main/notices/", "/main/notices?page=2", "/main/notices/abc", "/main/notices/-1", "/notices/5",
            "/main/boards/board-5", "/main/notices/%zz", "https://example.com/main/notices/5", "",
    })
    @DisplayName("공지 목록(302로 유효)·비숫자·음수·사이트 세그먼트 없음·잘못된 인코딩·절대 URL은 숫자 공지 경로가 아니다")
    void notNumericNotice(String url) {
        assertThat(BoardLinkPaths.isNumericNoticePath(url)).isFalse();
    }

    @Test
    @DisplayName("숫자 공지 판정은 디코딩·정리한 사이트 세그먼트와 정규형 id를 돌려준다 — 접미부 ..는 최종 목적지 기준")
    void noticeTarget() {
        assertThat(BoardLinkPaths.numericNoticeTarget("/%6Dain/notices/+5/attachments/1"))
                .isEqualTo(new BoardLinkPaths.NoticeTarget("main", 5L));
        assertThat(BoardLinkPaths.numericNoticeTarget("/main/notices/5/../../notices/6"))
                .isEqualTo(new BoardLinkPaths.NoticeTarget("main", 6L));
        assertThat(BoardLinkPaths.numericNoticeTarget(null)).isNull();
    }

    @Test
    @DisplayName("V10이 쓰는 숫자 게시판 판정은 공지 경로를 보지 않는다 — V11 이전 DB에서 V10 의미 보존")
    void boardJudgementIgnoresNotices() {
        assertThat(BoardLinkPaths.numericBoardSiteSegment("/main/notices/12")).isNull();
        assertThat(BoardLinkPaths.isRetiredNumericPath("/main/boards/12")).isTrue();
        assertThat(BoardLinkPaths.isRetiredNumericPath("/main/notices")).isFalse();
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
