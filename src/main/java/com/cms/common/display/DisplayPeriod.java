package com.cms.common.display;

import com.cms.common.exception.InvalidRequestException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
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

    /** 게시글 기간 입력의 허용 범위 — DB {@code datetime(6)}(1000~9999년) 안의 제품 범위. 이 밖은 DB 예외(충돌·500)가 아니라 입력 400이어야 한다. */
    public static final LocalDateTime MIN_VALUE = LocalDateTime.of(2000, 1, 1, 0, 0);
    public static final LocalDateTime MAX_VALUE_EXCLUSIVE = LocalDateTime.of(2100, 1, 1, 0, 0);

    /** 오프셋 없는 KST 벽시계 시각만 받는다. STRICT라 존재하지 않는 날짜(2월 30일)·{@code 24:00}을 다른 날짜로 보정하지 않고 거부한다. */
    private static final DateTimeFormatter STRICT_LOCAL = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm[:ss]")
            .withResolverStyle(ResolverStyle.STRICT);

    /**
     * 요청 문자열을 엄격하게 {@code LocalDateTime}으로 읽는다. {@code null}은 {@code null}(그쪽 열림)이다. 빈 문자열·공백, 오프셋({@code Z}·{@code +09:00}),
     * 소수 초, 날짜만, 존재하지 않는 날짜, {@code 24:00}은 전부 400이다. Jackson의 {@code LocalDateTime} 역직렬화는 이들을 조용히 보정하거나
     * {@code null}로 읽어 기간을 지우므로 쓰지 않는다. 예외 메시지에 입력 원문을 싣지 않는다.
     */
    public static LocalDateTime parseKstLocal(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, STRICT_LOCAL);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("노출 시각은 yyyy-MM-ddTHH:mm 형식(한국 시각)이어야 합니다.");
        }
    }

    /** 값이 있으면 {@link #MIN_VALUE} 이상 {@link #MAX_VALUE_EXCLUSIVE} 미만이어야 한다. 아니면 400. 분 절단 전에 호출한다. */
    public static void requireInRange(LocalDateTime value) {
        if (value != null && (value.isBefore(MIN_VALUE) || !value.isBefore(MAX_VALUE_EXCLUSIVE))) {
            throw new InvalidRequestException("노출 시각은 2000년 1월 1일부터 2099년 12월 31일 사이여야 합니다.");
        }
    }

    /** 노출 상태. {@code HIDDEN} > {@code SCHEDULED} > {@code EXPIRED} > {@code ACTIVE} 순으로 먼저 맞는 것이다. */
    public static DisplayStatus statusOf(boolean useYn, LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        if (!useYn) {
            return DisplayStatus.HIDDEN;
        }
        if (start != null && now.isBefore(start)) {
            return DisplayStatus.SCHEDULED;
        }
        if (end != null && !now.isBefore(end)) {
            return DisplayStatus.EXPIRED;
        }
        return DisplayStatus.ACTIVE;
    }
}
