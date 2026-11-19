package com.cms.admin.member.passkey;

import com.webauthn4j.verifier.exception.MaliciousCounterValueException;

/**
 * 서명 횟수 역행 판정(PLAN-passkey-login.md 결정 8). 두 곳에서 생긴다 — 등록 당시 횟수 이하면 라이브러리(webauthn4j 0.31.6
 * {@code AuthenticationDataVerifier} → {@link MaliciousCounterValueException})가 먼저, 저장된 최신 횟수 이하면 어댑터
 * ({@link PasskeySignatureCountException})가. 둘 다 서명 검증 뒤라 잘못된 서명을 면제하지 않는다. 호출자는 거부하되 실패로 세지 않는다.
 */
public final class PasskeyCounterRegression {

    private PasskeyCounterRegression() {
    }

    public static boolean matches(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof PasskeySignatureCountException || current instanceof MaliciousCounterValueException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }
}
