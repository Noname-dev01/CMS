package com.cms.admin.member.password;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PasswordBreachCheckProperties.class)
public class PasswordBreachCheckConfig {
}
