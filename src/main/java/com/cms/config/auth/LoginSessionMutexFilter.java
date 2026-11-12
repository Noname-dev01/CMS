package com.cms.config.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import java.io.IOException;

/**
 * 세션이 이미 있는 폼 로그인({@code POST /admin/login})을 <b>그 세션의 뮤텍스 안에서</b> 처리한다
 * (PLAN-sensitive-reauthentication.md 쟁점 7에서 도입, PLAN-remember-me-removal.md 쟁점 2에서 독립 방어로 유지).
 *
 * <p>Spring Security 7.0.7은 폼 로그인의 세션 ID 교체만 세션 뮤텍스 안에서 하고, 레지스트리 등록({@code RegisterSessionAuthenticationStrategy} —
 * 그 시점의 세션 ID로 등록)·컨텍스트 저장·성공 처리기는 그 밖에서 한다(바이트코드 확인). 그래서 같은 세션의 두 탭이 <b>서로 다른 계정</b>으로
 * 동시에 로그인하면 등록과 저장이 서로 다른 순서로 끝나 "실제 인증 회원 ≠ 레지스트리 회원"인 세션이 남을 수 있다. 강제 만료(역할·상태 변경,
 * 비밀번호 변경·재설정, 자동 잠금, 세션 관리 화면)는 레지스트리에서 회원의 세션을 찾으므로 그런 세션은 실제 회원이 강등·비활성화돼도 끊기지 않는다 —
 * ADMIN은 두 번째 계정을 직접 만들 수 있어 혼자서도 이 상태를 만들 수 있다. 이 필터가 {@code UsernamePasswordAuthenticationFilter} 앞에서
 * 나머지 체인 전체를 같은 뮤텍스로 감싸 같은 세션의 로그인을 한 건씩 처리한다. {@code synchronized}는 재진입이라 Spring 고정 방지 전략의
 * 같은 뮤텍스 획득과 교착하지 않는다.
 *
 * <p>세션이 없는 로그인은 감싸지 않는다 — 겹칠 상대(같은 세션의 다른 요청)가 없다. 비용: 같은 세션의 로그인끼리 BCrypt·DB 시간 동안 줄을 선다
 * (다른 세션은 영향 없음). 빈이 아니다(서블릿 필터로 자동 등록되지 않게 — {@code SecurityConfig}가 체인에만 넣는다).
 */
public class LoginSessionMutexFilter extends OncePerRequestFilter {

    /** 폼 로그인 처리 URL과 같은 매처 규칙(PathPattern) — {@code SecurityConfig.formLogin().loginProcessingUrl}과 같은 경로. */
    static final RequestMatcher LOGIN_PROCESSING =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/admin/login");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = LOGIN_PROCESSING.matches(request) ? request.getSession(false) : null;
        if (session == null) {
            chain.doFilter(request, response);
            return;
        }
        synchronized (WebUtils.getSessionMutex(session)) {
            chain.doFilter(request, response);
        }
    }
}
