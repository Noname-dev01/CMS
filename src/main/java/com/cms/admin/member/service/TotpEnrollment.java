package com.cms.admin.member.service;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 세션에 보관하는 2단계 인증 등록 대기 상태(DB 쓰기 없음). 확정 때 {@code memberId}·{@code enrollmentId}·발급 시각(10분)을
 * 모두 대조한다 — 같은 세션에서 다른 계정으로 다시 로그인했거나 다른 탭이 새로 시작한 대기를 확정하지 않기 위해서다
 * (PLAN-totp-two-factor.md 결정 4). 비밀값 평문이 세션에 머무는 시간은 대기 동안뿐이다.
 */
public record TotpEnrollment(String enrollmentId, Long memberId, byte[] secret, LocalDateTime issuedAt) implements Serializable {

    /** 세션 속성 이름 */
    public static final String SESSION_ATTRIBUTE = TotpEnrollment.class.getName();

    /**
     * 강제 등록 화면이 이 대기를 다시 보여 줄 수 있는지(PLAN-totp-enrollment-reuse.md 결정 1) — 같은 회원이고 발급 후 10분 안.
     * 경계는 확정 판정과 같다(정확히 10분은 유효). 재사용은 유효기간을 연장하지 않는다.
     */
    public boolean isReusableFor(Long requesterId, LocalDateTime now) {
        return Objects.equals(memberId, requesterId) && !issuedAt.plus(MemberTotpService.ENROLLMENT_TTL).isBefore(now);
    }

    @Override
    public String toString() {
        return "TotpEnrollment{enrollmentId=" + enrollmentId + ", memberId=" + memberId + "}";
    }
}
