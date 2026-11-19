package com.cms.config.auth;

import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 코드 화면의 패스키 어설션 대기(세션 보관 — PLAN-passkey-second-factor.md 결정 4). GET마다 대체되고, 제출 때 꺼내며 지운다(1회용).
 * {@code assertionId}는 화면의 패스키 폼에도 실린다 — 다른 탭의 GET이 챌린지를 바꾼 뒤 옛 탭의 정상 서명이 오답으로 쌓이지 않게
 * 폼 값과 다르면 검증 전에 만료로 처리한다(리뷰 2-1).
 */
public record PasskeyAssertionState(String assertionId, Long memberId, PublicKeyCredentialRequestOptions options, LocalDateTime issuedAt) {

    /** 옵션 타임아웃(라이브러리 기본 5분)과 같다 */
    public static final Duration TTL = Duration.ofMinutes(5);

    public static final String SESSION_ATTRIBUTE = PasskeyAssertionState.class.getName();

    public boolean matches(Long memberId, String assertionId, LocalDateTime now) {
        return Objects.equals(this.memberId, memberId)
                && Objects.equals(this.assertionId, assertionId)
                && !issuedAt.plus(TTL).isBefore(now);
    }
}
