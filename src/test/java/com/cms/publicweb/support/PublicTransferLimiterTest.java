package com.cms.publicweb.support;

import com.cms.publicweb.support.PublicTransferLimiter.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 슬롯 카운터의 경계·거절 우선순위·반환 계약(PLAN-public-transfer-limit.md D3·D4·D5, F1). */
class PublicTransferLimiterTest {

    @Test
    @DisplayName("IP별 상한까지 획득하고 초과하면 IP_LIMIT이며 사용 수가 늘지 않는다")
    void perIpLimitBoundary() {
        PublicTransferLimiter limiter = new PublicTransferLimiter(10, 2);

        assertThat(limiter.tryAcquire("1.1.1.1")).isEqualTo(Decision.ACQUIRED);
        assertThat(limiter.tryAcquire("1.1.1.1")).isEqualTo(Decision.ACQUIRED);
        assertThat(limiter.tryAcquire("1.1.1.1")).isEqualTo(Decision.IP_LIMIT);

        assertThat(limiter.inUse()).isEqualTo(2);
    }

    @Test
    @DisplayName("전체 상한까지 획득하고 초과하면 자기 슬롯에 여유가 있는 IP도 TOTAL_LIMIT이다")
    void totalLimitBoundary() {
        PublicTransferLimiter limiter = new PublicTransferLimiter(2, 2);

        assertThat(limiter.tryAcquire("1.1.1.1")).isEqualTo(Decision.ACQUIRED);
        assertThat(limiter.tryAcquire("2.2.2.2")).isEqualTo(Decision.ACQUIRED);
        assertThat(limiter.tryAcquire("3.3.3.3")).isEqualTo(Decision.TOTAL_LIMIT);

        assertThat(limiter.inUse()).isEqualTo(2);
    }

    @Test
    @DisplayName("두 상한이 함께 차 있으면 IP_LIMIT이 우선한다")
    void ipLimitTakesPrecedenceOverTotalLimit() {
        PublicTransferLimiter limiter = new PublicTransferLimiter(2, 2);
        limiter.tryAcquire("1.1.1.1");
        limiter.tryAcquire("1.1.1.1");

        assertThat(limiter.tryAcquire("1.1.1.1")).isEqualTo(Decision.IP_LIMIT);
    }

    @Test
    @DisplayName("반환하면 그 IP와 전체 자리가 다시 열린다")
    void releaseReopensSlot() {
        PublicTransferLimiter limiter = new PublicTransferLimiter(1, 1);
        limiter.tryAcquire("1.1.1.1");

        limiter.release("1.1.1.1");

        assertThat(limiter.inUse()).isZero();
        assertThat(limiter.tryAcquire("2.2.2.2")).isEqualTo(Decision.ACQUIRED);
    }

    @Test
    @DisplayName("0이 된 IP 키는 맵에서 지워진다 — 맵 크기가 상한을 넘지 않는 근거")
    void emptyIpKeyIsRemoved() {
        PublicTransferLimiter limiter = new PublicTransferLimiter(10, 2);
        limiter.tryAcquire("1.1.1.1");
        limiter.tryAcquire("1.1.1.1");
        limiter.tryAcquire("2.2.2.2");

        limiter.release("1.1.1.1");
        assertThat(limiter.trackedIps()).isEqualTo(2);

        limiter.release("1.1.1.1");
        limiter.release("2.2.2.2");
        assertThat(limiter.trackedIps()).isZero();
    }

    @Test
    @DisplayName("동시 획득도 상한을 넘지 않고 모두 반환하면 0이다")
    void concurrentAcquireNeverExceedsLimit() throws Exception {
        PublicTransferLimiter limiter = new PublicTransferLimiter(8, 8);
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Decision>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                String ip = "10.0.0." + (i % 4);
                results.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return limiter.tryAcquire(ip);
                }));
            }
            start.countDown();

            List<String> acquiredIps = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                if (results.get(i).get(5, TimeUnit.SECONDS) == Decision.ACQUIRED) {
                    acquiredIps.add("10.0.0." + (i % 4));
                }
            }

            assertThat(acquiredIps).hasSize(8);
            assertThat(limiter.inUse()).isEqualTo(8);
            acquiredIps.forEach(limiter::release);
            assertThat(limiter.inUse()).isZero();
            assertThat(limiter.trackedIps()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }
}
