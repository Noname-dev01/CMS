package com.cms.admin.member.password;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code cms.password-breach-check.*} 설정(PLAN-password-expiry-retirement-breach-check.md D9). {@code enabled=false}는 망이 격리된 설치와 시험용이다.
 * {@link PasswordBreachCheckConfig}가 명시 등록한다({@code CmsApplication}은 {@code @ConfigurationPropertiesScan}을 쓰지 않는다).
 */
@ConfigurationProperties(prefix = "cms.password-breach-check")
public class PasswordBreachCheckProperties {

    /** HIBP Pwned Passwords range API. 시험이 로컬 서버를 가리키도록 설정 가능하다. */
    static final String DEFAULT_RANGE_URL = "https://api.pwnedpasswords.com/range/";

    private boolean enabled = true;
    private String rangeUrl = DEFAULT_RANGE_URL;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRangeUrl() {
        return rangeUrl;
    }

    public void setRangeUrl(String rangeUrl) {
        this.rangeUrl = rangeUrl == null || rangeUrl.isBlank() ? DEFAULT_RANGE_URL : rangeUrl;
    }
}
