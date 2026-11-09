package com.cms.admin.member.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 비밀번호 재설정 발급 전용 executor (PLAN-password-reset-async-issue.md D2·D3).
 *
 * <p>재설정 요청의 계정 조회·토큰 발급을 응답 경로 밖에서 실행해 응답 시간이 계정 상태를 드러내지 않게 한다.
 * 메일 발송은 이 executor가 아니라 기존 공용 {@code applicationTaskExecutor}(4/4/20)를 그대로 쓴다.
 *
 * <p>{@code defaultCandidate = false}는 빼면 안 된다 — 일반 {@code Executor} 빈이 생기면 Boot가
 * {@code applicationTaskExecutor} 자동 구성을 거두고(Spring MVC 등이 그 이름을 쓴다), 한정자 없는
 * {@code TaskExecutor} 주입(메일 단계)이 이 빈을 받게 된다. {@code MailExecutorPoolConfigurationTest}가 고정한다.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordResetExecutorConfig {

    /** 발급 executor 빈 이름 — 서비스는 이 한정자로만 주입받는다. */
    public static final String ISSUE_EXECUTOR = "passwordResetIssueExecutor";

    /**
     * 스레드 2개: 발급 작업은 정상 상태에서 수 ms이고 DB 연결을 하나씩 쓰므로 요청 스레드의 연결 몫을 빼앗지 않게 적게 둔다.
     * 대기 100: 작업 하나가 이메일 문자열 정도라 메모리는 무시할 수준이고, IP당 시간당 5회 한도에서 한 번에 채우기 어렵다.
     * 무제한 큐는 쓰지 않는다(PLAN-mail-executor-bound.md와 같은 이유).
     *
     * <p>종료: 새 작업을 받지 않고 남은 작업을 계속 처리하며, 컨텍스트 종료는 최대 5초만 기다린 뒤 나머지 빈을 닫는다.
     * 5초가 지나도 작업이 중단되지는 않는다 — 그 뒤 실행되는 발급은 닫힌 자원 때문에 실패 로그로 끝난다.
     */
    @Bean(name = ISSUE_EXECUTOR, defaultCandidate = false)
    public ThreadPoolTaskExecutor passwordResetIssueExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("pwreset-issue-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}
