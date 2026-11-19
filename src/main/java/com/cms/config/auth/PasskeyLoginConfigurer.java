package com.cms.config.auth;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;

import java.time.Clock;

/**
 * 패스키 단독 로그인 필터 구성(PLAN-passkey-login.md 결정 1). {@code configure} 시점에 {@code SessionManagementConfigurer.init}이 공유 객체로
 * 등록한 세션 전략(동시 세션 제어·세션 ID 교체·레지스트리 등록)과 컨텍스트 저장소를 받아 폼 로그인과 같은 계약으로 처리하게 한다
 * (Spring Security 7.0.7 — 폼 로그인의 {@code AbstractAuthenticationFilterConfigurer.configure}와 같은 공유 객체).
 */
public class PasskeyLoginConfigurer extends AbstractHttpConfigurer<PasskeyLoginConfigurer, HttpSecurity> {

    /** 챌린지 만료 안내 */
    static final String EXPIRED_URL = "/admin/login?passkeyExpired";

    private final PasskeyLoginService passkeyLoginService;
    private final UserDetailsService userDetailsService;
    private final AuthenticationSuccessHandler successHandler;
    private final Clock clock;

    public PasskeyLoginConfigurer(PasskeyLoginService passkeyLoginService, UserDetailsService userDetailsService,
                                  AuthenticationSuccessHandler successHandler, Clock clock) {
        this.passkeyLoginService = passkeyLoginService;
        this.userDetailsService = userDetailsService;
        this.successHandler = successHandler;
        this.clock = clock;
    }

    @Override
    public void configure(HttpSecurity http) {
        PasskeyLoginFilter filter = new PasskeyLoginFilter(passkeyLoginService, userDetailsService, clock);
        filter.setSessionAuthenticationStrategy(http.getSharedObject(SessionAuthenticationStrategy.class));
        filter.setSecurityContextRepository(http.getSharedObject(SecurityContextRepository.class));
        filter.setAuthenticationSuccessHandler(successHandler);
        filter.setAuthenticationFailureHandler(failureHandler());
        http.addFilterAfter(postProcess(filter), UsernamePasswordAuthenticationFilter.class);
    }

    /** 실패 안내는 둘뿐이다 — 만료와 그 밖 전부(계정 상태를 나누지 않는다, 결정 5). 실패 횟수는 세지 않는다. */
    static AuthenticationFailureHandler failureHandler() {
        RedirectStrategy redirect = new DefaultRedirectStrategy();
        return (request, response, exception) -> redirect.sendRedirect(request, response,
                exception instanceof PasskeyLoginFilter.PasskeyLoginExpiredException
                        ? EXPIRED_URL : VisitLoggingAuthenticationSuccessHandler.PASSKEY_LOGIN_ERROR);
    }
}
