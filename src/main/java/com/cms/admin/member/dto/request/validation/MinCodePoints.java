package com.cms.admin.member.dto.request.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * 유니코드 코드포인트 수 기준 최소 길이를 제한하는 Bean Validation 제약 어노테이션.
 * {@code @Size}는 UTF-16 코드 단위(서로게이트 쌍을 2로 카운트)를 기준으로 삼는 반면,
 * 이 어노테이션은 NIST SP 800-63B의 카운팅 규칙("Each Unicode code point SHALL be
 * counted as a single character")과 일치하는 코드포인트 단위로 센다.
 * 위반 시 GlobalApiExceptionHandler의 handleValidation → 400 VALIDATION_ERROR로 응답한다.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = MinCodePointsValidator.class)
public @interface MinCodePoints {

    /** 요구할 최소 코드포인트 수 */
    int value();

    String message() default "길이가 너무 짧습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
