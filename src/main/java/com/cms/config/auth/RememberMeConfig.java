package com.cms.config.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.session.SessionRegistry;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

/** "로그인 상태 유지"(remember-me 영속 토큰) 구성 요소 — 이슈 #222, PLAN-remember-me-login.md 쟁점 5. */
@Configuration
public class RememberMeConfig {

    @Bean
    public RememberMeKey rememberMeKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new RememberMeKey(Base64.getEncoder().encodeToString(bytes));
    }

    @Bean
    public RememberMeTokenRepository rememberMeTokenRepository(DataSource dataSource) {
        RememberMeTokenRepository repository = new RememberMeTokenRepository();
        repository.setDataSource(dataSource);
        return repository;
    }

    @Bean
    public AdminRememberMeServices adminRememberMeServices(RememberMeKey key, CustomUserDetailsService userDetailsService,
                                                           RememberMeTokenRepository tokenRepository, AdminSessionService sessions,
                                                           RememberMeDeviceRevoker deviceRevoker, SessionRegistry sessionRegistry,
                                                           Clock clock) {
        return new AdminRememberMeServices(key.value(), userDetailsService, tokenRepository, sessions, deviceRevoker,
                sessionRegistry, clock);
    }
}
