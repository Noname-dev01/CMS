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
        // 형식만 본다(내부 경로 또는 외부 http(s)). 외부 허용 여부·비공개 경로 금지는 사이트 정책이라 서비스(SiteLinkPolicy)가 판정한다.
        return SafeUrls.isSafeLinkUrl(value);
    }
}
