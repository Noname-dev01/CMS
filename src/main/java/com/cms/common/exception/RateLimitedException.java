package com.cms.common.exception;

/**
 * 서비스가 판정한 빈도 제한 초과(429). 무인증 공개 경로의 {@code RateLimitFilter}와 같은 응답 형식
 * ({@code code=RATE_LIMITED}, {@code Retry-After} 초)으로 {@code GlobalApiExceptionHandler}가 변환한다.
 * 메시지는 고정 문구여야 한다 — 사용자 입력을 담지 않는다(감사·로그 비유출 계약, PLAN-admin-message.md §5-G).
 */
public class RateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitedException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /** 다시 시도해도 되는 시점까지 남은 초(최소 1). */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
