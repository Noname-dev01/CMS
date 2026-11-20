package com.cms.config.auth;

import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 코드 화면의 패스키 어설션 대기(세션 보관 — PLAN-passkey-second-factor.md 결정 4). 세션에 하나뿐이고 같은 회원의 탭들이 나눠 쓴다
 * (PLAN-passkey-challenge-stale-tab.md, 이슈 #293): 코드 화면 GET은 같은 회원이고 남은 수명이 {@link #REUSE_MIN_REMAINING} 이상이면 재사용한다.
 * 제출은 회원·{@code assertionId}가 맞으면 검증 전에 꺼내 지우고(1회용), 만료·다른 회원이면 지우고, {@code assertionId}만 다르면 남긴다.
 * {@code assertionId}는 화면의 패스키 폼에도 실린다 — 다른 화면의 정상 서명이 오답으로 쌓이지 않게 폼 값과 다르면 검증 전에 만료로 처리한다.
 */
public record PasskeyAssertionState(String assertionId, Long memberId, PublicKeyCredentialRequestOptions options, LocalDateTime issuedAt) {

    /** 옵션 타임아웃(라이브러리 기본 5분)과 같다 */
    public static final Duration TTL = Duration.ofMinutes(5);

    /** 화면 GET이 재사용하는 최소 남은 수명 — TTL의 절반 */
    public static final Duration REUSE_MIN_REMAINING = TTL.dividedBy(2);

    public static final String SESSION_ATTRIBUTE = PasskeyAssertionState.class.getName();

    /** 제출 판정 — 회원·식별자 일치 그리고 만료 아님(정확히 5분은 유효) */
    public boolean matches(Long memberId, String assertionId, LocalDateTime now) {
        return Objects.equals(this.memberId, memberId)
                && Objects.equals(this.assertionId, assertionId)
                && !isExpired(now);
    }

    public boolean isExpired(LocalDateTime now) {
        return issuedAt.plus(TTL).isBefore(now);
    }

    /** 화면 GET의 재사용 판정 — 같은 회원이고 남은 수명이 하한 이상 */
    public boolean isReusableFor(Long memberId, LocalDateTime now) {
        return Objects.equals(this.memberId, memberId) && !issuedAt.plus(TTL).minus(REUSE_MIN_REMAINING).isBefore(now);
    }
}
