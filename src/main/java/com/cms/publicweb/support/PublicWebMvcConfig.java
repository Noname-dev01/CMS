package com.cms.publicweb.support;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 공개 사이트 해석 인터셉터 등록. 적용 대상은 경로가 아니라 핸들러의 {@link SiteScopedPublic} 표지로 가른다. {@code WebMvcConfigurer}는
 * {@code @WebMvcTest} 슬라이스에도 포함되므로 서비스는 요청 시점에 지연 조회한다(서비스 빈이 없는 슬라이스에서 컨텍스트 기동이 깨지지 않게).
 */
@Configuration
@RequiredArgsConstructor
public class PublicWebMvcConfig implements WebMvcConfigurer {

    private final ObjectProvider<PublicSiteService> publicSiteService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PublicSiteInterceptor(publicSiteService));
    }
}
