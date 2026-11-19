package com.cms.admin.member.passkey;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 패스키(WebAuthn) 설정(PLAN-passkey-second-factor.md 결정 6). {@code rp-id}가 비어 있으면 기능이 꺼진다(prod 기본 — 도메인이 정해진 뒤 켠다).
 * 켜면 {@code allowed-origins}가 1개 이상이어야 하고, 각 origin은 {@code https://}(호스트가 {@code localhost}면 {@code http://} 허용)이며
 * 호스트가 rp-id와 같거나 {@code .rp-id}로 끝나야 한다 — 어기면 기동이 실패한다({@link PasskeyConfig}).
 * origin 비교는 scheme·host·port 정확 일치다(webauthn4j 0.31.6 {@code Origin.equals}).
 */
@ConfigurationProperties(prefix = "cms.passkey")
public class PasskeyProperties {

    private String rpId = "";
    private String rpName = "CMS 관리자";
    private List<String> allowedOrigins = new ArrayList<>();

    public boolean isEnabled() {
        return rpId != null && !rpId.isBlank();
    }

    public String getRpId() {
        return rpId;
    }

    public void setRpId(String rpId) {
        this.rpId = rpId;
    }

    public String getRpName() {
        return rpName;
    }

    public void setRpName(String rpName) {
        this.rpName = rpName;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
