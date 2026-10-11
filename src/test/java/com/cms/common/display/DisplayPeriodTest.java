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

    // ===== 게시글 예약 게시용 입력 규칙 (PLAN-post-publish-period.md 쟁점 1·6·7) =====

    @Test
    @DisplayName("parseKstLocal: yyyy-MM-ddTHH:mm[:ss]만 읽고 null은 null(그쪽 열림)")
    void parseKstLocal_accepts() {
        assertThat(DisplayPeriod.parseKstLocal("2026-10-12T09:00")).isEqualTo(LocalDateTime.of(2026, 10, 12, 9, 0));
        assertThat(DisplayPeriod.parseKstLocal("2026-10-12T09:00:30")).isEqualTo(LocalDateTime.of(2026, 10, 12, 9, 0, 30));
        assertThat(DisplayPeriod.parseKstLocal("2028-02-29T00:00")).as("윤년 2월 29일").isEqualTo(LocalDateTime.of(2028, 2, 29, 0, 0));
        assertThat(DisplayPeriod.parseKstLocal(null)).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "[{index}] \"{0}\"은 400")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "", " ", "   ",                       // 빈 문자열·공백 — Jackson은 ""을 null로 읽어 기간을 지운다
            "2026-10-12T01:00:00Z", "2026-10-12T10:00+09:00", "2026-10-12T10:00:00+09:00[Asia/Seoul]",   // 오프셋
            "2026-10-12T10:00:00.123", "2026-10-12T10:00:00.000000",                                    // 소수 초
            "2026-10-12", "10:00", "2026-10-12 10:00", "2026/10/12T10:00",                              // 형식 불일치
            "2026-02-30T10:00", "2027-02-29T10:00", "2026-13-01T10:00", "2026-10-32T10:00",             // 존재하지 않는 날짜(보정 금지)
            "2026-10-11T24:00", "2026-10-11T23:60", "2026-10-11T10:00:60",                              // 24시·60분·60초
            "abc", "null", "12345"})
    void parseKstLocal_rejects(String value) {
        assertThatThrownBy(() -> DisplayPeriod.parseKstLocal(value))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageNotContaining(value.isBlank() ? "\u0000" : value);   // 입력 원문을 메시지에 싣지 않는다
    }

    @Test
    @DisplayName("requireInRange: 2000-01-01T00:00 이상 2100-01-01T00:00 미만만 통과, null은 통과")
    void requireInRange() {
        assertThatCode(() -> DisplayPeriod.requireInRange(null)).doesNotThrowAnyException();
        assertThatCode(() -> DisplayPeriod.requireInRange(LocalDateTime.of(2000, 1, 1, 0, 0))).doesNotThrowAnyException();
        assertThatCode(() -> DisplayPeriod.requireInRange(LocalDateTime.of(2099, 12, 31, 23, 59))).doesNotThrowAnyException();
        assertThatThrownBy(() -> DisplayPeriod.requireInRange(LocalDateTime.of(1999, 12, 31, 23, 59))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> DisplayPeriod.requireInRange(LocalDateTime.of(2100, 1, 1, 0, 0))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> DisplayPeriod.requireInRange(LocalDateTime.of(10000, 1, 1, 0, 0))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("statusOf: HIDDEN > SCHEDULED > EXPIRED > ACTIVE — 노출 여부가 꺼지면 기간과 무관하게 HIDDEN")
    void statusOf_priority() {
        LocalDateTime before = START.minusMinutes(1);
        LocalDateTime inside = START.plusMinutes(1);
        LocalDateTime after = END.plusMinutes(1);

        assertThat(DisplayPeriod.statusOf(true, START, END, before)).isEqualTo(DisplayStatus.SCHEDULED);
        assertThat(DisplayPeriod.statusOf(true, START, END, START)).as("시작 시각은 노출 중").isEqualTo(DisplayStatus.ACTIVE);
        assertThat(DisplayPeriod.statusOf(true, START, END, inside)).isEqualTo(DisplayStatus.ACTIVE);
        assertThat(DisplayPeriod.statusOf(true, START, END, END)).as("종료 시각은 만료").isEqualTo(DisplayStatus.EXPIRED);
        assertThat(DisplayPeriod.statusOf(true, START, END, after)).isEqualTo(DisplayStatus.EXPIRED);
        assertThat(DisplayPeriod.statusOf(true, null, null, inside)).as("기간 없음").isEqualTo(DisplayStatus.ACTIVE);
        assertThat(DisplayPeriod.statusOf(true, START, null, before)).isEqualTo(DisplayStatus.SCHEDULED);
        assertThat(DisplayPeriod.statusOf(true, null, END, after)).isEqualTo(DisplayStatus.EXPIRED);
        // useYn=false는 예약·만료와 겹쳐도 HIDDEN이다
        assertThat(DisplayPeriod.statusOf(false, START, END, before)).isEqualTo(DisplayStatus.HIDDEN);
        assertThat(DisplayPeriod.statusOf(false, START, END, inside)).isEqualTo(DisplayStatus.HIDDEN);
        assertThat(DisplayPeriod.statusOf(false, START, END, after)).isEqualTo(DisplayStatus.HIDDEN);
    }

    @Test
    @DisplayName("statusOf는 isActive와 모순되지 않는다 — ACTIVE일 때만 isActive가 참(useYn 켬)")
    void statusOf_consistentWithIsActive() {
        for (LocalDateTime now : new LocalDateTime[]{START.minusNanos(1), START, END.minusNanos(1), END}) {
            assertThat(DisplayPeriod.statusOf(true, START, END, now) == DisplayStatus.ACTIVE)
                    .as("now=%s", now).isEqualTo(DisplayPeriod.isActive(START, END, now));
        }
    }
}
