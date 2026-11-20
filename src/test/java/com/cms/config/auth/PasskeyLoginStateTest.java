package com.cms.config.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** PLAN-passkey-challenge-stale-tab.md F4 — 재사용 하한(2분 30초)·제출 수명(5분)의 경계. */
class PasskeyLoginStateTest {

    private static final LocalDateTime ISSUED = LocalDateTime.of(2026, 11, 20, 10, 0, 0);
    private final PasskeyLoginState state = new PasskeyLoginState("login-1", null, ISSUED);

    @Test
    @DisplayName("F4: 재사용 하한은 TTL의 절반(2분 30초)")
    void reuseMinRemainingIsHalfTtl() {
        assertThat(PasskeyLoginState.REUSE_MIN_REMAINING).isEqualTo(PasskeyLoginState.TTL.dividedBy(2));
    }

    @Test
    @DisplayName("F4: 남은 수명이 정확히 2분 30초면 재사용, 1초 적으면 재사용하지 않는다")
    void reuseBoundary() {
        assertThat(state.isReusable(ISSUED)).isTrue();
        assertThat(state.isReusable(ISSUED.plusMinutes(2).plusSeconds(30))).isTrue();
        assertThat(state.isReusable(ISSUED.plusMinutes(2).plusSeconds(31))).isFalse();
    }

    @Test
    @DisplayName("F4: 정확히 5분은 유효(일치·만료 아님), 5분 1초는 만료")
    void expiryBoundary() {
        assertThat(state.isExpired(ISSUED.plusMinutes(5))).isFalse();
        assertThat(state.matches("login-1", ISSUED.plusMinutes(5))).isTrue();
        assertThat(state.isExpired(ISSUED.plusMinutes(5).plusSeconds(1))).isTrue();
        assertThat(state.matches("login-1", ISSUED.plusMinutes(5).plusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("F4: 식별자가 다르거나 없으면 일치하지 않지만 만료도 아니다(제출이 남기는 갈래)")
    void mismatchIsNotExpiry() {
        assertThat(state.matches("other", ISSUED)).isFalse();
        assertThat(state.matches(null, ISSUED)).isFalse();
        assertThat(state.isExpired(ISSUED)).isFalse();
    }
}
