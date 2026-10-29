package com.cms.common.exception;

/**
 * 서버가 일시적으로 처리할 자리가 없다는 판정(503). 대표 이미지 썸네일 생성처럼 동시 실행 수를 제한한 작업에서 자리를 못 얻었을 때 쓴다.
 * {@code RateLimitedException}(429 — 이 사용자가 너무 많이 보냈다)과 원인이 달라 따로 둔다. 메시지는 고정 문구여야 한다 — 사용자 입력을 담지 않는다.
 */
public class ServiceBusyException extends RuntimeException {

    private final long retryAfterSeconds;

    public ServiceBusyException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /** 다시 시도해도 되는 시점까지 남은 초(최소 1). */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
