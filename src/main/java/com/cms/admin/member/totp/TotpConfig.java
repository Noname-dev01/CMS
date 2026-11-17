package com.cms.admin.member.totp;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 2단계 인증 빈. 키·issuer 검증이 빈 생성 시점에 일어나므로 설정이 틀리면 기동이 실패한다(PLAN 결정 6, S8).
 */
@Configuration
@EnableConfigurationProperties(TotpProperties.class)
public class TotpConfig {

    @Bean
    public TotpSecretCipher totpSecretCipher(TotpProperties properties) {
        OtpAuthUri.validateIssuer(properties.getIssuer());
        return new TotpSecretCipher(properties.getEncryptionKey(), properties.getPreviousEncryptionKey());
    }

    @Bean
    public TotpCodeVerifier totpCodeVerifier(Clock clock) {
        return new TotpCodeVerifier(clock);
    }
}
