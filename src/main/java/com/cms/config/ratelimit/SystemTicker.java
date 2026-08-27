package com.cms.config.ratelimit;

/**
 * {@link Ticker}의 운영 구현. {@link System#nanoTime()}을 그대로 감싼다.
 *
 * <p>{@code @Component}를 붙이지 않는다 — {@link RateLimitFilterConfig}가 {@code @Bean}으로
 * 명시 등록한다(컴포넌트 스캔에 맡기면 같은 {@link Ticker} 타입 빈이 두 경로로 등록돼 충돌한다).
 */
public class SystemTicker implements Ticker {

    @Override
    public long nanos() {
        return System.nanoTime();
    }
}
