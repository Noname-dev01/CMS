package com.cms.config.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 민감 작업 표지 — 쿠키 자동 로그인(remember-me)으로만 인증된 세션은 이 핸들러를 실행할 수 없고 비밀번호로 다시 로그인해야 한다.
 * 판정은 {@link FullAuthenticationInterceptor}가 한다. 역할 인가({@code @PreAuthorize})를 대신하지 않고 그와 함께 붙인다
 * ("누가"는 인가 선언, "어떻게 인증됐는가"는 이 표지). <b>메서드에만</b> 붙인다 — 클래스에 붙이면 같은 컨트롤러의 조회까지 막힌다.
 * 대상 목록은 {@code FullAuthenticationConventionTest}가 고정한다(PLAN-sensitive-reauthentication.md).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireFullAuthentication {
}
