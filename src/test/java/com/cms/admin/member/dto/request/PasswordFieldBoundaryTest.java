package com.cms.admin.member.dto.request;

import com.cms.admin.member.AdminBootstrapCredentials;
import com.cms.admin.member.domain.Role;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 비밀번호 정책(@MinCodePoints(15) + @MaxUtf8Bytes(72))이 4개 진입점 DTO에
 * 동일하게 적용되는지 검증하는 경계 매트릭스. Spring 컨텍스트 없이 Bean Validation
 * Validator를 직접 사용한다(PLAN-password-policy-unification.md 쟁점 9).
 */
class PasswordFieldBoundaryTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static final List<Function<String, Object>> PASSWORD_DTO_FACTORIES = List.of(
            pwd -> AdminSignupRequest.builder()
                    .userId("validUser")
                    .pwd(pwd)
                    .userName("Valid Name")
                    .email("valid@test.com")
                    .userType(Role.ROLE_ADMIN)
                    .build(),
            pwd -> AdminMyPasswordChangeRequest.builder()
                    .currentPassword("CurrentPassword123!!!")
                    .newPassword(pwd)
                    .confirmPassword(pwd)
                    .build(),
            pwd -> PasswordResetConfirmRequest.builder()
                    .token("a".repeat(64))
                    .newPassword(pwd)
                    .confirmPassword(pwd)
                    .build(),
            pwd -> new AdminBootstrapCredentials("bootUser", pwd, "boot@test.com")
    );

    private void assertAllInvalid(String password, String label) {
        for (Function<String, Object> factory : PASSWORD_DTO_FACTORIES) {
            Object dto = factory.apply(password);
            Set<ConstraintViolation<Object>> violations = VALIDATOR.validate(dto);
            assertFalse(violations.isEmpty(),
                    label + " — " + dto.getClass().getSimpleName() + "에서 위반이 발생해야 한다");
        }
    }

    private void assertAllValid(String password, String label) {
        for (Function<String, Object> factory : PASSWORD_DTO_FACTORIES) {
            Object dto = factory.apply(password);
            Set<ConstraintViolation<Object>> violations = VALIDATOR.validate(dto);
            assertTrue(violations.isEmpty(),
                    label + " — " + dto.getClass().getSimpleName() + "에서 위반 없이 통과해야 한다: " + violations);
        }
    }

    @Test
    @DisplayName("14코드포인트는 4개 진입점 모두에서 무효")
    void fourteenCodePoints_invalidEverywhere() {
        assertAllInvalid("a".repeat(14), "14코드포인트");
    }

    @Test
    @DisplayName("15코드포인트는 4개 진입점 모두에서 유효")
    void fifteenCodePoints_validEverywhere() {
        assertAllValid("a".repeat(15), "15코드포인트");
    }

    @Test
    @DisplayName("ASCII 72바이트는 4개 진입점 모두에서 유효")
    void ascii72Bytes_validEverywhere() {
        assertAllValid("a".repeat(72), "ASCII 72바이트");
    }

    @Test
    @DisplayName("ASCII 73바이트는 4개 진입점 모두에서 무효")
    void ascii73Bytes_invalidEverywhere() {
        assertAllInvalid("a".repeat(73), "ASCII 73바이트");
    }

    @Test
    @DisplayName("한글 24자(72바이트)는 4개 진입점 모두에서 유효")
    void korean24Chars_72Bytes_validEverywhere() {
        assertAllValid("가".repeat(24), "한글 24자(72바이트)");
    }

    @Test
    @DisplayName("한글 25자(75바이트)는 4개 진입점 모두에서 무효")
    void korean25Chars_75Bytes_invalidEverywhere() {
        assertAllInvalid("가".repeat(25), "한글 25자(75바이트)");
    }

    @Test
    @DisplayName("서로게이트 쌍 문자(이모지) 15코드포인트는 4개 진입점 모두에서 유효 — @Size와 달리 코드포인트로 카운트됨을 증명")
    void surrogatePairs_fifteenCodePoints_validEverywhere() {
        // 😀(U+1F600)은 UTF-16에서 서로게이트 쌍(length()=2)이지만 코드포인트는 1개, UTF-8로는 4바이트.
        // 15개 반복 시 codePointCount=15(경계 통과), length()=30(만약 @Size(min=15)였다면 그냥 통과해버려
        // 코드포인트 카운팅과 차이가 드러나지 않으므로, 이 케이스는 "정확히 15코드포인트"가 핵심), 바이트=60(72 이하).
        String emoji15 = "😀".repeat(15);
        assertAllValid(emoji15, "서로게이트 쌍 15코드포인트");
    }

    @Test
    @DisplayName("고립 서로게이트는 코드포인트·길이 조건을 만족해도 4개 진입점 모두에서 무효")
    void loneSurrogates_invalidEverywhere() {
        // 고립 서로게이트(짝 없는 하이 서로게이트)는 codePointCount()가 각각을 코드포인트 1개로 세므로
        // 길이 조건(15 이상)은 만족하지만, UTF-8 인코딩이 불가능해 @MaxUtf8Bytes가 거부해야 한다
        // (실측: 치환 인코딩 시 서로 다른 원문이 같은 바이트열이 되는 문제 — 쟁점 2-2).
        String loneSurrogates = "\uD800".repeat(20);
        assertAllInvalid(loneSurrogates, "고립 서로게이트 20개");
    }

    @Test
    @DisplayName("1자 비밀번호는 4개 진입점 모두에서 무효")
    void oneCharacter_invalidEverywhere() {
        assertAllInvalid("a", "1자");
    }
}
