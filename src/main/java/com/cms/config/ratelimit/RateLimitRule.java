package com.cms.config.ratelimit;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.time.Duration;
import java.util.Set;

/**
 * {@link RateLimitProperties.RuleConfig}를 해석한 런타임 규칙.
 *
 * <p>경로 매칭({@code matcher})과 HTTP 메서드 매칭({@code methods})을 별도 필드로 분리한다 —
 * {@link PathPatternRequestMatcher}는 경로 패턴만 표현하고, HEAD가 GET과 동일 비용을 유발하는
 * 첨부 다운로드·공개 목록 규칙처럼 한 규칙이 여러 메서드를 함께 다뤄야 하기 때문이다
 * (PLAN-public-endpoint-rate-limit.md 쟁점 1).
 */
public record RateLimitRule(
        String id,
        PathPatternRequestMatcher matcher,
        Set<HttpMethod> methods,
        int capacity,
        Duration refillPeriod
) {
}
