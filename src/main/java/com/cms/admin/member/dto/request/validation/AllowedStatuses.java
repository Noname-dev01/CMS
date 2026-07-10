package com.cms.admin.member.dto.request.validation;

import com.cms.admin.member.domain.MemberStatus;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * 허용된 {@link MemberStatus} 값만 수신하도록 제한하는 Bean Validation 제약 어노테이션.
 * 위반 시 GlobalApiExceptionHandler의 handleValidation → 400 VALIDATION_ERROR로 응답한다.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = AllowedStatusesValidator.class)
public @interface AllowedStatuses {

    /** 허용할 MemberStatus 목록 */
    MemberStatus[] allowed();

    String message() default "허용되지 않는 status입니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
