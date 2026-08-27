package com.cms.config.ratelimit;

import java.time.Duration;

/**
 * 토큰 버킷 알고리즘(lazy refill). 캐시(Caffeine)가 인스턴스 생명주기(상한·만료)를 관리하고,
 * 이 클래스는 "얼마나 허용할지"라는 정책 로직만 책임진다(PLAN-public-endpoint-rate-limit.md 쟁점 3b).
 *
 * <p><b>동시성</b>: 가변 필드({@code tokens}, {@code lastRefillNanos})를 {@link #tryConsume}
 * 안에서 {@code synchronized}로 선형화한다. 단일 IP·규칙 조합의 요청 동시성은 낮으므로 락 경합
 * 비용은 무시할 수준이다.
 *
 * <p><b>초기 토큰</b>: 생성 즉시 {@code capacity}로 채워진다 — 신규 IP의 첫 요청부터 버스트
 * 상한만큼 허용되는 것이 이 계획의 정책이다("버스트 상한 N + 평균 N/기간").
 *
 * <p><b>lazy 시각 초기화</b>: {@link Ticker#nanos()}(={@link System#nanoTime()})는 원점이
 * 임의이며 음수를 반환할 수 있다. {@code lastRefillNanos} 필드를 생성 시점에 확정하지 않고
 * 최초 {@link #tryConsume} 호출 시에만 "지금"으로 설정한다 — 필드 기본값 {@code 0}을 암묵적
 * 생성 시각으로 전제하면 음수 시간원 환경에서 리필이 멈추는 버그가 된다.
 *
 * <p><b>장기 유휴 후 리필</b>: 경과시간이 리필 주기 이상이면 곱셈 없이 즉시
 * {@code tokens = capacity}로 설정하고, 이때 반드시 {@code lastRefillNanos}를 "지금"으로
 * 리셋한다 — 리셋하지 않으면 초과 유휴시간이 "시간 크레딧"으로 남아 소비 직후에도 다시
 * 풀버스트가 지급되는 버그가 된다. 부분 토큰(짧은 경과)은 {@code double tokens} 필드가
 * 이미 정확히 보존하므로 별도의 시간 잔액이 필요 없다.
 */
class Bucket {

    private final int capacity;
    private final long refillPeriodNanos;

    private double tokens;
    private long lastRefillNanos;
    private boolean initialized;

    Bucket(int capacity, long refillPeriodNanos) {
        this.capacity = capacity;
        this.refillPeriodNanos = refillPeriodNanos;
        this.tokens = capacity;
        this.initialized = false;
    }

    /** {@link RateLimitFilterConfig}가 등록하는 Caffeine {@code Expiry}가 참조하는 자기 규칙의 리필 주기. */
    long refillPeriodNanos() {
        return refillPeriodNanos;
    }

    synchronized RateLimitDecision tryConsume(Ticker ticker) {
        long now = ticker.nanos();

        if (!initialized) {
            lastRefillNanos = now;
            initialized = true;
        } else {
            refill(now);
        }

        if (tokens >= 1.0) {
            tokens -= 1.0;
            return RateLimitDecision.allow();
        }
        return RateLimitDecision.reject(retryAfterSeconds());
    }

    private void refill(long now) {
        long elapsed = Math.max(0, now - lastRefillNanos);
        if (elapsed >= refillPeriodNanos) {
            tokens = capacity;
            lastRefillNanos = now; // 초과 유휴시간 폐기 — 반복 풀버스트 버그 방지
        } else if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + (double) elapsed * capacity / refillPeriodNanos);
            lastRefillNanos = now;
        }
    }

    /** 다음 토큰 하나가 채워지기까지 남은 시간을 초 단위로 올림(최소 1초)한다. */
    private long retryAfterSeconds() {
        double missing = 1.0 - tokens;
        long nanosNeeded = (long) Math.ceil(missing * refillPeriodNanos / capacity);
        long secondsNanos = Duration.ofSeconds(1).toNanos();
        long seconds = (nanosNeeded + secondsNanos - 1) / secondsNanos; // 나노초 → 초 올림
        return Math.max(1, seconds);
    }
}
