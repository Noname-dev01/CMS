package com.cms.admin.member.service;

import com.cms.common.exception.InvalidRequestException;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 관리자 계정 생성·수정·재설정·부트스트랩 전 경로가 공유하는 이메일 정규화 유틸.
 * 이전에는 {@code AdminMemberService}(기본 Locale)와 {@code PasswordResetService}
 * (Locale.ROOT)가 같은 로직을 서로 다른 Locale로 중복 구현했다 — 이 클래스로 통일한다.
 *
 * <p>{@code Locale.ROOT} 고정: 기본 Locale의 대소문자 규칙(터키어 I/i 등)에 의존하지
 * 않기 위함.
 */
public final class EmailNormalizer {

    /**
     * {@code Member.email} 컬럼 길이(Member.java)와 정확히 일치시킨다. 대소문자 변환이
     * 항상 1:1은 아니어서(예: İ(U+0130) → "i̇" 2자로 확장, 실측 확인) 정규화 전 DTO의
     * {@code @Size(max=100)}을 통과한 값도 정규화 후에는 이 길이를 넘을 수 있다.
     */
    private static final int MAX_EMAIL_LENGTH = 100;

    private EmailNormalizer() {
    }

    /**
     * 이메일을 trim + 소문자화(Locale.ROOT)로 정규화한다.
     *
     * @throws InvalidRequestException 정규화 결과가 컬럼 길이를 초과하거나, 잘못된 형식의
     *                                  UTF-16(고립 서로게이트 등 — {@code @Email}은 이를
     *                                  걸러내지 못함을 실측 확인)을 포함하는 경우
     */
    public static String normalize(String email) {
        if (email == null) {
            return null;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_EMAIL_LENGTH) {
            throw new InvalidRequestException("이메일이 너무 깁니다.");
        }
        requireWellFormedUtf16(normalized);
        return normalized;
    }

    /**
     * {@code MaxUtf8BytesValidator}와 동일한 {@link CharsetEncoder} 기법 — 고립
     * 서로게이트 등 잘못된 형식의 UTF-16은 기본 인코딩에서 치환 문자로 조용히 바뀌어
     * 서로 다른 원문이 같은 값으로 취급될 수 있다. REPORT 모드로 명확히 거부한다.
     */
    private static void requireWellFormedUtf16(String value) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            encoder.encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException e) {
            throw new InvalidRequestException("이메일 형식이 올바르지 않습니다.");
        }
    }
}
