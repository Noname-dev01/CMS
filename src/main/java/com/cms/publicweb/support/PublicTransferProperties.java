package com.cms.publicweb.support;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code cms.public-transfer.*} — 공개 파일 전송 동시 상한(PLAN-public-transfer-limit.md D3). 값의 관계는 {@link PublicTransferConfig}가 기동 시 확인한다. */
@ConfigurationProperties(prefix = "cms.public-transfer")
@Validated
public record PublicTransferProperties(@Positive int maxTotal, @Positive int maxPerIp) {
}
