package com.cms.publicweb.support;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 공개 파일 전송의 동시 슬롯 카운터(PLAN-public-transfer-limit.md D3·D4). 전체 수와 IP별 수를 함께 바꾸므로 한 잠금으로 묶는다 — 임계 구역은 맵 연산뿐이고
 * IO를 하지 않는다. 키는 슬롯이 있을 때만 있고 0이 되면 지우므로 맵 크기는 전체 상한을 넘지 않는다. 단일 인스턴스 전제다(기존 레이트리밋과 같다).
 *
 * <p>거절 우선순위는 IP별 → 전체다. 두 상한이 함께 차 있으면 {@link Decision#IP_LIMIT}이다.
 */
@Slf4j
public class PublicTransferLimiter {

    public enum Decision { ACQUIRED, IP_LIMIT, TOTAL_LIMIT }

    /** 거절 WARN은 분당 최대 1회다 — 포화 상태에서 로그가 넘치지 않게 한다. 저장·조회 시각이 아니라 경과 시간만 재므로 {@code nanoTime}을 쓴다. */
    private static final long WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final int maxTotal;
    private final int maxPerIp;
    private final Map<String, Integer> perIp = new HashMap<>();
    private int total;
    private long rejectedSinceWarn;
    private long lastWarnNanos = System.nanoTime() - WARN_INTERVAL_NANOS;

    public PublicTransferLimiter(int maxTotal, int maxPerIp) {
        this.maxTotal = maxTotal;
        this.maxPerIp = maxPerIp;
    }

    public synchronized Decision tryAcquire(String ip) {
        if (perIp.getOrDefault(ip, 0) >= maxPerIp) {
            recordRejection();
            return Decision.IP_LIMIT;
        }
        if (total >= maxTotal) {
            recordRejection();
            return Decision.TOTAL_LIMIT;
        }
        perIp.merge(ip, 1, Integer::sum);
        total++;
        return Decision.ACQUIRED;
    }

    /** {@link Decision#ACQUIRED}를 받은 호출마다 정확히 한 번, 획득 때의 IP로 부른다. */
    public synchronized void release(String ip) {
        perIp.computeIfPresent(ip, (key, count) -> count > 1 ? count - 1 : null);
        total--;
    }

    public synchronized int inUse() {
        return total;
    }

    synchronized int trackedIps() {
        return perIp.size();
    }

    private void recordRejection() {
        rejectedSinceWarn++;
        long now = System.nanoTime();
        if (now - lastWarnNanos >= WARN_INTERVAL_NANOS) {
            log.warn("공개 파일 전송 상한에 도달해 요청을 거절했다: 거절 {}건, 사용 중 {}/{}", rejectedSinceWarn, total, maxTotal);
            rejectedSinceWarn = 0;
            lastWarnNanos = now;
        }
    }
}
