package com.cms.admin.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 회원의 2단계 인증(TOTP) 등록. 행이 있으면 사용 중이다(PLAN-totp-two-factor.md 결정 7).
 *
 * <p>{@link Member}와 분리한 이유: {@code CustomUserDetails}가 Member 엔티티 전체를 세션·레지스트리에 보관하므로
 * Member 컬럼이면 암호문이 principal에 실린다. 연관관계 매핑은 두지 않는다(프로젝트 관례).
 * 변경의 직렬화는 이 행이 아니라 {@code member} 행 잠금({@code findByIdForUpdate})이 맡는다.
 */
@Entity
@Table(name = "member_totp")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberTotp {

    /** 연속 실패 임계값 — 도달하면 계정을 잠그고 카운터를 0으로 돌린다(로그인 실패 임계값과 같은 값). */
    public static final int FAILURE_THRESHOLD = 5;

    @Id
    @Column(name = "member_id")
    private Long memberId;

    /** {@code TotpSecretCipher} 암호문 — 평문 비밀값은 저장하지 않는다. */
    @Column(nullable = false, length = 255)
    private String secret;

    @Column(name = "enabled_at", nullable = false)
    private LocalDateTime enabledAt;

    /** 마지막으로 통과한 30초 스텝 — 이하 스텝의 코드는 맞아도 거부(재사용 차단). */
    @Column(name = "last_used_step", nullable = false)
    private long lastUsedStep;

    /** TOTP 전용 연속 실패 횟수 — 비밀번호 재로그인·재설정과 무관하게 유지된다. */
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    public static MemberTotp enable(Long memberId, String encryptedSecret, long usedStep, LocalDateTime now) {
        MemberTotp totp = new MemberTotp();
        totp.memberId = memberId;
        totp.secret = encryptedSecret;
        totp.enabledAt = now;
        totp.lastUsedStep = usedStep;
        totp.failedAttempts = 0;
        return totp;
    }

    /** 코드가 통과한 스텝을 기록한다 — 호출자가 {@code step > lastUsedStep}을 이미 판정했다. */
    public void markStepUsed(long step) {
        this.lastUsedStep = step;
    }

    /** 실패를 하나 더하고, 임계값에 도달했으면 true를 돌려주며 카운터를 0으로 돌린다(잠금 기간 자체가 제한이다). */
    public boolean recordFailureAndCheckThreshold() {
        this.failedAttempts++;
        if (this.failedAttempts >= FAILURE_THRESHOLD) {
            this.failedAttempts = 0;
            return true;
        }
        return false;
    }

    public void resetFailures() {
        this.failedAttempts = 0;
    }
}
