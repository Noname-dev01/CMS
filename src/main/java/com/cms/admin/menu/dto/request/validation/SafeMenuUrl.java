package com.cms.admin.menu.dto.request.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * 메뉴 URL의 <b>형식</b>(같은 출처 경로 또는 외부 http(s) 주소)만 검사하는 Bean Validation 제약 어노테이션.
 * 사이트별 정책(관리자 사이트는 내부 경로만, 공개 사이트는 외부 허용 여부·비공개 경로 금지)은 서비스의 {@code SiteLinkPolicy}가 판정한다(PLAN-site-menu.md D3).
 * null·빈 문자열은 통과시킨다 — null 의미는 호출 측이 정하고, 빈 문자열은 서비스가 null로 정규화한다.
 * 위반 시 GlobalApiExceptionHandler의 handleValidation → 400 VALIDATION_ERROR로 응답한다.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = SafeMenuUrlValidator.class)
public @interface SafeMenuUrl {

    String message() default "메뉴 URL은 /로 시작하는 내부 경로 또는 http(s) 주소여야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
