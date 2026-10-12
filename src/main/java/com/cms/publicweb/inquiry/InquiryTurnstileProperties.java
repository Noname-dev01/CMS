package com.cms.publicweb.inquiry;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code cms.inquiry.turnstile.*} 설정(PLAN-inquiry.md 쟁점 4). <b>{@code enabled} 기본값은 {@code false}이고 false는 "문의 기능 닫힘"</b>이다 —
 * 캡차 없이 제출을 받는 상태는 어떤 설정 조합으로도 만들 수 없다. {@link InquiryConfig}가 {@code @EnableConfigurationProperties}로 명시 등록한다
 * ({@code CmsApplication}은 {@code @ConfigurationPropertiesScan}을 쓰지 않는다). 기동 시 교차 검증은 {@link InquiryTurnstileConfigValidator}가 한다.
 */
@ConfigurationProperties(prefix = "cms.inquiry.turnstile")
public class InquiryTurnstileProperties {

    /** Cloudflare 서버 측 검증 엔드포인트. 시험이 로컬 서버를 가리키도록 설정 가능하다. */
    static final String DEFAULT_VERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private boolean enabled;
    private String siteKey = "";
    private String secretKey = "";
    private String verifyUrl = DEFAULT_VERIFY_URL;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSiteKey() {
        return siteKey;
    }

    public void setSiteKey(String siteKey) {
        this.siteKey = siteKey == null ? "" : siteKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey == null ? "" : secretKey;
    }

    public String getVerifyUrl() {
        return verifyUrl;
    }

    public void setVerifyUrl(String verifyUrl) {
        this.verifyUrl = verifyUrl == null || verifyUrl.isBlank() ? DEFAULT_VERIFY_URL : verifyUrl;
    }
}
