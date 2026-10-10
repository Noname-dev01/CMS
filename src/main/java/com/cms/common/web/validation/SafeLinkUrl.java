package com.cms.common.web.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 화면에 링크로 내보낼 URL(배너·팝업 클릭 대상)로 쓸 수 있는 값 — 같은 출처 경로 또는 외부 http(s) 주소 — 만 받는 제약.
 * null·공백은 통과시킨다(값 없음의 의미는 호출 측이 정하고 빈 문자열은 서비스가 null로 정규화한다). 규칙 원본은
 * {@link com.cms.common.web.SafeUrls}다. 위반 시 400 VALIDATION_ERROR.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = SafeLinkUrlValidator.class)
public @interface SafeLinkUrl {

    String message() default "링크는 /로 시작하는 경로이거나 http(s):// 주소여야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
