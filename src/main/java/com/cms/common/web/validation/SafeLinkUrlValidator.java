package com.cms.common.web.validation;

import com.cms.common.web.SafeUrls;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link SafeLinkUrl}의 검증 로직 — null·공백만 있는 값은 통과시키고 길이는 {@code @Size}가 담당한다. */
public class SafeLinkUrlValidator implements ConstraintValidator<SafeLinkUrl, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return SafeUrls.isSafeMenuUrl(value);
    }
}
