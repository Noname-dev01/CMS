package com.cms.config.auth;

import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 로그인 화면의 패스키 로그인 챌린지(세션 보관 — PLAN-passkey-login.md 결정 2). 로그인 화면 GET마다 대체되고, 제출 때 꺼내며 지운다(1회용).
 * {@code loginId}는 화면의 패스키 폼에도 실린다 — 다른 탭의 GET이 챌린지를 바꾼 뒤 옛 탭의 제출을 검증 전에 만료로 끝낸다.
 */
public record PasskeyLoginState(String loginId, PublicKeyCredentialRequestOptions options, LocalDateTime issuedAt) {

    /** 옵션 타임아웃(5분)과 같다 */
    public static final Duration TTL = Duration.ofMinutes(5);

    public static final String SESSION_ATTRIBUTE = PasskeyLoginState.class.getName();

    public boolean matches(String loginId, LocalDateTime now) {
        return Objects.equals(this.loginId, loginId) && !issuedAt.plus(TTL).isBefore(now);
    }
}
