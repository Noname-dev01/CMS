package com.cms.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
    @DisplayName("dev·prod 외 조합(test, webmvc-test 등)은 통과한다")
    void otherCombination_passes() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test", "webmvc-test");

        assertThatCode(() -> processor.postProcessEnvironment(environment, null))
                .doesNotThrowAnyException();
    }
}
