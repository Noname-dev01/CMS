package com.cms.config.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Set;

/**
 * {@code cms.rate-limit.*} 설정 프로퍼티. {@link RateLimitFilterConfig}가
 * {@code @EnableConfigurationProperties}로 명시 등록한다 — {@code CmsApplication}은
 * {@code @ConfigurationPropertiesScan}을 쓰지 않으므로 자동 등록되지 않는다.
 *
 * <p>Bean Validation 상한값은 오버플로·과도한 캐시 크기를 방지하기 위한 것으로, 실제 위반 여부는
 * {@link RateLimitConfigValidator}가 기동 시점에 추가로 교차 검증한다(PLAN-public-endpoint-rate-limit.md
 * 쟁점 11).
 */
@ConfigurationProperties(prefix = "cms.rate-limit")
@Validated
public class RateLimitProperties {

    private boolean enabled;

    @Positive
    @Max(1_000_000)
    private int maxKeys = 10_000;

    @Valid
    private List<RuleConfig> rules = List.of();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxKeys() {
        return maxKeys;
    }

    public void setMaxKeys(int maxKeys) {
        this.maxKeys = maxKeys;
    }

    public List<RuleConfig> getRules() {
        return rules;
    }

    public void setRules(List<RuleConfig> rules) {
        this.rules = rules;
    }

    /** 단일 규칙 설정. {@link RateLimitRule}로 해석되기 전 원시 값이다. */
    public static class RuleConfig {

        @NotBlank
        private String id;

        @NotBlank
        private String pattern;

        @NotEmpty
        private Set<String> methods = Set.of();

        @Positive
        @Max(100_000)
        private int capacity;

        @Positive
        @Max(86_400)
        private long refillPeriodSeconds;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getPattern() {
            return pattern;
        }

        public void setPattern(String pattern) {
            this.pattern = pattern;
        }

        public Set<String> getMethods() {
            return methods;
        }

        public void setMethods(Set<String> methods) {
            this.methods = methods;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public long getRefillPeriodSeconds() {
            return refillPeriodSeconds;
        }

        public void setRefillPeriodSeconds(long refillPeriodSeconds) {
            this.refillPeriodSeconds = refillPeriodSeconds;
        }
    }
}
