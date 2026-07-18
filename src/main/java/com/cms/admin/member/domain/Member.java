package com.cms.admin.member.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;
import java.util.Objects;

/*
 * @DynamicUpdate: 변경된 컬럼만 UPDATE한다.
 * 행 잠금 없는 더티체킹 쓰기 경로(내 정보·프로필 수정 등)가 동시 자동 잠금(벌크 UPDATE)과
 * 경합할 때, 전체 컬럼 UPDATE가 stale status/failedLoginCount/lockedAt을 되써서
 * 잠금을 소실시키는 것을 차단한다. 같은 필드 경합은 여전히 findByIdForUpdate 행 잠금이 담당.
 */
@Entity
@DynamicUpdate
@Table(
        name = "member",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_member_user_id", columnNames = "userId"),
                @UniqueConstraint(name = "uk_member_email", columnNames = "email")
        }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    @Column(nullable = false, length = 255)
    private String pwd;

    @Column(nullable = false, length = 100)
    private String userName;

    @Column(nullable = false, length = 100)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role userType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MemberStatus status;

    private LocalDateTime createDate;

    private LocalDateTime updateDate;

    @Column(length = 255)
    private String resetToken;

    private LocalDateTime resetTokenExpiryAt;

    @Lob
    @Column(name = "profile_image_url", columnDefinition = "LONGTEXT")
    private String profileImageUrl;

    /** 로그인 연속 실패 카운트. 5회 도달 시 LOCKED 자동 전이 (증가·잠금은 벌크 UPDATE — LoginFailureService) */
    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    /** 자동 잠금 시각. null이면 수동 잠금(영구) — 30분 자동 해제 판정 기준 */
    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    /** 비밀번호 최종 변경 시각. 90일 도달 시 PASSWORD_EXPIRED 전이 기준 — null 불허(fail-open 차단) */
    @Column(name = "password_changed_at", nullable = false)
    private LocalDateTime passwordChangedAt;

    /**
     * 내 정보(이름, 이메일) 수정. 수정 시각을 함께 갱신한다.
     * 이메일이 실제로 바뀌면 발급돼 있던 재설정 토큰도 무효화한다 —
     * 이전 주소의 메일함에 남은 재설정 링크가 계속 유효해서는 안 된다.
     */
    public void updateInfo(String userName, String email) {
        this.userName = userName;
        if (!Objects.equals(this.email, email)) {
            this.resetToken = null;
            this.resetTokenExpiryAt = null;
        }
        this.email = email;
        this.updateDate = LocalDateTime.now();
    }

    /**
     * 프로필 이미지 변경. null을 전달하면 이미지를 초기화한다.
     */
    public void changeProfileImage(String profileImageUrl) {
        this.profileImageUrl = profileImageUrl;
        this.updateDate = LocalDateTime.now();
    }

    /**
     * 비밀번호 변경. 이미 인코딩된 비밀번호를 전달해야 한다.
     * 발급돼 있던 재설정 토큰도 함께 무효화한다 — 어떤 경로로든 비밀번호가 바뀌면
     * 메일함에 남은 재설정 링크가 계속 유효해서는 안 된다.
     * 만료 상태 해소도 이 관문이 담당한다 — 만료 전이는 세션을 폐기하지 않으므로
     * 살아있는 세션의 변경 경로에서도 PASSWORD_EXPIRED가 잔존하면 안 된다.
     *
     * @param now 앱 Clock 기준 현재 시각 — passwordChangedAt·updateDate에 동일 값 기록
     */
    public void changePassword(String encodedPwd, LocalDateTime now) {
        this.pwd = encodedPwd;
        this.resetToken = null;
        this.resetTokenExpiryAt = null;
        // 비밀번호가 바뀌면 이전 실패 연쇄는 단절된다 — 카운트만 리셋.
        // lockedAt은 보존한다: 경합으로 LOCKED 상태에서 실행돼도 자동 잠금(30분 해제)이
        // 영구 잠금(lockedAt null)으로 변질되지 않아야 한다.
        this.failedLoginCount = 0;
        this.passwordChangedAt = now;
        // LOCKED·DISABLED는 건드리지 않는다 — 만료 해소는 비밀번호 변경의 정의적 결과지만
        // 잠금·비활성은 별도 관리 경로의 소관이다.
        if (this.status == MemberStatus.PASSWORD_EXPIRED) {
            this.status = MemberStatus.ACTIVE;
        }
        this.updateDate = now;
    }

    /**
     * 비밀번호 재설정 토큰 발급. 평문이 아니라 SHA-256 해시를 저장해야 한다.
     */
    public void issueResetToken(String hashedToken, LocalDateTime expiryAt) {
        this.resetToken = hashedToken;
        this.resetTokenExpiryAt = expiryAt;
        this.updateDate = LocalDateTime.now();
    }

    /**
     * 비밀번호 재설정 토큰 무효화.
     */
    public void clearResetToken() {
        this.resetToken = null;
        this.resetTokenExpiryAt = null;
        this.updateDate = LocalDateTime.now();
    }

    /**
     * 권한(역할) 변경. 수정 시각을 함께 갱신한다.
     */
    public void changeRole(Role userType) {
        this.userType = userType;
        this.updateDate = LocalDateTime.now();
    }

    /**
     * 계정 상태 변경. 수정 시각을 함께 갱신한다.
     * 자동 잠금 시각(lockedAt)도 항상 정리한다 — 수동 →LOCKED는 영구 잠금이 되고,
     * 자동 잠금에서 다른 상태로 나갈 때(LOCKED→DISABLED 등) 잔존 시각이
     * 이후 수동 잠금을 자동 해제시키는 회귀(LOCKED→DISABLED→LOCKED)를 막는다.
     */
    public void changeStatus(MemberStatus status) {
        this.status = status;
        this.lockedAt = null;
        this.updateDate = LocalDateTime.now();
    }

    /**
     * 로그인 실패 카운트·자동 잠금 시각 리셋. 관리자 수동 해제(비ACTIVE→ACTIVE) 전용.
     * updateDate는 갱신하지 않는다 — 상태 변경 경로(changeStatus)가 함께 갱신한다.
     */
    public void resetFailedLoginCount() {
        this.failedLoginCount = 0;
        this.lockedAt = null;
    }

    /**
     * 만료된 자동 잠금을 해제한다 — 비밀번호 재설정 경로 전용 (행 잠금 하에서 호출).
     * 수동 잠금(lockedAt null)은 대상이 아니다. 로그인 경로의 벌크 UPDATE
     * (MemberRepository.unlockIfLockExpired)와 동일한 필드 계약을 유지한다.
     *
     * @param cutoff 이 시각 이전(포함)에 잠긴 자동 잠금만 해제
     * @param now    updateDate에 기록할 현재 시각 (앱 Clock 기준)
     */
    public boolean releaseExpiredAutoLock(LocalDateTime cutoff, LocalDateTime now) {
        if (this.status != MemberStatus.LOCKED || this.lockedAt == null || this.lockedAt.isAfter(cutoff)) {
            return false;
        }
        this.status = MemberStatus.ACTIVE;
        this.failedLoginCount = 0;
        this.lockedAt = null;
        this.updateDate = now;
        return true;
    }

}
