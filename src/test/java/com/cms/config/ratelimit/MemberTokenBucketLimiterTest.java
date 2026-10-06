package com.cms.config.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 회원 ID 키 토큰 버킷 — "버스트 N + 평균 N/주기" 계약(PLAN-admin-message.md D17, R2-7). */
class MemberTokenBucketLimiterTest {

    private final FakeTicker ticker = new FakeTicker();
    private final MemberTokenBucketLimiter limiter = new MemberTokenBucketLimiter(30, Duration.ofMinutes(1), 1_000, ticker);

    @Test
    @DisplayName("버스트: 시작 즉시 capacity(30)번까지 허용되고 31번째는 Retry-After(최소 1초)와 함께 거부된다")
    void burstThenReject() {
        for (int i = 0; i < 30; i++) {
            assertThat(limiter.tryConsume(1L).allowed()).as("%d번째", i + 1).isTrue();
        }

        RateLimitDecision rejected = limiter.tryConsume(1L);

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isBetween(1L, 2L); // 30/분 → 토큰 하나가 2초 뒤
    }

    @Test
    @DisplayName("회원마다 독립 버킷이다 — 한 회원이 한도를 써도 다른 회원은 영향이 없다(같은 IP·다른 회원 시나리오의 근거)")
    void membersAreIndependent() {
        for (int i = 0; i < 30; i++) {
            limiter.tryConsume(1L);
        }

        assertThat(limiter.tryConsume(1L).allowed()).isFalse();
        assertThat(limiter.tryConsume(2L).allowed()).isTrue();
    }

    @Test
    @DisplayName("같은 회원은 호출 경로(세션)와 무관하게 같은 버킷을 쓴다 — 키는 회원 ID뿐이다")
    void sameMemberSharesOneBucket() {
        for (int i = 0; i < 15; i++) {
            limiter.tryConsume(7L);
        }
        for (int i = 0; i < 15; i++) {
            limiter.tryConsume(7L);
        }

        assertThat(limiter.tryConsume(7L).allowed()).isFalse();
    }

    @Test
    @DisplayName("계약 정정(R2-7): 엄격한 '임의의 1분 30회'가 아니다 — 시작 즉시 30회 후 2초마다 1회면 첫 60초에 59회가 허용된다")
    void firstMinuteAllowsAtMostTwiceCapacityMinusOne() {
        int allowed = 0;
        for (int i = 0; i < 30; i++) {
            if (limiter.tryConsume(3L).allowed()) {
                allowed++;
            }
        }
        // 이후 2초마다 1회 시도 — 매 시도마다 토큰이 정확히 하나 리필돼 있다(60초 직전까지 29번: 2,4,...,58초)
        for (int second = 2; second < 60; second += 2) {
            ticker.advance(2, TimeUnit.SECONDS);
            if (limiter.tryConsume(3L).allowed()) {
                allowed++;
            }
        }

        assertThat(allowed).isEqualTo(59);
    }

    @Test
    @DisplayName("한 주기가 지나면 버킷이 가득 차 다시 capacity만큼 버스트가 가능하다")
    void refillsAfterOnePeriod() {
        for (int i = 0; i < 30; i++) {
            limiter.tryConsume(5L);
        }
        assertThat(limiter.tryConsume(5L).allowed()).isFalse();

        ticker.advance(1, TimeUnit.MINUTES);

        for (int i = 0; i < 30; i++) {
            assertThat(limiter.tryConsume(5L).allowed()).as("%d번째", i + 1).isTrue();
        }
        assertThat(limiter.tryConsume(5L).allowed()).isFalse();
    }

    @Test
    @DisplayName("유휴 한 주기가 지난 버킷은 캐시에서 만료된다(이미 가득 찬 상태라 지워도 토큰을 뺏지 않는다)")
    void idleBucketsExpire() {
        limiter.tryConsume(9L);
        assertThat(limiter.estimatedSize()).isEqualTo(1);

        ticker.advance(61, TimeUnit.SECONDS);
        limiter.cleanUp();

        assertThat(limiter.estimatedSize()).isZero();
    }

    @Test
    @DisplayName("잘못된 설정(0 이하)은 생성 시점에 거부된다")
    void invalidConfigurationRejected() {
        assertThatThrownBy(() -> new MemberTokenBucketLimiter(0, Duration.ofMinutes(1), 10, ticker))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemberTokenBucketLimiter(30, Duration.ZERO, 10, ticker))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemberTokenBucketLimiter(30, Duration.ofMinutes(1), 0, ticker))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
