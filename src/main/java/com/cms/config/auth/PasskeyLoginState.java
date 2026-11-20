package com.cms.config.auth;

import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 로그인 화면의 패스키 로그인 챌린지(세션 보관 — PLAN-passkey-login.md 결정 2). 세션에 하나뿐이고 같은 세션의 탭들이 나눠 쓴다
 * (PLAN-passkey-challenge-stale-tab.md, 이슈 #293): 로그인 화면 GET은 남은 수명이 {@link #REUSE_MIN_REMAINING} 이상이면 재사용하고
 * 아니면 새로 발급한다. 제출은 {@code loginId}가 맞으면 검증 전에 꺼내 지우고(1회용), 만료면 지우고, 그 밖의 불일치는 남긴다.
 * {@code loginId}는 화면의 패스키 폼에도 실린다 — 세션 챌린지와 다른 화면의 제출은 검증 전에 만료로 끝난다.
 */
public record PasskeyLoginState(String loginId, PublicKeyCredentialRequestOptions options, LocalDateTime issuedAt) {

    /** 옵션 타임아웃(5분)과 같다 */
    public static final Duration TTL = Duration.ofMinutes(5);

    /** 화면 GET이 재사용하는 최소 남은 수명 — 막 연 화면이 곧바로 만료 안내가 되지 않게 TTL의 절반 */
    public static final Duration REUSE_MIN_REMAINING = TTL.dividedBy(2);

    public static final String SESSION_ATTRIBUTE = PasskeyLoginState.class.getName();

    /** 제출 판정 — 식별자 일치 그리고 만료 아님(정확히 5분은 유효) */
    public boolean matches(String loginId, LocalDateTime now) {
        return Objects.equals(this.loginId, loginId) && !isExpired(now);
    }

    public boolean isExpired(LocalDateTime now) {
        return issuedAt.plus(TTL).isBefore(now);
    }

    /** 화면 GET의 재사용 판정 — 남은 수명이 하한 이상 */
    public boolean isReusable(LocalDateTime now) {
        return !issuedAt.plus(TTL).minus(REUSE_MIN_REMAINING).isBefore(now);
    }
}
