package com.cms.admin.message.service;

import com.cms.common.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 쪽지 제목·본문 검증·정규화 파이프라인(PLAN-admin-message.md §5-B, R1-9) — 순서·유니코드 범위·고정 문구. */
class MessageTextPolicyTest {

    // ===================== 제목 =====================

    @Test
    @DisplayName("제목: 앞뒤 공백을 제거하고 100자까지 허용한다(100자 통과·101자 거부)")
    void title_trimAndLengthBoundary() {
        assertThat(MessageTextPolicy.normalizeTitle("  안녕하세요  ")).isEqualTo("안녕하세요");
        assertThat(MessageTextPolicy.normalizeTitle("가".repeat(100))).hasSize(100);
        assertThatThrownBy(() -> MessageTextPolicy.normalizeTitle("가".repeat(101)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("제목: null·빈 값·공백만은 거부한다")
    void title_blankRejected() {
        for (String blank : new String[]{null, "", "   ", "　"}) {
            assertThatThrownBy(() -> MessageTextPolicy.normalizeTitle(blank)).isInstanceOf(InvalidRequestException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "줄\n바꿈", "줄\r바꿈", "탭\t포함", "널\u0000문자", "NEL\u0085", "삭제\u007f문자",
            "줄구분 자", "문단구분 자",
            "방향‮위조", "방향‪", "방향⁦", "방향⁩"
    })
    @DisplayName("제목: 개행·탭 등 제어문자(Cc)·줄/문단 구분자(Zl·Zp)·양방향 제어문자는 거부한다 — 한 줄이어야 한다")
    void title_forbiddenCharactersRejected(String title) {
        assertThatThrownBy(() -> MessageTextPolicy.normalizeTitle(title)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("제목: 이모지(서로게이트 쌍)와 ZWJ 합성 시퀀스는 허용한다")
    void title_emojiAndZwjAllowed() {
        String family = "👨‍👩‍👧"; // ZWJ(U+200D)는 일반 서식문자(Cf) — 거부하지 않는다
        assertThat(MessageTextPolicy.normalizeTitle("안녕 😀 " + family)).contains(family);
    }

    @Test
    @DisplayName("제목: 고립 서로게이트(잘못된 UTF-16)는 거부한다")
    void title_loneSurrogateRejected() {
        assertThatThrownBy(() -> MessageTextPolicy.normalizeTitle("고립\ud83d")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> MessageTextPolicy.normalizeTitle("고립\ude00")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> MessageTextPolicy.normalizeTitle("\ud83d\ud83d")).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("제목: 원문이 200 UTF-16 단위를 넘으면 trim으로 줄어들 길이여도 거부한다(원문 크기 방어)")
    void title_rawSizeCap() {
        assertThatThrownBy(() -> MessageTextPolicy.normalizeTitle(" ".repeat(150) + "가".repeat(60)))
                .isInstanceOf(InvalidRequestException.class);
    }

    // ===================== 본문 =====================

    @Test
    @DisplayName("본문: 개행·탭은 허용하고 CRLF·CR은 LF로 정규화한다")
    void body_newlineNormalization() {
        assertThat(MessageTextPolicy.normalizeBody("첫줄\r\n둘째줄\r셋째줄\n넷째\t탭")).isEqualTo("첫줄\n둘째줄\n셋째줄\n넷째\t탭");
    }

    @Test
    @DisplayName("본문: 2000자 통과·2001자 거부, 정규화 후 길이로 판정한다 — a×1999 + CRLF는 원문 2001이어도 정규화 후 2000이라 허용")
    void body_lengthIsMeasuredAfterNormalization() {
        assertThat(MessageTextPolicy.normalizeBody("a".repeat(2000))).hasSize(2000);
        assertThatThrownBy(() -> MessageTextPolicy.normalizeBody("a".repeat(2001))).isInstanceOf(InvalidRequestException.class);

        String crlfAtEnd = "a".repeat(1999) + "\r\n"; // 원문 2001 UTF-16 단위, 저장값은 2000
        assertThat(crlfAtEnd.length()).isEqualTo(2001);
        assertThat(MessageTextPolicy.normalizeBody(crlfAtEnd)).hasSize(2000);
    }

    @Test
    @DisplayName("본문: 원문이 4000 UTF-16 단위를 넘으면 거부한다(원문 크기 방어)")
    void body_rawSizeCap() {
        assertThatThrownBy(() -> MessageTextPolicy.normalizeBody("\n".repeat(4001))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("본문: null·빈 값·공백/개행만은 거부한다")
    void body_blankRejected() {
        for (String blank : new String[]{null, "", "  ", "\n\n", "\t \r\n"}) {
            assertThatThrownBy(() -> MessageTextPolicy.normalizeBody(blank)).isInstanceOf(InvalidRequestException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"널\u0000", "NEL\u0085문자", "삭제\u007f", "줄구분 ", "문단구분 ", "방향‮위조", "방향⁧", "벨\u0007"})
    @DisplayName("본문: \\n·\\t 외 제어문자·Zl·Zp·양방향 제어문자는 거부한다")
    void body_forbiddenCharactersRejected(String body) {
        assertThatThrownBy(() -> MessageTextPolicy.normalizeBody(body)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("본문: 고립 서로게이트는 거부하고 정상 이모지(서로게이트 쌍)·HTML 같은 문자는 원문 그대로 허용한다 — 이스케이프·필터링은 하지 않는다")
    void body_emojiAllowedHtmlKeptVerbatim() {
        assertThatThrownBy(() -> MessageTextPolicy.normalizeBody("고립\ud83d")).isInstanceOf(InvalidRequestException.class);

        String payload = "<script>alert(1)</script> <img src=x onerror=alert(1)> javascript:alert(1) 😀";
        assertThat(MessageTextPolicy.normalizeBody(payload)).isEqualTo(payload);
    }

    @Test
    @DisplayName("실패 메시지는 고정 한국어 문구이고 사용자 입력을 담지 않는다")
    void failureMessagesAreFixedAndNeverEchoInput() {
        String marker = "비밀표식-9f3a";
        assertThatThrownBy(() -> MessageTextPolicy.normalizeTitle(marker + "\n"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage(MessageTextPolicy.INVALID_TITLE_MESSAGE)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(marker));
        assertThatThrownBy(() -> MessageTextPolicy.normalizeBody(marker + "\u0000"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage(MessageTextPolicy.INVALID_BODY_MESSAGE)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(marker));
    }
}
