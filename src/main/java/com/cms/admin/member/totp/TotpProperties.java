package com.cms.admin.member.totp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 2단계 인증 설정(PLAN-totp-two-factor.md 결정 5·6).
 * {@code encryption-key}는 Base64 32바이트(환경변수 {@code CMS_TOTP_ENCRYPTION_KEY}) — 없거나 틀리면 기동이 실패한다.
 * {@code previous-encryption-key}는 키 교체 중에만 넣는 옛 키(선택, 환경변수 {@code CMS_TOTP_PREVIOUS_ENCRYPTION_KEY}) — 복호에만 쓰이고,
 * 기동 시 옛 키 행을 현재 키로 다시 암호화한다(PLAN-totp-key-rotation.md). 형식이 틀리거나 현재 키와 같으면 기동이 실패한다.
 * {@code issuer}는 인증 앱에 보이는 발급자 이름이며 콜론을 쓸 수 없다.
 */
@ConfigurationProperties(prefix = "cms.totp")
public class TotpProperties {

    private String encryptionKey;
    private String previousEncryptionKey;
    private String issuer = "CMS";

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public String getPreviousEncryptionKey() {
        return previousEncryptionKey;
    }

    public void setPreviousEncryptionKey(String previousEncryptionKey) {
        this.previousEncryptionKey = previousEncryptionKey;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }
}
