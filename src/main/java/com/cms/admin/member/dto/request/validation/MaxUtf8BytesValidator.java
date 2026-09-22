package com.cms.admin.member.dto.request.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * {@link MaxUtf8Bytes} 어노테이션의 실제 검증 로직.
 * null 값은 통과시키며, null 검증은 @NotBlank가 담당한다.
 *
 * <p>바이트 수 계산에 {@code String.getBytes(UTF_8)} 대신 {@link CharsetEncoder}를
 * {@code CodingErrorAction.REPORT} 모드로 사용한다 — 기본 인코딩은 고립 서로게이트 등
 * 잘못된 형식의 UTF-16을 조용히 치환 문자로 바꿔버려, 서로 다른 원문이 같은 바이트열로
 * 인코딩되고 결과적으로 같은 BCrypt 해시가 될 수 있다(실측으로 확인된 문제 —
 * PLAN-password-policy-unification.md 쟁점 2-2). REPORT 모드는 이런 입력을
 * {@link CharacterCodingException}으로 명확히 거부한다.
 *
 * <p>{@link CharsetEncoder}는 스레드 안전하지 않으므로 인스턴스 필드로 캐시하지 않고
 * {@link #isValid}를 호출할 때마다 새로 만든다(ConstraintValidator 인스턴스는 여러
 * 스레드에서 재사용될 수 있음).
 */
public class MaxUtf8BytesValidator implements ConstraintValidator<MaxUtf8Bytes, String> {

    private int max;

    @Override
    public void initialize(MaxUtf8Bytes constraintAnnotation) {
        this.max = constraintAnnotation.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        // 문자 수 단락 평가 — 모든 UTF-16 코드 단위는 UTF-8에서 최소 1바이트이므로
        // 문자 수가 이미 상한을 넘으면 바이트 수도 반드시 넘는다. 대용량 입력에서
        // 불필요한 인코딩 시도를 피한다.
        if (value.length() > max) {
            return false;
        }

        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            int encodedLength = encoder.encode(CharBuffer.wrap(value)).remaining();
            return encodedLength <= max;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
