package com.cms.common.display;

import com.cms.common.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 노출 기간 규칙 — 구간은 [start, end)(시작 포함·종료 미포함), 시각 단위는 분(PLAN-public-home-banner.md 쟁점 7). */
class DisplayPeriodTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 10, 9, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 20, 0, 0);

    @Test
    @DisplayName("경계: 시작 직전은 비노출, 시작 시각부터 노출, 종료 직전까지 노출, 종료 시각은 비노출")
    void boundaries() {
        assertThat(DisplayPeriod.isActive(START, END, START.minusNanos(1))).as("시작 직전").isFalse();
        assertThat(DisplayPeriod.isActive(START, END, START)).as("시작 시각").isTrue();
        assertThat(DisplayPeriod.isActive(START, END, END.minusNanos(1))).as("종료 직전").isTrue();
        assertThat(DisplayPeriod.isActive(START, END, END)).as("종료 시각").isFalse();
    }

    @Test
    @DisplayName("한쪽 또는 양쪽이 null이면 그쪽은 열려 있다(null/null = 제한 없음)")
    void openEnded() {
        assertThat(DisplayPeriod.isActive(null, null, START)).isTrue();
        assertThat(DisplayPeriod.isActive(START, null, START.minusMinutes(1))).isFalse();
        assertThat(DisplayPeriod.isActive(START, null, START.plusYears(100))).isTrue();
        assertThat(DisplayPeriod.isActive(null, END, END.minusMinutes(1))).isTrue();
        assertThat(DisplayPeriod.isActive(null, END, END)).isFalse();
    }

    @Test
    @DisplayName("normalize: 분 단위로 절단한다(초·나노초 제거), null은 그대로")
    void normalizeTruncatesToMinute() {
        assertThat(DisplayPeriod.normalize(LocalDateTime.of(2026, 10, 10, 12, 0, 59, 999_000_000)))
                .isEqualTo(LocalDateTime.of(2026, 10, 10, 12, 0));
        assertThat(DisplayPeriod.normalize(null)).isNull();
    }

    @Test
    @DisplayName("requireValid: start < end만 통과 — 같거나 역전이면 400, 한쪽만 있으면 통과")
    void requireValid() {
        assertThatCode(() -> DisplayPeriod.requireValid(START, END)).doesNotThrowAnyException();
        assertThatCode(() -> DisplayPeriod.requireValid(null, END)).doesNotThrowAnyException();
        assertThatCode(() -> DisplayPeriod.requireValid(START, null)).doesNotThrowAnyException();
        assertThatCode(() -> DisplayPeriod.requireValid(null, null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> DisplayPeriod.requireValid(START, START)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> DisplayPeriod.requireValid(END, START)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("소수 초 입력 12:00:00.100 ~ 12:00:00.900은 절단 후 같아져 400이다 — 저장 후 영영 노출되지 않는 배너를 막는다")
    void fractionalSecondsCollapseAndAreRejected() {
        LocalDateTime start = DisplayPeriod.normalize(LocalDateTime.of(2026, 10, 10, 12, 0, 0, 100_000_000));
        LocalDateTime end = DisplayPeriod.normalize(LocalDateTime.of(2026, 10, 10, 12, 0, 0, 900_000_000));

        assertThat(start).isEqualTo(end);
        assertThatThrownBy(() -> DisplayPeriod.requireValid(start, end)).isInstanceOf(InvalidRequestException.class);
    }
}
