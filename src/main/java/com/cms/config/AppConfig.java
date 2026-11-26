package com.cms.config;

import com.cms.common.time.AppTimeZone;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AppConfig {

    /**
     * 앱의 시각 단일 원천 Clock을 등록한다(통계 날짜 경계·저장 시각·조회 기본 기간 공통).
     * Asia/Seoul 기준으로 고정해 테스트에서 고정 시각으로 교체 가능하게 한다.
     */
    @Bean
    public Clock clock() {
        return Clock.system(AppTimeZone.KST);
    }
}
