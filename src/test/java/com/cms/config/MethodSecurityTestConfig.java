package com.cms.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.annotation.AnnotationTemplateExpressionDefaults;

/**
 * 메서드 보안 슬라이스 테스트 공용 설정. 운영 {@code MethodSecurityConfig}와 같은 템플릿 빈(@RequirePermission의 {feature}·{action} 치환)과
 * 고정 시드 권한 판정기({@link PermissionTestConfig})를 함께 제공한다.
 */
@TestConfiguration
@EnableMethodSecurity
@Import(PermissionTestConfig.class)
public class MethodSecurityTestConfig {

    @Bean
    static AnnotationTemplateExpressionDefaults templateExpressionDefaults() {
        return new AnnotationTemplateExpressionDefaults();
    }
}
