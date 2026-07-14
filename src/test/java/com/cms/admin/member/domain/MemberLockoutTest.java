package com.cms.admin.member.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Member의 자동 잠금 도메인 계약 단위 테스트 — DB 없이 상태 전이 규칙만 검증한다.
 */
class MemberLockoutTest {

    private Member lockedMember(LocalDateTime lockedAt, int failedCount) {
        return Member.builder()
                .id(1L)
                .userId("admin01")
                .pwd("encoded")
                .userName("홍길동")
                .email("admin01@test.com")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.LOCKED)
                .failedLoginCount(failedCount)
                .lockedAt(lockedAt)
                .build();
    }

    // ==================== changePassword ====================

    @Test
    @DisplayName("changePassword는 실패 카운트만 리셋하고 lockedAt은 보존한다 — 자동 잠금이 영구 잠금으로 변질되지 않는다")
    void changePassword_resetsCount_preservesLockedAt() {
        LocalDateTime lockedAt = LocalDateTime.of(2026, 7, 14, 12, 0);
        Member member = lockedMember(lockedAt, 5);

        member.changePassword("newEncoded");

        assertThat(member.getFailedLoginCount()).isZero();
        assertThat(member.getLockedAt()).isEqualTo(lockedAt); // 보존 — 30분 자동 해제 유지
        assertThat(member.getStatus()).isEqualTo(MemberStatus.LOCKED);
    }

    // ==================== changeStatus ====================

    @Test
    @DisplayName("changeStatus는 lockedAt을 항상 정리한다 — 수동 →LOCKED는 영구 잠금")
    void changeStatus_clearsLockedAt() {
        Member member = lockedMember(LocalDateTime.of(2026, 7, 14, 12, 0), 5);

        member.changeStatus(MemberStatus.DISABLED);

        assertThat(member.getLockedAt()).isNull();
    }

    @Test
    @DisplayName("LOCKED(자동)→DISABLED→LOCKED(수동) 회귀 경로에서 locked_at이 잔존하지 않는다 — 수동 잠금은 자동 해제되지 않음")
    void manualRelockAfterDisable_hasNoResidualLockedAt() {
        Member member = lockedMember(LocalDateTime.of(2026, 7, 14, 12, 0), 5);

        member.changeStatus(MemberStatus.DISABLED);
        member.changeStatus(MemberStatus.LOCKED);

        assertThat(member.getLockedAt()).isNull(); // 자동 해제 조건(locked_at 존재)에 걸리지 않는다
    }

    // ==================== resetFailedLoginCount ====================

    @Test
    @DisplayName("resetFailedLoginCount는 카운트와 lockedAt을 함께 정리한다")
    void resetFailedLoginCount_clearsCountAndLockedAt() {
        Member member = lockedMember(LocalDateTime.of(2026, 7, 14, 12, 0), 5);

        member.resetFailedLoginCount();

        assertThat(member.getFailedLoginCount()).isZero();
        assertThat(member.getLockedAt()).isNull();
    }

    // ==================== releaseExpiredAutoLock ====================

    @Test
    @DisplayName("lockedAt이 cutoff 이전이면 해제된다 (ACTIVE·카운트 0·lockedAt null·updateDate 갱신)")
    void releaseExpiredAutoLock_beforeCutoff_released() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 14, 13, 0);
        LocalDateTime cutoff = now.minusMinutes(30);
        Member member = lockedMember(cutoff.minusSeconds(1), 5);

        boolean released = member.releaseExpiredAutoLock(cutoff, now);

        assertThat(released).isTrue();
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(member.getFailedLoginCount()).isZero();
        assertThat(member.getLockedAt()).isNull();
        assertThat(member.getUpdateDate()).isEqualTo(now);
    }

    @Test
    @DisplayName("lockedAt이 정확히 cutoff와 같으면 해제된다 (<= 경계 계약)")
    void releaseExpiredAutoLock_exactlyAtCutoff_released() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 14, 13, 0);
        LocalDateTime cutoff = now.minusMinutes(30);
        Member member = lockedMember(cutoff, 5);

        assertThat(member.releaseExpiredAutoLock(cutoff, now)).isTrue();
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("lockedAt이 cutoff 이후(미만료)면 해제되지 않는다")
    void releaseExpiredAutoLock_afterCutoff_notReleased() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 14, 13, 0);
        LocalDateTime cutoff = now.minusMinutes(30);
        Member member = lockedMember(cutoff.plusSeconds(1), 5);

        assertThat(member.releaseExpiredAutoLock(cutoff, now)).isFalse();
        assertThat(member.getStatus()).isEqualTo(MemberStatus.LOCKED);
        assertThat(member.getFailedLoginCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("수동 잠금(lockedAt null)은 해제되지 않는다 — 관리자의 명시적 잠금은 영구")
    void releaseExpiredAutoLock_manualLock_notReleased() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 14, 13, 0);
        Member member = lockedMember(null, 0);

        assertThat(member.releaseExpiredAutoLock(now.minusMinutes(30), now)).isFalse();
        assertThat(member.getStatus()).isEqualTo(MemberStatus.LOCKED);
    }

    @Test
    @DisplayName("LOCKED가 아닌 상태에서는 해제가 동작하지 않는다")
    void releaseExpiredAutoLock_notLocked_noop() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 14, 13, 0);
        Member member = Member.builder()
                .id(1L).userId("admin01").pwd("encoded").userName("홍길동")
                .email("admin01@test.com").userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .lockedAt(now.minusHours(1)) // 오염 데이터 가정
                .build();

        assertThat(member.releaseExpiredAutoLock(now.minusMinutes(30), now)).isFalse();
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }
}
