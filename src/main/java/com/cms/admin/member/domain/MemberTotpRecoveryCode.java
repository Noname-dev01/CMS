package com.cms.admin.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 2단계 인증 복구 코드 1개(해시만 저장, 한 번 쓰면 {@code usedAt} 기록). */
@Entity
@Table(name = "member_totp_recovery_code")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberTotpRecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "code_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String codeHash;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static MemberTotpRecoveryCode issue(Long memberId, String codeHash, LocalDateTime now) {
        MemberTotpRecoveryCode code = new MemberTotpRecoveryCode();
        code.memberId = memberId;
        code.codeHash = codeHash;
        code.createdAt = now;
        return code;
    }

    public void markUsed(LocalDateTime now) {
        this.usedAt = now;
    }
}
