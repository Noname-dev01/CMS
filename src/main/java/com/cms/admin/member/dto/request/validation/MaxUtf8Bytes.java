package com.cms.admin.member.dto.request.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * UTF-8 인코딩 기준 최대 바이트 수를 제한하는 Bean Validation 제약 어노테이션.
 * 문자 수(코드 단위)가 아니라 실제 인코딩 바이트 수를 기준으로 판정한다(예: BCrypt의
 * 72바이트 물리적 제약). 잘못된 형식의 UTF-16(고립 서로게이트 등)도 무효로 처리한다
 * (MaxUtf8BytesValidator 참조).
 * 위반 시 GlobalApiExceptionHandler의 handleValidation → 400 VALIDATION_ERROR로 응답한다.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
public @interface MaxUtf8Bytes {

    /** 허용할 최대 UTF-8 바이트 수 */
    int value();

    String message() default "허용된 바이트 수를 초과했습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
