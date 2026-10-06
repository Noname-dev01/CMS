package com.cms.admin.menu.dto.request.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * 메뉴 URL로 쓸 수 있는 값(같은 출처 경로 또는 외부 http(s) 주소)만 받는 Bean Validation 제약 어노테이션.
 * null·빈 문자열은 통과시킨다 — null 의미는 호출 측이 정하고, 빈 문자열은 서비스가 null로 정규화한다.
 * 위반 시 GlobalApiExceptionHandler의 handleValidation → 400 VALIDATION_ERROR로 응답한다.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = SafeMenuUrlValidator.class)
public @interface SafeMenuUrl {

    String message() default "메뉴 URL은 /로 시작하는 경로이거나 http(s):// 주소여야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
