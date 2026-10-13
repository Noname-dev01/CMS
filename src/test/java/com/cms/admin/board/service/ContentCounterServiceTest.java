package com.cms.admin.board.service;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 중복 억제 창·예외 격리(PLAN-view-download-count.md 쟁점 3·5). 시각은 가짜 Ticker로 움직인다. */
class ContentCounterServiceTest {

    private ContentCounterWriter writer;
    private AtomicLong nanos;
    private ContentCounterService service;

    @BeforeEach
    void setUp() {
        writer = mock(ContentCounterWriter.class);
        nanos = new AtomicLong();
        Ticker ticker = nanos::get;
        service = new ContentCounterService(writer, ticker);
    }

    @Test
    @DisplayName("같은 IP·같은 글은 30분 안에 한 번만 센다")
    void sameIpSamePost_countedOnceInWindow() {
        service.recordView(1L, "1.1.1.1");
        service.recordView(1L, "1.1.1.1");
        verify(writer, times(1)).incrementView(1L);
    }

    @Test
    @DisplayName("다른 IP·다른 글·다른 종류(조회/다운로드)는 각각 센다")
    void differentKeys_countedSeparately() {
        service.recordView(1L, "1.1.1.1");
        service.recordView(1L, "2.2.2.2");
        service.recordView(2L, "1.1.1.1");
        service.recordDownload(1L, "1.1.1.1");
        verify(writer, times(3)).incrementView(any());
        verify(writer, times(1)).incrementDownload(1L);
    }

    @Test
    @DisplayName("창이 지나면 같은 IP도 다시 센다")
    void afterWindow_countedAgain() {
        service.recordDownload(5L, "1.1.1.1");
        nanos.addAndGet(ContentCounterService.DEDUPE_WINDOW.toNanos() - 1);
        service.recordDownload(5L, "1.1.1.1");
        verify(writer, times(1)).incrementDownload(5L);

        nanos.addAndGet(2);
        service.recordDownload(5L, "1.1.1.1");
        verify(writer, times(2)).incrementDownload(5L);
    }

    @Test
    @DisplayName("writer 예외(트랜잭션 시작·커밋 실패 포함)는 호출자에게 전파되지 않는다")
    void writerFailure_isSwallowed() {
        doThrow(new IllegalStateException("tx begin failed")).when(writer).incrementView(any());
        doThrow(new IllegalStateException("commit failed")).when(writer).incrementDownload(any());

        service.recordView(1L, "1.1.1.1");
        service.recordDownload(1L, "1.1.1.1");
    }

    @Test
    @DisplayName("동시 쓰기가 상한에 차면 기다리지 않고 이번 집계를 버리고, 억제 키도 되돌려 이후 방문은 센다 (공용 풀 점유 방지)")
    void saturated_dropsWithoutWaiting_andKeepsKeyRetryable() throws Exception {
        int max = ContentCounterService.MAX_CONCURRENT_WRITES;
        CountDownLatch inside = new CountDownLatch(max);
        CountDownLatch release = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            inside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(writer).incrementView(org.mockito.ArgumentMatchers.longThat(id -> id <= max));

        ExecutorService pool = Executors.newFixedThreadPool(max);
        for (long i = 1; i <= max; i++) {
            long postId = i;
            pool.submit(() -> service.recordView(postId, "9.9.9.9"));
        }
        assertThat(inside.await(5, TimeUnit.SECONDS)).as("상한만큼 쓰기가 진행 중").isTrue();

        long started = System.nanoTime();
        service.recordView(100L, "1.1.1.1");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("기다리지 않는다").isLessThan(1_000);
        verify(writer, never()).incrementView(100L);

        release.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        service.recordView(100L, "1.1.1.1");
        verify(writer, times(1)).incrementView(100L);
    }

    @Test
    @DisplayName("null 대상은 무시한다")
    void nullTarget_ignored() {
        service.recordView(null, "1.1.1.1");
        service.recordDownload(null, "1.1.1.1");
        verify(writer, never()).incrementView(any());
        verify(writer, never()).incrementDownload(any());
    }
}
