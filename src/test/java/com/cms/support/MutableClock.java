package com.cms.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 시험에서 시각을 바꿔 가며 경계(시작 직전·시작·종료 직전·종료)를 실제 스택으로 재현하기 위한 가변 {@link Clock}. 시험 전용
 * {@code @TestConfiguration}에서 {@code @Primary} 빈으로 올려 앱의 KST {@code Clock}을 대신한다 — 판정 시각이 항상 주입된 Clock에서 나온다는
 * 규약({@code ClockUsageConventionTest})이 있어야 성립하는 시험이다.
 */
public class MutableClock extends Clock {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private volatile Instant instant;

    public MutableClock(LocalDateTime initialKst) {
        set(initialKst);
    }

    /** KST 벽시계 시각으로 지정한다. */
    public void set(LocalDateTime kst) {
        this.instant = kst.atZone(KST).toInstant();
    }

    @Override
    public ZoneId getZone() {
        return KST;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
