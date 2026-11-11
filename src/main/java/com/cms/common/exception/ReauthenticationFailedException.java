package com.cms.common.exception;

/**
 * 재인증 비밀번호가 틀렸다 — 더 시도할 수 있다(폼 로그인과 같은 실패 카운터에 셌다). 400 {@code REAUTHENTICATION_FAILED}
 * (PLAN-sensitive-reauthentication.md 쟁점 6). 잠금·비활성·폐기로 세션이 끝나는 경우는 {@link ReauthenticationEndedException}.
 */
public class ReauthenticationFailedException extends RuntimeException {

    public static final String MESSAGE = "비밀번호가 올바르지 않습니다.";

    public ReauthenticationFailedException() {
        super(MESSAGE);
    }
}
