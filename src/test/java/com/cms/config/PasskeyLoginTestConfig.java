package com.cms.config;

import com.cms.admin.member.passkey.PasskeyJson;
import com.cms.common.time.AppTimeZone;
import com.cms.config.auth.CustomUserDetailsService;
import com.cms.config.auth.PasskeyLoginService;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * {@code SecurityConfig}를 직접 올리는 슬라이스 시험용 — 패스키 단독 로그인 필터(PLAN-passkey-login.md 결정 1)의 의존 빈을 목으로 둔다.
 * 목의 {@code isEnabled()}는 false라 필터가 매칭하지 않는다(기능 꺼짐과 같다). 로그인 화면 컨트롤러를 올리는 시험은 {@link PasskeyJson}도 쓴다.
 */
@TestConfiguration
public class PasskeyLoginTestConfig {

    @Bean
    public PasskeyLoginService passkeyLoginService() {
        return Mockito.mock(PasskeyLoginService.class);
    }

    @Bean
    public CustomUserDetailsService customUserDetailsService() {
        return Mockito.mock(CustomUserDetailsService.class);
    }

    /** 필터가 챌린지 만료 판정에 쓰는 시계 — 이 슬라이스들은 {@code AppConfig}를 올리지 않는다 */
    @Bean
    public Clock clock() {
        return Clock.system(AppTimeZone.KST);
    }

    @Bean
    public PasskeyJson passkeyJson() {
        return new PasskeyJson();
    }
}
