package com.cms.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ProfileGuardEnvironmentPostProcessorTest {

    private final ProfileGuardEnvironmentPostProcessor processor = new ProfileGuardEnvironmentPostProcessor();

    @Test
    @DisplayName("dev만 활성화되면 통과한다")
    void devOnly_passes() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        assertThatCode(() -> processor.postProcessEnvironment(environment, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod만 활성화되면 통과한다")
    void prodOnly_passes() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatCode(() -> processor.postProcessEnvironment(environment, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("dev와 prod가 동시에 활성화되면 기동을 거부한다")
    void devAndProd_rejected() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev", "prod");

        assertThatIllegalStateException()
                .isThrownBy(() -> processor.postProcessEnvironment(environment, null));
    }

    @Test
    @DisplayName("활성 프로파일이 0개면(빈 문자열 등) 기동을 거부한다")
    void noActiveProfiles_rejected() {
        MockEnvironment environment = new MockEnvironment();

        assertThatIllegalStateException()
                .isThrownBy(() -> processor.postProcessEnvironment(environment, null));
    }

    @Test
    @DisplayName("META-INF/spring.factories 등록으로 실제 SpringApplication 기동 경로에서도 dev+prod를 막는다")
    void registeredViaSpringFactories_blocksDevAndProdOnRealStartup() {
        // 위 테스트들은 클래스를 직접 호출해 등록 키가 틀려도 통과한다 — Boot 4는 등록 키가
        // org.springframework.boot.EnvironmentPostProcessor로 바뀌어 키 불일치 시 가드가 조용히 빠진다(PLAN-spring-boot-4.md R5).
        SpringApplication application = new SpringApplication(EmptyConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);

        assertThatIllegalStateException()
                .isThrownBy(() -> application.run("--spring.profiles.active=dev,prod"))
                .withMessageContaining("dev와 prod 프로파일을 동시에 활성화할 수 없습니다");
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {
    }

    @Test
    @DisplayName("dev·prod 외 조합(test, webmvc-test 등)은 통과한다")
    void otherCombination_passes() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test", "webmvc-test");

        assertThatCode(() -> processor.postProcessEnvironment(environment, null))
                .doesNotThrowAnyException();
    }
}
