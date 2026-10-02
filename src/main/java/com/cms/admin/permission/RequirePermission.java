package com.cms.admin.permission;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 핸들러가 요구하는 (기능, 동작). 메타 {@link PreAuthorize}가 {@link AdminPermissionEvaluator#check}를 호출한다
 * ({@code AnnotationTemplateExpressionDefaults}가 {feature}·{action}을 속성값으로 치환 — MethodSecurityConfig).
 * 위임 가능 기능(DELEGABLE)의 핸들러에만 붙인다. 페이지 컨트롤러에는 붙이지 않는다 — 페이지 차단은 URL 게이트(필터)가 HTML 403으로 낸다.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("@adminPermission.check('{feature}', '{action}')")
public @interface RequirePermission {

    AdminFeature feature();

    PermissionAction action();
}
