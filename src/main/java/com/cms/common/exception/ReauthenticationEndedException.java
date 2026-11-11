package com.cms.common.exception;

/**
 * 재인증 중 세션을 끝냈다(계정 잠금·비활성·세션 폐기 경합·인증 불일치) — 다시 로그인해야 한다. 401 {@code UNAUTHORIZED}
 * (PLAN-sensitive-reauthentication.md 쟁점 6). 던지기 전에 호출자가 세션을 무효화하고 보안 컨텍스트를 비운다.
 */
public class ReauthenticationEndedException extends RuntimeException {

    public static final String MESSAGE = "다시 로그인해 주세요.";

    public ReauthenticationEndedException() {
        super(MESSAGE);
    }
}
