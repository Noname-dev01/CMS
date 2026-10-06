package com.cms.admin.menu.dto.request.validation;

import com.cms.common.web.SafeUrls;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link SafeMenuUrl} 어노테이션의 실제 검증 로직.
 * null·공백만 있는 값은 통과시키며, 길이는 @Size가 담당한다.
 */
public class SafeMenuUrlValidator implements ConstraintValidator<SafeMenuUrl, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return SafeUrls.isSafeMenuUrl(value);
    }
}
