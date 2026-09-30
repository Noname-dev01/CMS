package com.cms.admin.member.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Member 도메인 변경 메서드가 호출자가 넘긴 시각(앱 Clock 기준)을 updateDate에 그대로 기록하는지 확인한다.
 * 시스템 시각·기본 시간대에 의존하지 않는 것이 목적이라 과거 고정 시각을 쓴다.
 */
class MemberTimestampTest {

    private static final LocalDateTime FIXED = LocalDateTime.of(2026, 9, 30, 0, 0);

    private Member member() {
        return Member.builder()
                .userId("ts-user")
                .userName("이름")
                .email("ts@example.com")
                .pwd("hash")
                .userType(Role.ROLE_MANAGER)
                .status(MemberStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("updateInfo·changeRole·changeStatus는 전달받은 시각을 updateDate에 기록한다")
    void mutators_recordGivenNow() {
        Member m = member();

        m.updateInfo("새이름", "new@example.com", FIXED);
        assertEquals(FIXED, m.getUpdateDate());

        LocalDateTime t2 = FIXED.plusMinutes(1);
        m.changeRole(Role.ROLE_ADMIN, t2);
        assertEquals(t2, m.getUpdateDate());

        LocalDateTime t3 = FIXED.plusMinutes(2);
        m.changeStatus(MemberStatus.DISABLED, t3);
        assertEquals(t3, m.getUpdateDate());
    }

    @Test
    @DisplayName("issueResetToken·clearResetToken도 전달받은 시각을 updateDate에 기록한다")
    void resetTokenMutators_recordGivenNow() {
        Member m = member();

        m.issueResetToken("hash", FIXED.plusMinutes(30), FIXED);
        assertEquals(FIXED, m.getUpdateDate());

        LocalDateTime later = FIXED.plusMinutes(5);
        m.clearResetToken(later);
        assertEquals(later, m.getUpdateDate());
    }
}
