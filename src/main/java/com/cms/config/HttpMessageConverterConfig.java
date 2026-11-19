package com.cms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.cbor.JacksonCborHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 메시지 변환기에서 CBOR를 뺀다(PLAN-passkey-second-factor.md 구현 중 발견).
 *
 * <p>패스키 검증 라이브러리(webauthn4j)가 {@code jackson-dataformat-cbor}를 전이로 끌어오면 Spring MVC가 클래스패스를 보고
 * {@link JacksonCborHttpMessageConverter}를 기본 변환기에 더한다. 그러면 모든 {@code @RequestBody} API가 {@code application/cbor}
 * 본문도 받게 되는데, 그 매퍼는 앱의 JSON 설정(입력 계약)을 거치지 않은 별도 매퍼다. 앱은 CBOR를 쓰지 않으므로 기본 목록에서 지운다.
 * 회귀는 {@code PasskeyModuleWiringIntegrationTest}가 고정한다.
 */
@Configuration
public class HttpMessageConverterConfig implements WebMvcConfigurer {

    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        builder.configureMessageConvertersList(converters ->
                converters.removeIf(JacksonCborHttpMessageConverter.class::isInstance));
    }
}
