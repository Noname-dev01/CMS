package com.cms.config.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** PLAN-passkey-challenge-stale-tab.md F4·F5 — 코드 화면 어설션 대기의 재사용 하한·제출 수명·회원 대조. */
class PasskeyAssertionStateTest {

    private static final LocalDateTime ISSUED = LocalDateTime.of(2026, 11, 20, 10, 0, 0);
    private final PasskeyAssertionState state = new PasskeyAssertionState("assert-1", 7L, null, ISSUED);

    @Test
    @DisplayName("F4: 재사용 하한은 TTL의 절반(2분 30초)")
    void reuseMinRemainingIsHalfTtl() {
        assertThat(PasskeyAssertionState.REUSE_MIN_REMAINING).isEqualTo(PasskeyAssertionState.TTL.dividedBy(2));
    }

    @Test
    @DisplayName("F4: 같은 회원·남은 수명 정확히 2분 30초면 재사용, 1초 적으면 재사용하지 않는다")
    void reuseBoundary() {
        assertThat(state.isReusableFor(7L, ISSUED.plusMinutes(2).plusSeconds(30))).isTrue();
        assertThat(state.isReusableFor(7L, ISSUED.plusMinutes(2).plusSeconds(31))).isFalse();
    }

    @Test
    @DisplayName("F5: 다른 회원의 대기는 재사용하지 않는다")
    void otherMemberNotReusable() {
        assertThat(state.isReusableFor(8L, ISSUED)).isFalse();
    }

    @Test
    @DisplayName("F4: 정확히 5분은 유효, 5분 1초는 만료")
    void expiryBoundary() {
        assertThat(state.isExpired(ISSUED.plusMinutes(5))).isFalse();
        assertThat(state.matches(7L, "assert-1", ISSUED.plusMinutes(5))).isTrue();
        assertThat(state.isExpired(ISSUED.plusMinutes(5).plusSeconds(1))).isTrue();
    }
}
