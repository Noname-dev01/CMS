package com.cms.admin.member.dto.request.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link MinCodePoints} 어노테이션의 실제 검증 로직.
 * null 값은 통과시키며, null 검증은 @NotBlank가 담당한다.
 *
 * <p>잘못된 형식의 UTF-16(고립 서로게이트)에 대한 방어는 이 검증기의 책임이 아니다 —
 * {@code codePointCount()}는 고립 서로게이트도 코드포인트 1개로 정상 카운트하지만,
 * 같은 필드에 함께 붙는 {@link MaxUtf8Bytes}가 그런 입력을 전부 거부하므로(Bean
 * Validation은 한 필드의 모든 제약을 평가해 하나라도 위반하면 전체가 무효) 이 검증기까지
 * 중복으로 방어할 필요가 없다(PLAN-password-policy-unification.md 쟁점 2-2).
 */
public class MinCodePointsValidator implements ConstraintValidator<MinCodePoints, String> {

    private int min;

    @Override
    public void initialize(MinCodePoints constraintAnnotation) {
        this.min = constraintAnnotation.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return value.codePointCount(0, value.length()) >= min;
    }
}
