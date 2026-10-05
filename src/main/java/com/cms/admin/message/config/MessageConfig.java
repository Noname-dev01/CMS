package com.cms.admin.message.config;

import com.cms.admin.message.service.MessageRateLimiter;
import com.cms.config.ratelimit.Ticker;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 쪽지 설정·제한기 Bean 배선. {@code CmsApplication}이 {@code @ConfigurationPropertiesScan}을 쓰지 않으므로
 * {@link MessageProperties}는 {@code @EnableConfigurationProperties}로 명시 등록한다.
 * {@link Ticker}는 공개 경로 제한과 같은 단조 시계 Bean을 쓴다(제한기 자체는 분리 — {@code cms.rate-limit.enabled}와 무관).
 */
@Configuration
@EnableConfigurationProperties(MessageProperties.class)
public class MessageConfig {

    @Bean
    public MessageRateLimiter messageRateLimiter(MessageProperties properties, Ticker ticker) {
        return new MessageRateLimiter(properties, ticker);
    }
}
