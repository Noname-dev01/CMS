package com.cms.admin.member.totp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2단계 인증 설정 — 키가 없거나 틀리면, issuer가 비었거나 콜론을 포함하면 컨텍스트 생성(= 기동)이 실패한다(PLAN 결정 5·6, S8).
 * application.yml의 기본값이 빈 문자열이라 prod에서 CMS_TOTP_ENCRYPTION_KEY를 빠뜨리면 이 경로로 기동이 멈춘다.
 */
class TotpConfigTest {

    private static final String VALID_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ClockConfig.class, TotpConfig.class);

    @Configuration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Test
    @DisplayName("유효한 키와 기본 issuer면 암호기·판정기·설정 빈이 만들어진다(issuer 기본값 CMS)")
    void startsWithValidKey() {
        runner.withPropertyValues("cms.totp.encryption-key=" + VALID_KEY).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TotpSecretCipher.class).hasSingleBean(TotpCodeVerifier.class);
            assertThat(context.getBean(TotpProperties.class).getIssuer()).isEqualTo("CMS");
        });
    }

    @Test
    @DisplayName("키가 없거나 빈 값·32바이트가 아님 → 기동 실패")
    void failsWithoutValidKey() {
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("cms.totp.encryption-key=").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("cms.totp.encryption-key=" + Base64.getEncoder().encodeToString(new byte[16]))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("issuer에 콜론이 있거나 비어 있으면 기동 실패")
    void failsWithInvalidIssuer() {
        runner.withPropertyValues("cms.totp.encryption-key=" + VALID_KEY, "cms.totp.issuer=C:MS")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("cms.totp.encryption-key=" + VALID_KEY, "cms.totp.issuer= ")
                .run(context -> assertThat(context).hasFailed());
    }
}
