package com.cms.admin.member.dto.request.validation;

import com.cms.admin.member.domain.Role;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * {@link AllowedRoles} 어노테이션의 실제 검증 로직.
 * null 값은 통과시키며, null 검증은 @NotNull이 담당한다.
 */
public class AllowedRolesValidator implements ConstraintValidator<AllowedRoles, Role> {

    private Set<Role> allowedRoles;

    @Override
    public void initialize(AllowedRoles constraintAnnotation) {
        allowedRoles = EnumSet.copyOf(Arrays.asList(constraintAnnotation.allowed()));
    }

    @Override
    public boolean isValid(Role value, ConstraintValidatorContext context) {
        // null 검증은 @NotNull에 위임
        if (value == null) {
            return true;
        }
        return allowedRoles.contains(value);
    }
}
