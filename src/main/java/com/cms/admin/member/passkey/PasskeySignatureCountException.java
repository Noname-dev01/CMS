package com.cms.admin.member.passkey;

/**
 * 패스키 서명 횟수가 저장된 최신 값보다 커지지 않았다 — 복제된 자격 의심(PLAN-passkey-login.md 결정 8).
 * 라이브러리(Spring Security 7.0.7)는 등록 당시 횟수와만 비교하므로 어댑터가 저장된 최신 값으로 다시 본다.
 * 정상 어설션 두 개의 도착 순서가 뒤바뀌어도 생기므로 호출자는 실패 횟수에 넣지 않는다.
 */
public class PasskeySignatureCountException extends RuntimeException {

    public PasskeySignatureCountException(String message) {
        super(message);
    }
}
