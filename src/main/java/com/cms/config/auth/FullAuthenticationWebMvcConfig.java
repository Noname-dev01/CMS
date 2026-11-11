package com.cms.config.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 민감 작업 재인증 인터셉터 등록. 경로 패턴이 아니라 핸들러의 {@link RequireFullAuthentication} 표지로 대상을 정하므로 모든 경로에 건다
 * (표지 없는 핸들러·정적 리소스·오류 재디스패치는 그대로 통과한다).
 */
@Configuration
public class FullAuthenticationWebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new FullAuthenticationInterceptor());
    }
}
