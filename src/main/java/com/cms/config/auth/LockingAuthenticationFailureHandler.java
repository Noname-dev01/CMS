package com.cms.config.auth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 로그인 실패 핸들러 — 비밀번호 불일치(BadCredentials)만 실패 카운트로 기록한다.
 *
 * <p>BadCredentialsException 이외의 모든 인증 실패는 카운트하지 않는다.
 * 상태 기반 거부(LockedException/DisabledException 등)는 DaoAuthenticationProvider가
 * InternalAuthenticationServiceException으로 래핑해 도달하지만, 화이트리스트 조건이라 무관하다.
 * (기본 설정상 UsernameNotFoundException은 BadCredentials로 변환되어 도달 — 미존재 계정은
 * 카운트 쿼리가 0행이라 무동작.)
 *
 * <p>카운트 기록 실패가 로그인 에러 페이지 리다이렉트를 막으면 안 되므로 try-catch로 격리한다.
 */
@Slf4j
@Component
public class LockingAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    /** admin_action_log.request_ip 컬럼 길이 */
    private static final int MAX_IP_LENGTH = 45;
    /** admin_action_log.request_uri 컬럼 길이 */
    private static final int MAX_URI_LENGTH = 255;

    private final LoginFailureService loginFailureService;

    public LockingAuthenticationFailureHandler(LoginFailureService loginFailureService) {
        this.loginFailureService = loginFailureService;
        // 기존 SecurityConfig의 failureUrl("/admin/login-error") 동작 보존
        setDefaultFailureUrl("/admin/login-error");
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        if (exception instanceof BadCredentialsException) {
            String username = request.getParameter("username"); // login.html 폼 파라미터명
            if (username != null && !username.isBlank()) {
                tryRecordFailure(request, username);
            }
        }

        super.onAuthenticationFailure(request, response, exception);
    }

    private void tryRecordFailure(HttpServletRequest request, String username) {
        try {
            String ip = truncate(extractClientIp(request), MAX_IP_LENGTH);
            String uri = truncate(request.getRequestURI(), MAX_URI_LENGTH);
            loginFailureService.recordFailure(username, ip, uri);
        } catch (Exception e) {
            log.error("로그인 실패 카운트 기록 실패 (username={})", username, e);
        }
    }

    /**
     * IP를 추출한다. X-FORWARDED-FOR(마지막 홉) → X-Real-IP → RemoteAddr 순.
     * VisitLoggingAuthenticationSuccessHandler.extractClientIp()와 동일 로직(private라 직접 재사용 불가).
     */
    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-FORWARDED-FOR");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            String[] ips = xForwardedFor.split(",");
            return ips[ips.length - 1].trim();
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp;
        }

        return request.getRemoteAddr();
    }

    /**
     * 감사 컬럼 길이로 절단한다 — 조작된 헤더가 컬럼 초과 예외로 카운트 트랜잭션을 깨지 않도록.
     */
    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
