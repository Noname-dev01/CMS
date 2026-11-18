package com.cms.config.auth;

import com.cms.admin.member.domain.Role;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * 2단계 인증 중간 상태 표지와 "로그인 사용자" 판정(PLAN-totp-two-factor.md 결정 1·3).
 *
 * <p>2단계를 켠 회원이 비밀번호만 통과하면 인증 권한은 {@link #PENDING} 하나뿐이다 — 역할 권한이 없어 모든 관리 게이트가
 * 거부한다. 다만 "익명이 아니면 로그인 사용자"로 보는 판정(공개 레이트리밋 면제·공개 전송 상한 면제)은 이 상태도 통과시키므로
 * 그런 판정은 {@link #isFullyAuthenticated}를 쓴다. 2단계가 필수인 역할({@link #isRequired})이 아직 등록하지 않았으면 {@link #ENROLLMENT_REQUIRED}
 * 하나뿐인 또 다른 중간 상태가 된다(PLAN-totp-required-admin.md 결정 1).
 */
public final class TotpAuthorities {

    public static final String PENDING = "TOTP_PENDING";

    /** 2단계 인증이 필수인 역할(ADMIN)이 아직 등록하지 않은 채 비밀번호만 통과한 상태 — 강제 등록 화면만 들어간다(PLAN-totp-required-admin.md 결정 1). */
    public static final String ENROLLMENT_REQUIRED = "TOTP_ENROLLMENT_REQUIRED";

    private TotpAuthorities() {
    }

    /** 2단계 인증이 필수인 역할인가 — ADMIN만(PLAN-totp-required-admin.md 사용자 결정, 코드 상수 — 설정으로 바꾸지 않는다). */
    public static boolean isRequired(Role role) {
        return role == Role.ROLE_ADMIN;
    }

    public static boolean isPending(Authentication authentication) {
        return hasAuthority(authentication, PENDING);
    }

    public static boolean isEnrollmentRequired(Authentication authentication) {
        return hasAuthority(authentication, ENROLLMENT_REQUIRED);
    }

    /** 두 중간 상태(코드 대기·강제 등록) 중 하나인가 — 어느 쪽이든 역할 권한이 없다. */
    public static boolean isIntermediate(Authentication authentication) {
        return isPending(authentication) || isEnrollmentRequired(authentication);
    }

    /** 인증됨 · 익명 아님 · 2단계 중간 상태(두 종류 모두) 아님. */
    public static boolean isFullyAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && !isIntermediate(authentication);
    }

    private static boolean hasAuthority(Authentication authentication, String name) {
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (name.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
