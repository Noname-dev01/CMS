package com.cms.config.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 레이트리밋 관련 Bean 배선을 전부 명시한다. {@code CmsApplication}이
 * {@code @ConfigurationPropertiesScan}을 쓰지 않으므로 {@link RateLimitProperties}는
 * {@code @EnableConfigurationProperties}로 별도 등록해야 하고, {@link Ticker}·
 * {@link RateLimitConfigValidator}·{@link TokenBucketRateLimiter}도 컴포넌트 스캔에
 * 맡기지 않고 이 클래스가 직접 정의한다(PLAN-public-endpoint-rate-limit.md 쟁점 12).
 *
 * <p>{@code rateLimitConfigValidator}를 {@code tokenBucketRateLimiter} 메서드의 파라미터로
 * 받아 소비한다 — 사용하지 않는 파라미터처럼 보이지만, 이렇게 해야 Spring이 검증을 캐시 생성
 * 전에 반드시 먼저 실행하도록 Bean 의존관계로 강제할 수 있다(검증기가 Bean이 아니면 검증
 * 로직 자체가 실행되지 않는다는 점이 핵심 — 쟁점 11·12).
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitFilterConfig {

    @Bean
    public Ticker ticker() {
        return new SystemTicker();
    }

    @Bean
    public RateLimitConfigValidator rateLimitConfigValidator(RateLimitProperties properties) {
        return new RateLimitConfigValidator(properties);
    }

    @Bean
    public TokenBucketRateLimiter tokenBucketRateLimiter(
            RateLimitProperties properties, Ticker ticker, RateLimitConfigValidator validator) {
        return new TokenBucketRateLimiter(properties, ticker);
    }

    @Bean
    public RateLimitFilter rateLimitFilter(RateLimitProperties properties, TokenBucketRateLimiter limiter) {
        return new RateLimitFilter(properties, limiter);
    }

    /**
     * 서블릿 컨테이너 자동 등록을 명시적으로 차단한다 — {@code SecurityConfig}가
     * {@code addFilterAfter(rateLimitFilter, CsrfFilter.class)}로만 등록해야 필터 순서 계약이
     * 유지된다. {@code setEnabled(false)}가 없으면 Spring Boot가 이 {@code Filter} 빈을
     * 컨테이너 필터 체인에도 자동 등록해 실행 위치가 흔들릴 수 있다.
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter rateLimitFilter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(rateLimitFilter);
        registration.setEnabled(false);
        return registration;
    }
}
