package com.cms.admin.member.service;

import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 패스키 등록 대기(세션 보관, DB 쓰기 없음 — PLAN-passkey-second-factor.md 결정 5). 부를 때마다 대체되고 5분 유효(옵션 타임아웃과 같다).
 *
 * @param totpEnabledAt 발급 때 잠금 아래 읽은 {@code member_totp.enabled_at} — 확정 때 다르면 409(리셋·재등록 전에 시작한 등록이 새 세대에
 *                      패스키를 만들지 않게, 리뷰 3-1)
 */
public record PasskeyRegistration(String registrationId, Long memberId, LocalDateTime totpEnabledAt,
                                  PublicKeyCredentialCreationOptions options, LocalDateTime issuedAt) {

    public static final Duration TTL = Duration.ofMinutes(5);

    public static final String SESSION_ATTRIBUTE = PasskeyRegistration.class.getName();

    /** 확정 가능한가 — 같은 회원·같은 ID·발급 5분 이내(정확히 5분은 유효). */
    public boolean matches(Long memberId, String registrationId, LocalDateTime now) {
        return Objects.equals(this.memberId, memberId)
                && Objects.equals(this.registrationId, registrationId)
                && !issuedAt.plus(TTL).isBefore(now);
    }
}
