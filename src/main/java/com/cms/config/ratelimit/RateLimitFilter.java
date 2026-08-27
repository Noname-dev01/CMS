package com.cms.config.ratelimit;

import com.cms.common.api.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import static com.cms.common.api.GlobalApiExceptionHandler.API_MATCHER;

/**
 * 무인증 공개 엔드포인트 레이트리밋 필터. {@code @Component}를 부착하지 않는다 —
 * {@link RateLimitFilterConfig}가 이 클래스를 Bean으로 정의하되 서블릿 컨테이너 자동 등록은
 * {@code FilterRegistrationBean.setEnabled(false)}로 차단하고, {@code SecurityConfig}가
 * {@code addFilterAfter(rateLimitFilter, CsrfFilter.class)}로만 명시 등록한다
 * (PLAN-public-endpoint-rate-limit.md 쟁점 10).
 *
 * <p><b>필터 위치가 {@code CsrfFilter} 다음인 이유</b>: CSRF 검증에 실패해 컨트롤러까지
 * 도달하지 못하는 요청도 이 필터가 {@code CsrfFilter}보다 먼저 있으면 이미 quota를
 * 소비시킨다 — 공격자가 외부 사이트에서 피해자 브라우저로 CSRF 토큰 없는 form POST를
 * 반복 전송해 피해자 IP의 quota를 고갈시키는 교차 사이트 공격이 가능해진다(쟁점 4).
 * GET·HEAD는 기본적으로 CSRF 검증 대상이 아니므로 공개 목록·첨부 다운로드 규칙에는
 * 이 위치가 영향을 주지 않는다.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final RateLimitProperties properties;
    private final TokenBucketRateLimiter limiter;

    public RateLimitFilter(RateLimitProperties properties, TokenBucketRateLimiter limiter) {
        this.properties = properties;
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        RateLimitRule matched = findMatchingRule(request);
        if (matched == null) {
            chain.doFilter(request, response);
            return;
        }

        RateLimitDecision decision = limiter.tryConsume(request.getRemoteAddr(), matched);
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        reject(request, response, decision);
    }

    private RateLimitRule findMatchingRule(HttpServletRequest request) {
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        for (RateLimitRule rule : limiter.rules()) {
            if (rule.methods().contains(method) && rule.matcher().matches(request)) {
                return rule;
            }
        }
        return null;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, RateLimitDecision decision)
            throws IOException {
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));

        if (API_MATCHER.matches(request)) {
            ApiErrorResponse body = ApiErrorResponse.of(
                    request.getRequestURI(), "RATE_LIMITED", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.");
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(MAPPER.writeValueAsString(body));
        } else {
            // sendError + 필터 종료 → 컨테이너 ERROR 재디스패치 → CustomErrorController → error/429.html.
            response.sendError(HttpStatus.TOO_MANY_REQUESTS.value());
        }
    }
}
