package com.cms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class AppConfig {

    /**
     * 통계 날짜 경계 계산에 사용할 Clock을 등록한다.
     * Asia/Seoul 기준으로 고정해 테스트에서 고정 시각으로 교체 가능하게 한다.
     */
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
