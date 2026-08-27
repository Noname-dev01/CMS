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
}
