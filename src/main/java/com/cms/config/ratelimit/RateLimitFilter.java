package com.cms.config.ratelimit;

import com.cms.common.api.ApiErrorResponse;
import com.cms.config.auth.TotpAuthorities;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@code login-redirect} 규칙의 한도 초과 이동 위치 — 설정으로 바꾸지 않는다(결정 2: 임의 URL 표면을 만들지 않는다). */
    public static final String LOGIN_RATE_LIMITED_PATH = "/admin/login?rateLimited";

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

        // 로그인 세션 면제 규칙(content-image): 관리 화면 이미지가 같은 IP 익명 방문자의 버킷 소진으로 깨지지 않게 한다.
        // 면제는 이 규칙의 버킷을 쓰지 않는다는 뜻이며 다음 규칙으로 넘어가지 않는다. 익명 한도는 그대로다.
        if (matched.exemptAuthenticated() && isAuthenticatedUser()) {
            chain.doFilter(request, response);
            return;
        }

        RateLimitDecision decision = limiter.tryConsume(request.getRemoteAddr(), matched);
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        reject(request, response, matched, decision);
    }

    /** 로그인 사용자인가 — 2단계 중간 상태(비밀번호만 통과)는 아니다(PLAN-totp-two-factor.md 결정 3). */
    private static boolean isAuthenticatedUser() {
        return TotpAuthorities.isFullyAuthenticated(SecurityContextHolder.getContext().getAuthentication());
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

    private void reject(HttpServletRequest request, HttpServletResponse response, RateLimitRule rule, RateLimitDecision decision)
            throws IOException {
        // 분기 순서가 계약이다: API(JSON 429) → 로그인 입구(302) → 그 밖(429 화면). API를 먼저 봐야 플래그가 API 규칙에 잘못 붙어도
        // fetch 호출자가 HTML 리다이렉트를 받지 않는다(PLAN-login-rate-limit-notice.md 결정 3, F6).
        if (API_MATCHER.matches(request)) {
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            ApiErrorResponse body = ApiErrorResponse.of(
                    request.getRequestURI(), "RATE_LIMITED", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.");
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(MAPPER.writeValueAsString(body));
        } else if (rule.loginRedirect()) {
            // 로그인 입구(비밀번호·패스키) — 공용 429 화면 대신 로그인 화면 안내(이슈 #288, 결정 1). Retry-After는 붙이지 않는다:
            // 3xx에서는 "리다이렉트를 따르기 전 대기"라는 다른 뜻이다(RFC 9110 §10.2.3). 인증 처리 필터보다 앞이라 인증 시도는 없다.
            response.sendRedirect(request.getContextPath() + LOGIN_RATE_LIMITED_PATH);
        } else {
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            // sendError + 필터 종료 → 컨테이너 ERROR 재디스패치 → CustomErrorController → error/429.html.
            response.sendError(HttpStatus.TOO_MANY_REQUESTS.value());
        }
    }
}
