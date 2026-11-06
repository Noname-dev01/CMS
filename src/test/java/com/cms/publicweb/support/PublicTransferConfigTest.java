package com.cms.publicweb.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;

import static org.assertj.core.api.Assertions.assertThat;

/** 설정 실수가 기동 실패로 드러나는지 고정한다(PLAN-public-transfer-limit.md D3, F5). */
class PublicTransferConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PublicTransferConfig.class);

    @Test
    @DisplayName("전체 상한이 스레드 수의 정확히 절반이면 기동한다")
    void totalAtHalfOfThreadsStarts() {
        runner.withPropertyValues("cms.public-transfer.max-total=100", "cms.public-transfer.max-per-ip=20", "server.tomcat.threads.max=200")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(PublicTransferLimiter.class));
    }

    @Test
    @DisplayName("스레드 수를 지정하지 않으면 Boot 기본 200으로 판정한다")
    void missingThreadsUsesDefault200() {
        runner.withPropertyValues("cms.public-transfer.max-total=101", "cms.public-transfer.max-per-ip=20")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("전체 상한이 스레드 수의 절반을 넘으면 기동이 실패한다")
    void totalOverHalfOfThreadsFails() {
        runner.withPropertyValues("cms.public-transfer.max-total=101", "cms.public-transfer.max-per-ip=20", "server.tomcat.threads.max=200")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("스레드 수를 줄여 관계가 역전돼도 기동이 실패한다")
    void loweredThreadsFails() {
        runner.withPropertyValues("cms.public-transfer.max-total=100", "cms.public-transfer.max-per-ip=20", "server.tomcat.threads.max=100")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("IP별 상한이 전체 상한보다 크면 기동이 실패한다")
    void perIpOverTotalFails() {
        runner.withPropertyValues("cms.public-transfer.max-total=10", "cms.public-transfer.max-per-ip=11")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("상한이 0이거나 값이 없으면 기동이 실패한다")
    void zeroOrMissingFails() {
        runner.withPropertyValues("cms.public-transfer.max-total=0", "cms.public-transfer.max-per-ip=0")
                .run(context -> assertThat(context).hasFailed());
        runner.run(context -> assertThat(context).hasFailed());
    }
}
