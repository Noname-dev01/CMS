package com.cms.config.ratelimit;

/**
 * 레이트리밋 판정 결과.
 *
 * @param allowed          소비 허용 여부
 * @param retryAfterSeconds 거절된 경우 다음 토큰이 채워지기까지 남은 시간(초, 올림, 최소 1) —
 *                          허용된 경우에는 의미가 없다(0).
 */
public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {

    public static RateLimitDecision allow() {
        return new RateLimitDecision(true, 0);
    }

    public static RateLimitDecision reject(long retryAfterSeconds) {
        return new RateLimitDecision(false, retryAfterSeconds);
    }
}
