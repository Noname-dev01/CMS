package com.cms.admin.board.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.Semaphore;

/**
 * 공개 조회수·다운로드 수 집계 진입점(PLAN-view-download-count.md). <b>이 클래스에는 {@code @Transactional}을 두지 않는다</b> — 트랜잭션 시작·커밋
 * 실패까지 {@link ContentCounterWriter} 호출 전체를 감싸는 try/catch가 잡아야 해서다. 카운터는 최선 노력 지표이며, 어떤 실패도 공개 응답에
 * 영향을 주지 않는다(예외 클래스명만 WARN — 메시지·IP는 남기지 않는다).
 *
 * <p>중복 억제: 같은 (종류, 대상, IP)는 {@link #DEDUPE_WINDOW} 안에 한 번만 센다. 프로세스 메모리 캐시라 재기동하면 비고, 가득 차면 오래된 키가
 * 밀려나 중복 집계가 늘 수 있다(fail-open, 레이트리밋과 같은 수용 방침). IP는 {@code getRemoteAddr()}라 프록시 뒤에서는 한 IP로 보인다(수용).
 * 호출 전에 공개 판정이 끝나 있어야 한다 — 이 클래스는 대상이 공개 상태인지 보지 않는다.
 */
@Slf4j
@Service
public class ContentCounterService {

    static final Duration DEDUPE_WINDOW = Duration.ofMinutes(30);
    static final long MAX_KEYS = 100_000;
    /** 동시에 DB에 쓰는 집계 수의 상한 — 공용 풀(기본 10)의 일부만 쓰게 한다. */
    static final int MAX_CONCURRENT_WRITES = 3;

    private final ContentCounterWriter writer;
    private final Cache<String, Boolean> seen;
    private final Semaphore permits = new Semaphore(MAX_CONCURRENT_WRITES);

    @Autowired
    public ContentCounterService(ContentCounterWriter writer) {
        this(writer, Ticker.systemTicker());
    }

    ContentCounterService(ContentCounterWriter writer, Ticker ticker) {
        this.writer = writer;
        this.seen = Caffeine.newBuilder()
                .maximumSize(MAX_KEYS)
                .expireAfterWrite(DEDUPE_WINDOW)
                .ticker(ticker)
                .build();
    }

    public void recordView(Long postId, String remoteAddr) {
        record("V", postId, remoteAddr, () -> writer.incrementView(postId), "조회수");
    }

    public void recordDownload(Long attachmentId, String remoteAddr) {
        record("D", attachmentId, remoteAddr, () -> writer.incrementDownload(attachmentId), "다운로드 수");
    }

    /**
     * 중복 억제 → 동시 실행 상한(대기 없음) → 쓰기 순이다. 상한은 관리자가 행을 잡은 동안 서로 다른 IP의 요청이 몰려도 카운터 UPDATE가 공용
     * DB 풀의 연결을 줄줄이 쥐고 락을 기다리지 못하게 한다(코드 리뷰 R1) — 자리가 없으면 기다리지 않고 이번 집계를 버리며, 억제 키도 되돌려
     * 다음 방문이 30분간 막히지 않게 한다.
     */
    private void record(String kind, Long id, String remoteAddr, Runnable write, String label) {
        if (id == null) {
            return;
        }
        String key = kind + ":" + id + ":" + remoteAddr;
        if (!firstSeen(key)) {
            return;
        }
        if (!permits.tryAcquire()) {
            seen.invalidate(key);
            return;
        }
        try {
            write.run();
        } catch (Exception e) {
            log.warn("{} 집계 실패 — 응답에는 영향 없음: {}", label, e.getClass().getSimpleName());
        } finally {
            permits.release();
        }
    }

    /** 처음 보는 키면 true(이후 창 안에서는 false). 캐시 자체가 실패해도 집계를 막지 않는다. */
    private boolean firstSeen(String key) {
        try {
            return seen.asMap().putIfAbsent(key, Boolean.TRUE) == null;
        } catch (Exception e) {
            return true;
        }
    }
}
