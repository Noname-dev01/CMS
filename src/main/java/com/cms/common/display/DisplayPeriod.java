package com.cms.common.display;

import com.cms.common.exception.InvalidRequestException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 노출 기간 규칙의 단일 원본(배너가 먼저 쓰고 팝업·예약 게시가 재사용한다 — PLAN-public-home-banner.md 쟁점 7).
 *
 * <p>구간은 {@code [start, end)} — 시작 시각 포함, 종료 시각 <b>미포함</b>이다. 둘 다 {@code null}이면 제한 없음이고, 한쪽만
 * {@code null}이면 그쪽은 열려 있다. 시각 단위는 분이라 저장 전에 {@link #normalize}로 분 단위로 절단한다 — 소수 초·초 단위 입력이
 * 저장 후 {@code start == end}를 만들거나 시작을 앞당기는 일이 없게 한다.
 *
 * <p>DB 조건(QueryDSL)은 Q클래스에 의존하므로 각 도메인 Repository가 같은 의미({@code start is null or start <= now},
 * {@code end is null or end > now})로 만든다. 두 표현이 같은 의미임은 도메인별 DB 시험이 고정한다.
 */
public final class DisplayPeriod {

    private DisplayPeriod() {
    }

    /** 분 단위로 절단한다. {@code null}은 그대로 통과한다. */
    public static LocalDateTime normalize(LocalDateTime value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MINUTES);
    }

    /** 정규화된 두 값이 모두 있으면 {@code start < end}여야 한다. 아니면 400. */
    public static void requireValid(LocalDateTime start, LocalDateTime end) {
        if (start != null && end != null && !start.isBefore(end)) {
            throw new InvalidRequestException("노출 종료 시각은 시작 시각보다 뒤여야 합니다.");
        }
    }

    /** {@code now}가 {@code [start, end)} 안인가. */
    public static boolean isActive(LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        return (start == null || !now.isBefore(start)) && (end == null || now.isBefore(end));
    }
}
