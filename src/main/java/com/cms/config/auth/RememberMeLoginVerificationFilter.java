package com.cms.config.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 쿠키 자동 로그인의 사후 검증(I2, PLAN-remember-me-login.md 쟁점 2). {@code RememberMeAuthenticationFilter} 바로 뒤에 둔다 —
 * 그 필터는 세션 등록·컨텍스트 저장까지 끝낸 뒤 체인을 넘긴다(필터 성공 핸들러는 체인을 잇지 못해 쓰지 않는다).
 *
 * <p>이 요청이 방금 쿠키로 인증됐으면 그 시리즈가 아직 DB에 있는지 본다. 폐기 쪽은 항상 "삭제 → 세션 만료" 순서라,
 * 여기서 행을 봤다면 이후의 만료가 이미 등록된 이 세션을 찾고, 못 봤다면 여기서 거부한다. 거부는 세션 무효화·컨텍스트 초기화·쿠키 취소 후
 * 익명으로 체인을 계속한다(302/401은 인가 규칙이 정한다). 조회가 실패해도 먼저 같은 정리를 한다 — 남은 인증은 다음 요청에서
 * 세션 인증이라 이 검증을 다시 타지 않기 때문이다(fail-closed). 통과하면 세션 속성에 시리즈를 남기고 방문을 기록한다(U3).
 *
 * <p>빈이 아니다 — 서블릿 필터로 자동 등록되지 않도록 {@code SecurityConfig}가 직접 만든다.
 */
public class RememberMeLoginVerificationFilter extends OncePerRequestFilter {

    private final RememberMeTokenRepository tokens;
    private final AdminRememberMeServices rememberMeServices;
    private final LoginVisitRecorder visitRecorder;

    public RememberMeLoginVerificationFilter(RememberMeTokenRepository tokens, AdminRememberMeServices rememberMeServices,
                                             LoginVisitRecorder visitRecorder) {
        this.tokens = tokens;
        this.rememberMeServices = rememberMeServices;
        this.visitRecorder = visitRecorder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object marked = request.getAttribute(AdminRememberMeServices.AUTO_LOGIN_SERIES_ATTR);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (marked instanceof String series
                && auth instanceof RememberMeAuthenticationToken
                && auth.getPrincipal() instanceof CustomUserDetails details
                && series.equals(details.getRememberMeSeries())) {
            boolean alive;
            try {
                // 시리즈가 살아 있고, 그 회원이 탈취 폐기 대기(삭제 실패로 차단 중)가 아니어야 한다.
                alive = !rememberMeServices.isTheftRevocationPending(details.getUserId()) && tokens.existsSeries(series);
            } catch (RuntimeException e) {
                rememberMeServices.discardAuthentication(request, response);
                throw e;
            }
            if (!alive) {
                rememberMeServices.discardAuthentication(request, response);
            } else {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.setAttribute(AdminRememberMeServices.SESSION_SERIES_ATTR, series);
                }
                visitRecorder.record(request, auth);
            }
        }
        chain.doFilter(request, response);
    }
}
