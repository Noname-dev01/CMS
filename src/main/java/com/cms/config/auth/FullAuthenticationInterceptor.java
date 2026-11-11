package com.cms.config.auth;

import com.cms.common.exception.ReauthenticationRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@link RequireFullAuthentication}이 붙은 핸들러 앞에서 인증 출처를 본다 — 완전 인증(비밀번호 폼 로그인)이 아니면
 * {@link ReauthenticationRequiredException}(403 {@code REAUTHENTICATION_REQUIRED}).
 *
 * <p>위치: 보안 필터(URL 게이트) 뒤, 핸들러 호출(본문 바인딩·{@code @Valid}·메서드 보안) 앞. 그래서 역할이 부족하면 기존 {@code ACCESS_DENIED}가
 * 먼저이고, 쿠키 세션은 본문 검증·서비스 실행 전에 거부돼 상태가 바뀌지 않는다. 인증 없음·익명·remember-me 전부 거부한다(fail-closed —
 * {@link AuthenticationTrustResolver#isFullyAuthenticated}). 쿠키 세션은 끝까지 remember-me 인증이다({@code AdminSecurityService.refreshAuthentication}이
 * 타입을 보존) — 같은 브라우저에서 폼 로그인하면 완전 인증 세션이 된다(PLAN-sensitive-reauthentication.md 쟁점 1·2).
 */
@Slf4j
public class FullAuthenticationInterceptor implements HandlerInterceptor {

    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !method.hasMethodAnnotation(RequireFullAuthentication.class)) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (trustResolver.isFullyAuthenticated(authentication)) {
            return true;
        }
        // 탈취 쿠키 사용 추적용 — 정상 사용자도 겪을 수 있어 INFO. 사용자 입력(본문·경로 값)은 남기지 않는다.
        log.info("재인증 필요로 민감 작업 거부: user={}, method={}, handler={}#{}",
                authentication == null ? null : authentication.getName(), request.getMethod(),
                method.getBeanType().getSimpleName(), method.getMethod().getName());
        throw new ReauthenticationRequiredException();
    }
}
