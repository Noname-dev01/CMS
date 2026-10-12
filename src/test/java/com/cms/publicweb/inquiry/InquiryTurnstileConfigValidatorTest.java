package com.cms.publicweb.inquiry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Turnstile 설정 기동 검증(PLAN-inquiry.md R1-3) — 닫힘(기본)·키 누락·prod 공개 테스트 키. */
class InquiryTurnstileConfigValidatorTest {

    private static final String TEST_SITE = "1x00000000000000000000AA";
    private static final String TEST_SECRET = "1x0000000000000000000000000000000AA";
    private static final String REAL_SITE = "0x4AAAAAAAexampleSiteKey";
    private static final String REAL_SECRET = "0x4AAAAAAAexampleSecretKeyValue";

    private static InquiryTurnstileProperties props(boolean enabled, String site, String secret) {
        InquiryTurnstileProperties properties = new InquiryTurnstileProperties();
        properties.setEnabled(enabled);
        properties.setSiteKey(site);
        properties.setSecretKey(secret);
        return properties;
    }

    private static MockEnvironment env(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }

    @Test
    @DisplayName("기본값은 닫힘(enabled=false)이고 키가 비어 있어도 기동된다 — prod에서도 마찬가지")
    void disabledByDefault_startsWithoutKeys() {
        assertThat(new InquiryTurnstileProperties().isEnabled()).isFalse();
        assertThatCode(() -> new InquiryTurnstileConfigValidator(new InquiryTurnstileProperties(), env("prod"))).doesNotThrowAnyException();
        assertThatCode(() -> new InquiryTurnstileConfigValidator(props(false, TEST_SITE, TEST_SECRET), env("prod"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enabled=true인데 키가 비어 있으면 어떤 프로파일에서도 기동 실패")
    void enabledWithBlankKeys_fails() {
        assertThatThrownBy(() -> new InquiryTurnstileConfigValidator(props(true, "", REAL_SECRET), env("dev")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new InquiryTurnstileConfigValidator(props(true, REAL_SITE, "  "), env("prod")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("prod에서 Cloudflare 공개 테스트 키(1x0…·2x0…·3x0…)는 사이트 키든 시크릿이든 기동 실패")
    void prodRejectsPublicTestKeys() {
        for (String testKey : new String[]{TEST_SITE, "2x00000000000000000000AB", "3x00000000000000000000FF",
                TEST_SECRET, "2x0000000000000000000000000000000AA", "3x0000000000000000000000000000000AA"}) {
            assertThatThrownBy(() -> new InquiryTurnstileConfigValidator(props(true, testKey, REAL_SECRET), env("prod")))
                    .as("사이트 키 자리: " + testKey).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new InquiryTurnstileConfigValidator(props(true, REAL_SITE, testKey), env("prod")))
                    .as("시크릿 자리: " + testKey).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("prod에서 실제 키는 통과하고, dev에서는 테스트 키도 허용된다")
    void realKeysPass_devAllowsTestKeys() {
        assertThatCode(() -> new InquiryTurnstileConfigValidator(props(true, REAL_SITE, REAL_SECRET), env("prod"))).doesNotThrowAnyException();
        assertThatCode(() -> new InquiryTurnstileConfigValidator(props(true, TEST_SITE, TEST_SECRET), env("dev"))).doesNotThrowAnyException();
    }

    private static final String[] TEST_KEY_ARGS = {"cms.inquiry.turnstile.enabled=true", "cms.inquiry.turnstile.site-key=1x00000000000000000000AA",
            "cms.inquiry.turnstile.secret-key=1x0000000000000000000000000000000AA"};

    @Test
    @DisplayName("배선: 실제 컨텍스트에서 prod + 공개 테스트 키는 컨텍스트 기동이 실패하고, dev는 뜨며, 기본(닫힘)은 prod에서도 뜬다")
    void wiring_failsContextStartup() {
        org.springframework.boot.test.context.runner.ApplicationContextRunner runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(InquiryConfig.class);

        runner.withPropertyValues("spring.profiles.active=prod").withPropertyValues(TEST_KEY_ARGS)
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("spring.profiles.active=dev").withPropertyValues(TEST_KEY_ARGS)
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("spring.profiles.active=prod", "cms.inquiry.turnstile.enabled=true")
                .run(context -> assertThat(context).as("enabled인데 키 없음").hasFailed());
    }
}
