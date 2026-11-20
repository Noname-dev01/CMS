package com.cms.config.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RateLimitProperties}·{@link RateLimitConfigValidator}의 기동 시 검증.
 * {@link ApplicationContextRunner}로 {@link RateLimitFilterConfig}만 올려 가볍게 검증한다
 * (PLAN-public-endpoint-rate-limit.md 쟁점 11·12).
 */
class RateLimitPropertiesValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RateLimitFilterConfig.class);

    @Test
    void validConfig_startsSuccessfully() {
        contextRunner
                .withPropertyValues(
                        "cms.rate-limit.enabled=true",
                        "cms.rate-limit.max-keys=100",
                        "cms.rate-limit.rules[0].id=r1",
                        "cms.rate-limit.rules[0].pattern=/notices/**",
                        "cms.rate-limit.rules[0].methods=GET,HEAD",
                        "cms.rate-limit.rules[0].capacity=10",
                        "cms.rate-limit.rules[0].refill-period-seconds=60")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void enabledTrueWithEmptyRules_failsToStart() {
        contextRunner
                .withPropertyValues("cms.rate-limit.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledFalseWithEmptyRules_startsSuccessfully() {
        contextRunner
                .withPropertyValues("cms.rate-limit.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void duplicateRuleId_failsToStart() {
        contextRunner
                .withPropertyValues(
                        "cms.rate-limit.enabled=true",
                        "cms.rate-limit.rules[0].id=dup",
                        "cms.rate-limit.rules[0].pattern=/a/**",
                        "cms.rate-limit.rules[0].methods=GET",
                        "cms.rate-limit.rules[0].capacity=10",
                        "cms.rate-limit.rules[0].refill-period-seconds=60",
                        "cms.rate-limit.rules[1].id=dup",
                        "cms.rate-limit.rules[1].pattern=/b/**",
                        "cms.rate-limit.rules[1].methods=GET",
                        "cms.rate-limit.rules[1].capacity=10",
                        "cms.rate-limit.rules[1].refill-period-seconds=60")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void disallowedMethod_failsToStart() {
        contextRunner
                .withPropertyValues(
                        "cms.rate-limit.enabled=true",
                        "cms.rate-limit.rules[0].id=r1",
                        "cms.rate-limit.rules[0].pattern=/a/**",
                        "cms.rate-limit.rules[0].methods=TRACE",
                        "cms.rate-limit.rules[0].capacity=10",
                        "cms.rate-limit.rules[0].refill-period-seconds=60")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void negativeCapacity_failsToStart() {
        contextRunner
                .withPropertyValues(
                        "cms.rate-limit.enabled=true",
                        "cms.rate-limit.rules[0].id=r1",
                        "cms.rate-limit.rules[0].pattern=/a/**",
                        "cms.rate-limit.rules[0].methods=GET",
                        "cms.rate-limit.rules[0].capacity=-1",
                        "cms.rate-limit.rules[0].refill-period-seconds=60")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void emptyMethods_failsToStart() {
        contextRunner
                .withPropertyValues(
                        "cms.rate-limit.enabled=true",
                        "cms.rate-limit.rules[0].id=r1",
                        "cms.rate-limit.rules[0].pattern=/a/**",
                        "cms.rate-limit.rules[0].capacity=10",
                        "cms.rate-limit.rules[0].refill-period-seconds=60")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void blankId_failsToStart() {
        contextRunner
                .withPropertyValues(
                        "cms.rate-limit.enabled=true",
                        "cms.rate-limit.rules[0].pattern=/a/**",
                        "cms.rate-limit.rules[0].methods=GET",
                        "cms.rate-limit.rules[0].capacity=10",
                        "cms.rate-limit.rules[0].refill-period-seconds=60")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void loginRedirectOnPostOnlyRule_startsSuccessfully() {
        contextRunner
                .withPropertyValues(loginRedirectRule("POST"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** 로그인 화면 302는 POST 전용 — GET이 섞이면 이동한 GET /admin/login이 같은 규칙에 다시 걸려 루프가 될 수 있다(PLAN-login-rate-limit-notice.md F2). */
    @Test
    void loginRedirectOnRuleWithGet_failsToStart() {
        contextRunner
                .withPropertyValues(loginRedirectRule("GET,POST"))
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues(loginRedirectRule("GET"))
                .run(context -> assertThat(context).hasFailed());
    }

    private static String[] loginRedirectRule(String methods) {
        return new String[]{
                "cms.rate-limit.enabled=true",
                "cms.rate-limit.rules[0].id=login",
                "cms.rate-limit.rules[0].pattern=/admin/login",
                "cms.rate-limit.rules[0].methods=" + methods,
                "cms.rate-limit.rules[0].capacity=10",
                "cms.rate-limit.rules[0].refill-period-seconds=60",
                "cms.rate-limit.rules[0].login-redirect=true"};
    }
}
