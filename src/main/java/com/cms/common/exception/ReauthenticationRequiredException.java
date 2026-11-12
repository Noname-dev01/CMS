package com.cms.common.exception;

/**
 * 쿠키 자동 로그인(로그인 상태 유지)으로만 인증된 세션이 민감 작업을 시도했다 — 비밀번호로 다시 로그인해야 한다.
 * {@code GlobalApiExceptionHandler}가 403 {@code REAUTHENTICATION_REQUIRED}로 응답한다(PLAN-sensitive-reauthentication.md).
 */
public class ReauthenticationRequiredException extends RuntimeException {

    public static final String MESSAGE =
            "로그인 상태 유지로 접속한 세션은 이 작업 전에 비밀번호 확인이 필요합니다. 다시 시도하고 비밀번호를 입력해 주세요.";

    public ReauthenticationRequiredException() {
        super(MESSAGE);
    }
}
