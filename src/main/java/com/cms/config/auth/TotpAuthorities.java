package com.cms.config.auth;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * 2단계 인증 중간 상태 표지와 "로그인 사용자" 판정(PLAN-totp-two-factor.md 결정 1·3).
 *
 * <p>2단계를 켠 회원이 비밀번호만 통과하면 인증 권한은 {@link #PENDING} 하나뿐이다 — 역할 권한이 없어 모든 관리 게이트가
 * 거부한다. 다만 "익명이 아니면 로그인 사용자"로 보는 판정(공개 레이트리밋 면제·공개 전송 상한 면제)은 이 상태도 통과시키므로
 * 그런 판정은 {@link #isFullyAuthenticated}를 쓴다.
 */
public final class TotpAuthorities {

    public static final String PENDING = "TOTP_PENDING";

    private TotpAuthorities() {
    }

    public static boolean isPending(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (PENDING.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /** 인증됨 · 익명 아님 · 2단계 중간 상태 아님. */
    public static boolean isFullyAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && !isPending(authentication);
    }
}
