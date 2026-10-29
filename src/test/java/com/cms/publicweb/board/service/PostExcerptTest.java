package com.cms.publicweb.board.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 웹진형 요약 추출 시험(PLAN-board-definition.md PR 6 D5). */
class PostExcerptTest {

    @Test
    @DisplayName("본문이 null·빈 문자열이면 null이다")
    void nullAndEmpty() {
        assertThat(PostExcerpt.of(null)).isNull();
        assertThat(PostExcerpt.of("")).isNull();
    }

    @Test
    @DisplayName("태그는 벗기고 블록 경계·br은 공백이 되어 문단이 한 단어로 붙지 않는다")
    void blockBoundariesBecomeSpaces() {
        assertThat(PostExcerpt.of("<p>첫째 문단</p><p>둘째 문단</p>")).isEqualTo("첫째 문단 둘째 문단");
        assertThat(PostExcerpt.of("<p>줄1<br>줄2</p>")).isEqualTo("줄1 줄2");
        assertThat(PostExcerpt.of("<h2>제목</h2><ul><li>하나</li><li>둘</li></ul>")).isEqualTo("제목 하나 둘");
        assertThat(PostExcerpt.of("<p>글자<strong>강조</strong>이어짐</p>")).as("인라인 태그는 공백을 만들지 않는다").isEqualTo("글자강조이어짐");
    }

    @Test
    @DisplayName("저장 형식의 &nbsp; 연속과 일반 공백 연속은 한 칸으로 접히고 앞뒤 공백은 제거된다")
    void whitespaceFolded() {
        assertThat(PostExcerpt.of("<p>&nbsp;&nbsp;가&nbsp;&nbsp; 나   다&nbsp;</p>")).isEqualTo("가 나 다");
    }

    @Test
    @DisplayName("이미지만 있는 본문·빈 문단만 있는 본문은 null이다 — 템플릿이 요약 줄을 생략한다")
    void textlessBodyIsNull() {
        assertThat(PostExcerpt.of("<p><img src=\"/content-images/1\"></p>")).isNull();
        assertThat(PostExcerpt.of("<p><br></p><p>&nbsp;</p>")).isNull();
    }

    @Test
    @DisplayName("120 코드 포인트까지는 그대로, 121부터는 120 코드 포인트로 자르고 끝 공백을 지운 뒤 …을 붙인다")
    void truncationBoundary() {
        String exact = "가".repeat(120);
        assertThat(PostExcerpt.of("<p>" + exact + "</p>")).isEqualTo(exact);

        assertThat(PostExcerpt.of("<p>" + "가".repeat(121) + "</p>")).isEqualTo("가".repeat(120) + "…");

        // 120번째 글자 바로 뒤가 공백이면 잘린 끝의 공백은 지운다
        String spaced = "나".repeat(119) + "다 라마바";
        assertThat(PostExcerpt.of("<p>" + spaced + "</p>")).isEqualTo("나".repeat(119) + "다…");
    }

    @Test
    @DisplayName("이모지(서로게이트 쌍)가 120 경계에 걸려도 쪼개지지 않고 코드 포인트 단위로 센다")
    void surrogatePairsAreNotSplit() {
        String emoji = "😀";
        String body = "가".repeat(119) + emoji + emoji;

        String excerpt = PostExcerpt.of("<p>" + body + "</p>");

        assertThat(excerpt).isEqualTo("가".repeat(119) + emoji + "…");
        assertThat(new String(excerpt.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).as("짝 잃은 서로게이트 없음").isEqualTo(excerpt);
    }

    @Test
    @DisplayName("앞 20,000 UTF-16 코드 유닛 뒤의 텍스트는 읽지 않는다")
    void parseLimitIgnoresTail() {
        String head = "<p>머리</p>";
        String filler = "<p>" + "x".repeat(PostExcerpt.PARSE_LIMIT) + "</p>";
        String body = head + filler + "<p>꼬리</p>";

        String excerpt = PostExcerpt.of(body);

        assertThat(excerpt).startsWith("머리 xxx").doesNotContain("꼬리");
    }

    @Test
    @DisplayName("파싱 상한의 경계가 서로게이트 쌍의 가운데여도 짝 잃은 문자가 요약에 들어가지 않는다")
    void parseLimitBoundaryInsideSurrogatePair() {
        String emoji = "😀";
        // 경계(20,000번째 유닛)가 이모지의 높은/낮은 서로게이트 사이에 오도록 앞을 채운다
        String body = "a".repeat(PostExcerpt.PARSE_LIMIT - 1) + emoji + "끝";

        String excerpt = PostExcerpt.of(body);

        assertThat(excerpt).isNotNull();
        // 짝 잃은 서로게이트는 UTF-8로 인코딩하면 '?'로 바뀌므로 왕복 결과가 달라진다
        assertThat(new String(excerpt.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .as("짝 잃은 서로게이트 없음").isEqualTo(excerpt);
    }

    @Test
    @DisplayName("본문 속 태그 문자열은 엔티티 해제된 문자 그대로 평문이다 — 출력은 th:text가 이스케이프한다")
    void escapedTagTextStaysPlainText() {
        assertThat(PostExcerpt.of("<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>")).isEqualTo("<script>alert(1)</script>");
    }
}
