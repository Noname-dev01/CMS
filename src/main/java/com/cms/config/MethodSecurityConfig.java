package com.cms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.annotation.AnnotationTemplateExpressionDefaults;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {

    /**
     * {@code @RequirePermission}의 메타 {@code @PreAuthorize("@adminPermission.check('{feature}', '{action}')")}가 쓰는
     * 템플릿 자리표시자({feature}·{action})를 어노테이션 속성값으로 치환한다. 인프라 빈이라 static으로 둔다.
     */
    @Bean
    static AnnotationTemplateExpressionDefaults templateExpressionDefaults() {
        return new AnnotationTemplateExpressionDefaults();
    }
}
