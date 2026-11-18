package com.cms.admin.member.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 강제 등록 화면의 대기 재사용 판정(PLAN-totp-enrollment-reuse.md 결정 1·FM2) — 고정 시각으로 경계를 본다.
 * 정확히 10분은 유효다(확정 판정 {@code issuedAt + TTL isBefore now}와 같은 경계).
 */
class TotpEnrollmentTest {

    private static final LocalDateTime ISSUED = LocalDateTime.of(2026, 11, 19, 10, 0, 0);
    private static final TotpEnrollment PENDING = new TotpEnrollment("e-1", 7L, new byte[20], ISSUED);
    private static final LocalDateTime DEADLINE = ISSUED.plus(MemberTotpService.ENROLLMENT_TTL);

    @Test
    @DisplayName("같은 회원·만료 1µs 전과 정확히 10분은 재사용, 1µs 지나면 불가")
    void reusableUntilExactlyTtl() {
        assertThat(PENDING.isReusableFor(7L, ISSUED)).isTrue();
        assertThat(PENDING.isReusableFor(7L, DEADLINE.minusNanos(1_000))).isTrue();
        assertThat(PENDING.isReusableFor(7L, DEADLINE)).isTrue();
        assertThat(PENDING.isReusableFor(7L, DEADLINE.plusNanos(1_000))).isFalse();
    }

    @Test
    @DisplayName("다른 회원·회원 없음이면 시각과 무관하게 불가")
    void otherMemberIsNotReusable() {
        assertThat(PENDING.isReusableFor(8L, ISSUED)).isFalse();
        assertThat(PENDING.isReusableFor(null, ISSUED)).isFalse();
    }
}
