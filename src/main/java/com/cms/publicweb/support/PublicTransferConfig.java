package com.cms.publicweb.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 공개 전송 상한 배선. 전송이 Tomcat 스레드의 절반을 넘게 차지하지 못한다는 것이 이 기능의 보장이라 설정이 그 관계를 깨면 기동을 막는다
 * (PLAN-public-transfer-limit.md D3). 스레드 수 기본값 200은 Boot 기본값이고, {@code PublicTransferLimitServerTest}가 실제 커넥터 값과 같은지 확인한다.
 */
@Configuration
@EnableConfigurationProperties(PublicTransferProperties.class)
public class PublicTransferConfig {

    @Bean
    public PublicTransferLimiter publicTransferLimiter(PublicTransferProperties properties,
                                                       @Value("${server.tomcat.threads.max:200}") int tomcatMaxThreads) {
        if (properties.maxPerIp() > properties.maxTotal()) {
            throw new IllegalStateException("cms.public-transfer.max-per-ip는 max-total 이하여야 한다");
        }
        if (properties.maxTotal() * 2L > tomcatMaxThreads) {
            throw new IllegalStateException("cms.public-transfer.max-total은 server.tomcat.threads.max의 절반 이하여야 한다");
        }
        return new PublicTransferLimiter(properties.maxTotal(), properties.maxPerIp());
    }
}
