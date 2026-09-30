package com.cms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class AppConfig {

    /** 앱 시간대(KST) 단일 정의. */
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 앱의 시각 단일 원천 Clock을 등록한다(통계 날짜 경계·저장 시각·조회 기본 기간 공통).
     * Asia/Seoul 기준으로 고정해 테스트에서 고정 시각으로 교체 가능하게 한다.
     */
    @Bean
    public Clock clock() {
        return Clock.system(KST);
    }
}
