package com.cms.admin.message.service;

import com.cms.admin.message.config.MessageConfig;
import com.cms.admin.message.config.MessageProperties;
import com.cms.common.exception.RateLimitedException;
import com.cms.config.ratelimit.Ticker;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 쪽지 검색·발송 시도 제한(PLAN-admin-message.md D17, R1-7·R2-6·R2-7) — 규칙 독립성·고정 문구·공개 필터와의 분리. */
class MessageRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;

    private MessageRateLimiter limiter() {
        return new MessageRateLimiter(new MessageProperties(), ticker);
    }

    @Test
    @DisplayName("검색과 발송 시도는 서로 독립인 버킷이다 — 검색 한도를 다 써도 발송 시도는 허용된다")
    void searchAndAttemptAreIndependent() {
        MessageRateLimiter limiter = limiter();
        for (int i = 0; i < 30; i++) {
            limiter.consumeSearch(1L);
        }

        assertThatThrownBy(() -> limiter.consumeSearch(1L)).isInstanceOf(RateLimitedException.class);
        limiter.consumeAttempt(1L); // 예외 없음
    }

    @Test
    @DisplayName("검색은 호출(요청)당 토큰 하나만 쓴다 — 31번째 호출에서 거부된다")
    void searchConsumesOneTokenPerCall() {
        MessageRateLimiter limiter = limiter();
        for (int i = 0; i < 30; i++) {
            limiter.consumeSearch(1L);
        }

        assertThatThrownBy(() -> limiter.consumeSearch(1L))
                .isInstanceOfSatisfying(RateLimitedException.class, e -> {
                    assertThat(e.getRetryAfterSeconds()).isGreaterThanOrEqualTo(1);
                    assertThat(e.getMessage()).isEqualTo(MessageRateLimiter.TOO_MANY_REQUESTS_MESSAGE); // 고정 문구
                });
    }

    @Test
    @DisplayName("발송 시도는 성공·실패와 무관하게 호출마다 소비한다 — 수신자 확인만 반복하는 열거도 제한된다")
    void attemptConsumesRegardlessOfOutcome() {
        MessageRateLimiter limiter = limiter();
        for (int i = 0; i < 30; i++) {
            limiter.consumeAttempt(2L);
        }

        assertThatThrownBy(() -> limiter.consumeAttempt(2L)).isInstanceOf(RateLimitedException.class);
    }

    @Test
    @DisplayName("회원마다 독립이다 — 같은 IP의 다른 회원은 서로의 한도를 쓰지 않는다")
    void membersAreIndependent() {
        MessageRateLimiter limiter = limiter();
        for (int i = 0; i < 30; i++) {
            limiter.consumeSearch(1L);
        }

        limiter.consumeSearch(2L); // 예외 없음
        assertThatThrownBy(() -> limiter.consumeSearch(1L)).isInstanceOf(RateLimitedException.class);
    }

    @Test
    @DisplayName("설정으로 한도를 바꿀 수 있다(cms.message.search-per-minute 등)")
    void honorsConfiguredLimits() {
        MessageProperties properties = new MessageProperties();
        properties.setSearchPerMinute(2);
        MessageRateLimiter limiter = new MessageRateLimiter(properties, ticker);

        limiter.consumeSearch(1L);
        limiter.consumeSearch(1L);

        assertThatThrownBy(() -> limiter.consumeSearch(1L)).isInstanceOf(RateLimitedException.class);
    }

    @Test
    @DisplayName("설정 검증: 기본값은 유효하고 0 이하는 거부된다(기동 시 바인딩 실패)")
    void propertiesValidation() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new MessageProperties())).isEmpty();

            MessageProperties zero = new MessageProperties();
            zero.setSearchPerMinute(0);
            zero.setAttemptPerMinute(-1);
            zero.setSendPerMinute(0);
            zero.setSendPerDay(0);
            zero.setMaxKeys(0);
            assertThat(validator.validate(zero)).hasSize(5);
        }
    }

    @Configuration
    static class TickerConfig {
        @Bean
        Ticker ticker() {
            return () -> 0L;
        }
    }

    @Test
    @DisplayName("공개 경로 제한을 꺼도(cms.rate-limit.enabled=false) 쪽지 제한(D17)은 유지된다 — 설정·Bean 모두 독립")
    void independentFromPublicRateLimitSwitch() {
        new ApplicationContextRunner()
                .withUserConfiguration(TickerConfig.class, MessageConfig.class)
                .withPropertyValues("cms.rate-limit.enabled=false", "cms.message.search-per-minute=1")
                .run(context -> {
                    MessageRateLimiter bean = context.getBean(MessageRateLimiter.class);

                    bean.consumeSearch(1L);
                    assertThatThrownBy(() -> bean.consumeSearch(1L)).isInstanceOf(RateLimitedException.class);
                });
    }

    @Test
    @DisplayName("0 이하 설정값이면 컨텍스트 기동이 실패한다(fail-fast)")
    void invalidPropertyFailsStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(TickerConfig.class, MessageConfig.class)
                .withPropertyValues("cms.message.attempt-per-minute=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
