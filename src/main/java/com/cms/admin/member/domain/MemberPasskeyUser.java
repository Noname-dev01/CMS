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
 * 회원의 WebAuthn user handle(회원당 1개, 무작위 바이트 — PLAN-passkey-second-factor.md 결정 3).
 * 패스키를 모두 지워도 남겨 재등록 때 같은 핸들을 쓴다. 쓰기는 {@code member} 행 잠금 아래에서만 한다.
 */
@Entity
@Table(name = "member_passkey_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberPasskeyUser {

    /** 핸들 길이(바이트) — WebAuthn 상한 64 */
    public static final int HANDLE_BYTES = 64;

    @Id
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "user_handle", nullable = false, length = HANDLE_BYTES)
    private byte[] userHandle;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static MemberPasskeyUser create(Long memberId, byte[] userHandle, LocalDateTime now) {
        MemberPasskeyUser user = new MemberPasskeyUser();
        user.memberId = memberId;
        user.userHandle = userHandle.clone();
        user.createdAt = now;
        return user;
    }

    public byte[] getUserHandle() {
        return userHandle.clone();
    }
}
