package com.cms.admin.contentimage.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * {@code CmsApplication}이 {@code @ConfigurationPropertiesScan}을 쓰지 않으므로 {@link ContentImageProperties}를
 * 명시 등록한다({@code MessageConfig}와 같은 방식).
 */
@Configuration
@EnableConfigurationProperties(ContentImageProperties.class)
public class ContentImageConfig {
}
