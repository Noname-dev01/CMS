package com.cms.config.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TokenBucketRateLimiter}(Caffeine 래퍼) + {@link Bucket}(토큰 버킷 알고리즘) 단위 테스트.
 * PLAN-public-endpoint-rate-limit.md 여러 라운드에서 발견된 회귀(장기 유휴 크레딧 누적, 음수
 * nanoTime, 초기 토큰 0 등)를 재발 방지 테스트로 고정한다.
 */
class TokenBucketRateLimiterTest {

    private static final int CAPACITY = 5;
    private static final Duration REFILL_PERIOD = Duration.ofSeconds(60);

    private RateLimitProperties.RuleConfig ruleConfig(String id, int capacity, Duration refillPeriod) {
        RateLimitProperties.RuleConfig config = new RateLimitProperties.RuleConfig();
        config.setId(id);
        config.setPattern("/notices/**");
        config.setMethods(Set.of("GET"));
        config.setCapacity(capacity);
        config.setRefillPeriodSeconds(refillPeriod.toSeconds());
        return config;
    }

    private RateLimitProperties properties(int maxKeys, RateLimitProperties.RuleConfig... rules) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setMaxKeys(maxKeys);
        properties.setRules(List.of(rules));
        return properties;
    }

    private RateLimitRule rule(TokenBucketRateLimiter limiter, String id) {
        return limiter.rules().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("신규 버킷은 시간 전진 없이 정확히 capacity회 허용, 그다음은 거절 (초기 tokens=capacity 회귀 방지)")
    void newBucket_allowsExactlyCapacity_thenRejects() {
        FakeTicker ticker = new FakeTicker();
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(properties(100, ruleConfig("r", CAPACITY, REFILL_PERIOD)), ticker);
        RateLimitRule rule = rule(limiter, "r");

        for (int i = 0; i < CAPACITY; i++) {
            assertThat(limiter.tryConsume("1.2.3.4", rule).allowed()).as("allow #" + i).isTrue();
        }
        RateLimitDecision rejected = limiter.tryConsume("1.2.3.4", rule);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("부분 토큰 보존 — 절반 경과 시 약 절반만 리필된다")
    void partialElapsed_partiallyRefills() {
        FakeTicker ticker = new FakeTicker();
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(properties(100, ruleConfig("r", CAPACITY, REFILL_PERIOD)), ticker);
        RateLimitRule rule = rule(limiter, "r");

        for (int i = 0; i < CAPACITY; i++) {
            limiter.tryConsume("1.2.3.4", rule);
        }
        assertThat(limiter.tryConsume("1.2.3.4", rule).allowed()).isFalse();

        // 리필 주기의 1/capacity(=1토큰 분량)만큼 전진 → 정확히 1개만 추가 허용.
        ticker.advance(REFILL_PERIOD.toSeconds() / CAPACITY, TimeUnit.SECONDS);
        assertThat(limiter.tryConsume("1.2.3.4", rule).allowed()).isTrue();
        assertThat(limiter.tryConsume("1.2.3.4", rule).allowed()).isFalse();
    }

    @Test
    @DisplayName("장기 유휴(리필 주기의 여러 배) 후에도 정확히 capacity개만 허용 — 초과 유휴시간이 크레딧으로 남지 않는다")
    void longIdle_doesNotAccumulateExtraCredit() {
        FakeTicker ticker = new FakeTicker();
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(properties(100, ruleConfig("r", CAPACITY, REFILL_PERIOD)), ticker);
        RateLimitRule rule = rule(limiter, "r");

        for (int i = 0; i < CAPACITY; i++) {
            limiter.tryConsume("1.2.3.4", rule);
        }
        // 리필 주기의 10배만큼 장기간 유휴.
        ticker.advance(REFILL_PERIOD.toSeconds() * 10, TimeUnit.SECONDS);

        for (int i = 0; i < CAPACITY; i++) {
            assertThat(limiter.tryConsume("1.2.3.4", rule).allowed()).as("allow #" + i).isTrue();
        }
        // 소비 직후 즉시 재요청 — 크레딧이 남아있었다면 여기서도 허용됐을 것.
        assertThat(limiter.tryConsume("1.2.3.4", rule).allowed()).isFalse();
    }

    @Test
    @DisplayName("Ticker가 큰 음수값에서 시작해도 정상 동작한다 (lazy 초기화 회귀 방지)")
    void negativeInitialTicker_worksCorrectly() {
        FakeTicker ticker = new FakeTicker(-10_000_000_000_000L);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(properties(100, ruleConfig("r", CAPACITY, REFILL_PERIOD)), ticker);
        RateLimitRule rule = rule(limiter, "r");

        for (int i = 0; i < CAPACITY; i++) {
            assertThat(limiter.tryConsume("1.2.3.4", rule).allowed()).as("allow #" + i).isTrue();
        }
        assertThat(limiter.tryConsume("1.2.3.4", rule).allowed()).isFalse();

        ticker.advance(REFILL_PERIOD.toSeconds(), TimeUnit.SECONDS);
        assertThat(limiter.tryConsume("1.2.3.4", rule).allowed()).isTrue();
    }

    @Test
    @DisplayName("같은 IP라도 규칙이 다르면 독립된 버킷을 쓴다")
    void differentRules_haveIndependentBuckets() {
        FakeTicker ticker = new FakeTicker();
        RateLimitProperties.RuleConfig ruleA = ruleConfig("rule-a", 2, REFILL_PERIOD);
        RateLimitProperties.RuleConfig ruleB = ruleConfig("rule-b", 2, REFILL_PERIOD);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(properties(100, ruleA, ruleB), ticker);
        RateLimitRule a = rule(limiter, "rule-a");
        RateLimitRule b = rule(limiter, "rule-b");

        limiter.tryConsume("9.9.9.9", a);
        limiter.tryConsume("9.9.9.9", a);
        assertThat(limiter.tryConsume("9.9.9.9", a).allowed()).isFalse();

        // 규칙 a가 소진됐어도 규칙 b는 별도 버킷이라 정상 허용.
        assertThat(limiter.tryConsume("9.9.9.9", b).allowed()).isTrue();
    }

    @Test
    @DisplayName("동시 소비 시 정확히 capacity개만 허용된다")
    void concurrentConsumption_allowsExactlyCapacity() throws InterruptedException {
        FakeTicker ticker = new FakeTicker();
        int capacity = 20;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(properties(100, ruleConfig("r", capacity, REFILL_PERIOD)), ticker);
        RateLimitRule rule = rule(limiter, "r");

        int threadCount = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (limiter.tryConsume("8.8.8.8", rule).allowed()) {
                    allowedCount.incrementAndGet();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(allowedCount.get()).isEqualTo(capacity);
    }

    @Test
    @DisplayName("만료 경계 — refillPeriod - 1ns는 유지된다 (별도 fixture)")
    void expiry_justBeforePeriod_stillPresent() {
        FakeTicker ticker = new FakeTicker();
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(properties(100, ruleConfig("r", CAPACITY, REFILL_PERIOD)), ticker);
        RateLimitRule rule = rule(limiter, "r");

        limiter.tryConsume("1.1.1.1", rule); // 버킷 생성, tokens = capacity - 1
        ticker.advance(REFILL_PERIOD.toNanos() - 1, TimeUnit.NANOSECONDS);
        limiter.cleanUp();

        // 엔트리가 아직 만료 전이므로 소비가 이어져(만료로 리셋되지 않고 4개 남은 상태에서) 계속 허용된다.
        for (int i = 0; i < CAPACITY - 1; i++) {
            assertThat(limiter.tryConsume("1.1.1.1", rule).allowed()).as("allow #" + i).isTrue();
        }
    }

    @Test
    @DisplayName("만료 경계 — 정확히 refillPeriod 경과 시 논리적 만료 + cleanUp() 후 물리 제거 + 신규 Bucket 생성 (별도 fixture)")
    void expiry_exactlyAtPeriod_evictsAndRecreates() {
        FakeTicker ticker = new FakeTicker();
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(properties(100, ruleConfig("r", CAPACITY, REFILL_PERIOD)), ticker);
        RateLimitRule rule = rule(limiter, "r");

        for (int i = 0; i < CAPACITY; i++) {
            limiter.tryConsume("2.2.2.2", rule); // 완전히 소진
        }
        assertThat(limiter.tryConsume("2.2.2.2", rule).allowed()).isFalse();

        ticker.advance(REFILL_PERIOD.toNanos(), TimeUnit.NANOSECONDS);
        limiter.cleanUp();
        assertThat(limiter.estimatedSize()).isZero();

        // 만료로 물리 제거된 뒤 재접근 → 신규 Bucket(가득 참)이 생성되어 다시 capacity회 허용.
        for (int i = 0; i < CAPACITY; i++) {
            assertThat(limiter.tryConsume("2.2.2.2", rule).allowed()).as("allow #" + i).isTrue();
        }
    }

    @Test
    @DisplayName("maximumSize 도달 시에도 예외 없이 정상 동작한다 (fail-open — 완전한 정확성은 검증 대상 아님)")
    void maximumSizeReached_doesNotThrow() {
        FakeTicker ticker = new FakeTicker();
        int maxKeys = 3;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(properties(maxKeys, ruleConfig("r", CAPACITY, REFILL_PERIOD)), ticker);
        RateLimitRule rule = rule(limiter, "r");

        // maxKeys를 넘는 서로 다른 IP로 요청해도 예외 없이 항상 판정을 반환해야 한다.
        for (int i = 0; i < maxKeys * 5; i++) {
            RateLimitDecision decision = limiter.tryConsume("10.0.0." + i, rule);
            assertThat(decision).isNotNull();
        }
    }
}
