package com.cms.config;

import com.cms.admin.member.service.PasswordResetExecutorConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 비밀번호 재설정 메일 발송 executor 상한(PLAN-mail-executor-bound.md 결정 1·2·6-1)의 설정 적용 계약.
 *
 * <p>{@code PasswordResetService}가 주입받는 {@code TaskExecutor}는 전용 빈이 아니라 Boot 기본
 * {@code applicationTaskExecutor}다. 값을 테스트에 복제하지 않고 실제 {@code application.yml}의
 * {@code spring.task.execution.*}를 읽어 Boot 자동 구성에 바인딩하므로, yml에서 pool 블록이 지워지거나
 * 키가 오타 나면(=Boot 기본값 core 8·큐 무제한으로 되돌아가면) 이 테스트가 실패한다.
 * DB·전체 앱을 띄우지 않는 좁은 {@link ApplicationContextRunner}만 쓴다.
 */
class MailExecutorPoolConfigurationTest {

    @Test
    @DisplayName("application.yml의 pool 설정이 실제 applicationTaskExecutor에 core 4 / max 4 / 큐 20으로 반영된다")
    void applicationYml_boundsApplicationTaskExecutor() throws Exception {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .withPropertyValues(taskExecutionPropertiesFromApplicationYml())
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ThreadPoolTaskExecutor.class);
                    ThreadPoolTaskExecutor executor = context.getBean(ThreadPoolTaskExecutor.class);

                    assertThat(executor.getCorePoolSize()).isEqualTo(4);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(4);
                    // 큐 용량 — 무제한(기본값)이면 remainingCapacity가 Integer.MAX_VALUE다
                    assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(20);
                });
    }

    @Test
    @DisplayName("발급 전용 executor(2/2/100, 종료 시 최대 5초 대기)가 있어도 Boot applicationTaskExecutor(4/4/20)는 유지되고 한정자 없는 TaskExecutor 주입은 그것을 받는다")
    void issueExecutor_keepsApplicationTaskExecutorAsDefault() throws Exception {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .withPropertyValues(taskExecutionPropertiesFromApplicationYml())
                .withUserConfiguration(PasswordResetExecutorConfig.class, PlainTaskExecutorConsumer.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasBean("applicationTaskExecutor");

                    ThreadPoolTaskExecutor issue = context.getBean(
                            PasswordResetExecutorConfig.ISSUE_EXECUTOR, ThreadPoolTaskExecutor.class);
                    assertThat(issue.getCorePoolSize()).isEqualTo(2);
                    assertThat(issue.getMaxPoolSize()).isEqualTo(2);
                    assertThat(issue.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(100);
                    assertThat(ReflectionTestUtils.getField(issue, "waitForTasksToCompleteOnShutdown")).isEqualTo(true);
                    assertThat(ReflectionTestUtils.getField(issue, "awaitTerminationMillis")).isEqualTo(5000L);

                    // defaultCandidate=false가 빠지면 Boot 자동 구성이 물러나거나 메일 단계가 발급 executor를 받는다
                    Object applicationTaskExecutor = context.getBean("applicationTaskExecutor");
                    assertThat(context.getBean("plainTaskExecutorHolder", TaskExecutor[].class)[0])
                            .isSameAs(applicationTaskExecutor);
                    assertThat(applicationTaskExecutor).isNotSameAs(issue);
                });
    }

    /** 한정자 없는 TaskExecutor 주입이 어떤 빈을 받는지 보는 최소 소비자(PasswordResetService의 메일 executor 주입과 같은 형태). */
    @Configuration(proxyBeanMethods = false)
    static class PlainTaskExecutorConsumer {
        @Bean
        TaskExecutor[] plainTaskExecutorHolder(TaskExecutor executor) {
            return new TaskExecutor[]{executor};
        }
    }

    /** application.yml의 spring.task.execution.* 키만 "key=value" 형태로 추출한다. */
    private static String[] taskExecutionPropertiesFromApplicationYml() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));

        Map<String, String> result = new LinkedHashMap<>();
        for (PropertySource<?> source : sources) {
            EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) source;
            for (String name : enumerable.getPropertyNames()) {
                if (name.startsWith("spring.task.execution.")) {
                    result.put(name, String.valueOf(enumerable.getProperty(name)));
                }
            }
        }
        // 키가 하나도 없으면 아래 검증이 Boot 기본값으로 조용히 통과할 수 없도록 여기서 먼저 실패시킨다
        assertThat(result).as("application.yml의 spring.task.execution.* 설정").isNotEmpty();
        return result.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new);
    }
}
