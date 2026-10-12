package com.cms.publicweb.inquiry;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 공개 문의 설정 배선. {@code CmsApplication}이 {@code @ConfigurationPropertiesScan}을 쓰지 않으므로 {@link InquiryTurnstileProperties}를 명시 등록하고,
 * 교차 검증 Bean({@link InquiryTurnstileConfigValidator})을 등록한다(Bean이 아니면 검증이 실행되지 않는다).
 */
@Configuration
@EnableConfigurationProperties(InquiryTurnstileProperties.class)
public class InquiryConfig {

    @Bean
    public InquiryTurnstileConfigValidator inquiryTurnstileConfigValidator(InquiryTurnstileProperties properties, Environment environment) {
        return new InquiryTurnstileConfigValidator(properties, environment);
    }
}
