package com.cms.config.auth;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 패스키 단독 로그인(비밀번호·2단계 생략)으로 만든 완전 권한 토큰(PLAN-passkey-login.md 결정 4). 권한은 principal의 원래 역할이다.
 *
 * <p>성공 처리기는 이 종류의 토큰을 "2단계 사용 중인 회원의 완전 권한"으로 인정하되, 검증 때 읽은 TOTP 등록 시각
 * ({@link #getTotpEnabledAt()})이 지금 등록 시각과 같을 때만 받는다 — 로그인 진행 중 TOTP 해제(또는 해제 → 재등록)가 커밋된 경합을 거부한다.
 */
public class PasskeyLoginAuthenticationToken extends AbstractAuthenticationToken {

    private final CustomUserDetails principal;
    private final LocalDateTime totpEnabledAt;

    public PasskeyLoginAuthenticationToken(CustomUserDetails principal, LocalDateTime totpEnabledAt) {
        super(principal.getAuthorities());
        this.principal = principal;
        this.totpEnabledAt = Objects.requireNonNull(totpEnabledAt, "totpEnabledAt");
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public CustomUserDetails getPrincipal() {
        return principal;
    }

    public LocalDateTime getTotpEnabledAt() {
        return totpEnabledAt;
    }
}
