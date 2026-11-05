package com.cms.common.exception;

/**
 * 새 비밀번호 거부(400 {@code PASSWORD_REJECTED}). 재설정 화면이 무효 토큰과 구분해 입력을 다시 받도록 별도 코드를 쓴다
 * (PLAN-password-expiry-retirement-breach-check.md D5). 메시지는 입력값을 담지 않는 상수다 — 감사 FAIL 행에 그대로 저장된다.
 */
public class PasswordRejectedException extends InvalidRequestException {

    private PasswordRejectedException(String message) {
        super(message);
    }

    public static PasswordRejectedException breached() {
        return new PasswordRejectedException("유출된 적이 있는 비밀번호입니다. 다른 비밀번호를 입력해 주세요.");
    }

    public static PasswordRejectedException sameAsCurrent() {
        return new PasswordRejectedException("현재 비밀번호와 다른 비밀번호를 입력해 주세요.");
    }
}
