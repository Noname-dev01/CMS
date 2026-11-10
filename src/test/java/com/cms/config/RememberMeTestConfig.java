package com.cms.config;

import com.cms.config.auth.AdminRememberMeServices;
import com.cms.config.auth.LoginVisitRecorder;
import com.cms.config.auth.RememberMeKey;
import com.cms.config.auth.RememberMeTokenRepository;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * {@code SecurityConfig}를 가져오는 슬라이스 시험용 remember-me 의존 빈(이슈 #222). 슬라이스는 쿠키 자동 로그인 경로를 타지 않으므로
 * 서비스·저장소·방문 기록은 mock이면 충분하다({@code autoLogin}이 {@code null}을 돌려 익명으로 계속한다).
 * 실제 동작은 {@code RememberMeLoginIntegrationTest}가 본다.
 */
@TestConfiguration
public class RememberMeTestConfig {

    @Bean
    public RememberMeKey rememberMeKey() {
        return new RememberMeKey("slice-test-remember-me-key");
    }

    @Bean
    public RememberMeTokenRepository rememberMeTokenRepository() {
        return Mockito.mock(RememberMeTokenRepository.class);
    }

    @Bean
    public AdminRememberMeServices adminRememberMeServices() {
        return Mockito.mock(AdminRememberMeServices.class);
    }

    @Bean
    public LoginVisitRecorder loginVisitRecorder() {
        return Mockito.mock(LoginVisitRecorder.class);
    }
}
