package com.cms.publicweb.inquiry;

/**
 * 캡차 토큰의 서버 측 검증. 구현은 <b>fail-closed</b>다 — 외부 서비스 오류·타임아웃·잘못된 응답은 모두 {@code false}이고 예외를 던지지 않는다.
 * 시험이 네트워크 없이 대체하기 위한 최소 이음매이며 다른 용도로 확장하지 않는다(PLAN-inquiry.md 쟁점 4).
 */
public interface CaptchaVerifier {

    /** @param remoteIp 방문자 IP(알 수 없으면 null) */
    boolean verify(String token, String remoteIp);
}
