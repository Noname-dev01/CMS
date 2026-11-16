package com.cms.config.auth;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 비밀번호를 통과해 2단계를 기다리는 세션의 표지 — 누구의(memberId) 언제부터(since)인가. 성공 처리기가 두고,
 * 2단계 제출이 principal과 대조하고 5분이 지났으면 버린다(PLAN-totp-two-factor.md 결정 2).
 */
public record TotpPendingState(Long memberId, LocalDateTime since) implements Serializable {

    public static final String SESSION_ATTRIBUTE = TotpPendingState.class.getName();
}
