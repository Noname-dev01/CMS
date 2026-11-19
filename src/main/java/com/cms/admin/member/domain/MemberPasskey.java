package com.cms.admin.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 회원이 등록한 패스키(WebAuthn 자격 증명) — 2단계 인증의 대체 수단(PLAN-passkey-second-factor.md 결정 1·3).
 *
 * <p><b>유효 조건</b>: {@code createdAt >= member_totp.enabled_at}(현재 TOTP 등록 이후에 만든 것). 쓰기는 {@code member} 행 잠금 아래에서만 한다.
 * 연관관계 매핑은 두지 않는다(프로젝트 관례). 바이트 배열은 방어적으로 복사한다.
 */
@Entity
@Table(name = "member_passkey")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberPasskey {

    /** 회원당 등록 상한 */
    public static final int MAX_PER_MEMBER = 10;

    /** 이름 상한(코드포인트) — DB varchar(50)과 같다 */
    public static final int LABEL_MAX_CODE_POINTS = 50;

    /** DB {@code blob}(최대 65535바이트) — {@code @Lob}은 길이 없이 {@code tinyblob}을 기대해 스키마 검증이 실패한다 */
    private static final int BLOB_BYTES = 65535;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "credential_id", nullable = false, length = 1023)
    private byte[] credentialId;

    @Column(name = "public_key", nullable = false, length = BLOB_BYTES)
    private byte[] publicKey;

    @Column(name = "signature_count", nullable = false)
    private long signatureCount;

    @Column(name = "uv_initialized", nullable = false)
    private boolean uvInitialized;

    @Column(name = "backup_eligible", nullable = false)
    private boolean backupEligible;

    @Column(name = "backup_state", nullable = false)
    private boolean backupState;

    /** 인증기 전송 방식(쉼표 목록, 없으면 빈 문자열) */
    @Column(nullable = false, length = 255)
    private String transports;

    @Column(name = "attestation_object", nullable = false, length = BLOB_BYTES)
    private byte[] attestationObject;

    @Column(name = "attestation_client_data_json", nullable = false, length = BLOB_BYTES)
    private byte[] attestationClientDataJson;

    @Column(nullable = false, length = LABEL_MAX_CODE_POINTS)
    private String label;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Builder(builderMethodName = "registration")
    private static MemberPasskey register(Long memberId, byte[] credentialId, byte[] publicKey, long signatureCount,
                                          boolean uvInitialized, boolean backupEligible, boolean backupState, String transports,
                                          byte[] attestationObject, byte[] attestationClientDataJson, String label,
                                          LocalDateTime createdAt) {
        MemberPasskey passkey = new MemberPasskey();
        passkey.memberId = memberId;
        passkey.credentialId = credentialId.clone();
        passkey.publicKey = publicKey.clone();
        passkey.signatureCount = signatureCount;
        passkey.uvInitialized = uvInitialized;
        passkey.backupEligible = backupEligible;
        passkey.backupState = backupState;
        passkey.transports = transports;
        passkey.attestationObject = attestationObject.clone();
        passkey.attestationClientDataJson = attestationClientDataJson.clone();
        passkey.label = label;
        passkey.createdAt = createdAt;
        return passkey;
    }

    /** 인증 성공 기록 — 서명 횟수·백업 상태·마지막 사용 시각. */
    public void recordUse(long signatureCount, boolean backupState, LocalDateTime now) {
        this.signatureCount = signatureCount;
        this.backupState = backupState;
        this.lastUsedAt = now;
    }

    public byte[] getCredentialId() {
        return credentialId.clone();
    }

    public byte[] getPublicKey() {
        return publicKey.clone();
    }

    public byte[] getAttestationObject() {
        return attestationObject.clone();
    }

    public byte[] getAttestationClientDataJson() {
        return attestationClientDataJson.clone();
    }
}
