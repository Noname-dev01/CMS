package com.cms.publicweb.board.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 새 글 배지 판정 순수 함수(PLAN-board-definition.md PR 1 P1-2 ④·P1-v3). */
class NewBadgeTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 12, 0, 0);

    @Test
    @DisplayName("기간 0 이하는 날짜를 보지 않고 배지 없음 — 기준이 NULL이어도 예외가 없다")
    void zeroDaysIsOff() {
        assertThat(NewBadge.isNew(NOW, null, 0, NOW)).isFalse();
        assertThat(NewBadge.isNew(null, null, 0, NOW)).isFalse();
        assertThat(NewBadge.isNew(NOW, null, -1, NOW)).isFalse();
    }

    @Test
    @DisplayName("정확히 N×24시간 전은 배지(경계 포함), 1마이크로초 더 이전이면 없음")
    void boundaryIsInclusive() {
        LocalDateTime exactly = NOW.minusHours(24L * 3);
        assertThat(NewBadge.isNew(exactly, null, 3, NOW)).isTrue();
        assertThat(NewBadge.isNew(exactly.minusNanos(1_000), null, 3, NOW)).isFalse();
        assertThat(NewBadge.isNew(NOW, null, 1, NOW)).as("방금 쓴 글").isTrue();
    }

    @Test
    @DisplayName("예약 게시 글은 작성일이 아니라 늦은 쪽(publishStart)을 기준으로 센다")
    void usesLaterOfCreateAndPublishStart() {
        LocalDateTime created = NOW.minusDays(10);
        assertThat(NewBadge.isNew(created, null, 3, NOW)).as("작성일 기준이면 지남").isFalse();
        assertThat(NewBadge.isNew(created, NOW.minusHours(1), 3, NOW)).as("공개된 지 1시간").isTrue();
        assertThat(NewBadge.isNew(NOW.minusDays(1), NOW.minusDays(10), 3, NOW)).as("publishStart가 더 이르면 작성일 기준").isTrue();
    }

    @Test
    @DisplayName("create_date가 NULL이면 publishStart만, 둘 다 NULL이면 배지 없음 — 예외 없음")
    void nullSafe() {
        assertThat(NewBadge.isNew(null, NOW.minusHours(2), 1, NOW)).isTrue();
        assertThat(NewBadge.isNew(null, NOW.minusDays(5), 1, NOW)).isFalse();
        assertThat(NewBadge.isNew(null, null, 30, NOW)).isFalse();
    }
}
