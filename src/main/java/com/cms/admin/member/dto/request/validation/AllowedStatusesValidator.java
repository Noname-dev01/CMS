package com.cms.admin.member.dto.request.validation;

import com.cms.admin.member.domain.MemberStatus;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * {@link AllowedStatuses} 어노테이션의 실제 검증 로직.
 * null 값은 통과시키며, null 검증은 @NotNull이 담당한다.
 */
public class AllowedStatusesValidator implements ConstraintValidator<AllowedStatuses, MemberStatus> {

    private Set<MemberStatus> allowedStatuses;

    @Override
    public void initialize(AllowedStatuses constraintAnnotation) {
        allowedStatuses = EnumSet.copyOf(Arrays.asList(constraintAnnotation.allowed()));
    }

    @Override
    public boolean isValid(MemberStatus value, ConstraintValidatorContext context) {
        // null 검증은 @NotNull에 위임 (PATCH의 optional 필드는 null 허용)
        if (value == null) {
            return true;
        }
        return allowedStatuses.contains(value);
    }
}
