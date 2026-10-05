package com.cms.config.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

import java.time.Duration;

/**
 * 인증된 회원 ID를 키로 하는 단일 규칙 토큰 버킷 제한기(쪽지 검색·발송 시도 남용 제한 — PLAN-admin-message.md D17).
 *
 * <p>공개 경로 필터({@link RateLimitFilter}·{@link TokenBucketRateLimiter})와 <b>분리된</b> 컴포넌트다 — 그 필터는 IP를
 * 키로 소비하므로 쪽지 규칙을 공개 규칙 목록에 넣으면 같은 사무실 IP의 여러 회원이 서로를 차단하는, 승인되지 않은 IP 제한이 생기고
 * {@code cms.rate-limit.enabled=false}가 이 제한까지 끄게 된다(R2-6). {@link Bucket}(토큰 버킷 알고리즘)과 캐시 구성
 * (Caffeine {@code maximumSize}+버킷별 {@link Expiry})만 같은 방식으로 재사용한다.
 *
 * <p><b>계약</b>: "버스트 {@code capacity} + 평균 {@code capacity}/리필 주기"다. 엄격한 슬라이딩 상한이 아니다 — 시작 즉시
 * {@code capacity}개를 소비한 뒤 리필되는 속도로 계속 소비하면 첫 주기에 최대 {@code 2 × capacity − 1}회까지 허용된다
 * (30/분이면 첫 60초 최대 59회, R2-7). 단일 인스턴스·fail-open(캐시 포화 시 개별 키의 정확한 누적치 보장이 흐트러질 수 있음)을
 * 수용한다 — 다중 인스턴스 정확 제한은 하지 않는다.
 */
public class MemberTokenBucketLimiter {

    private final int capacity;
    private final long refillPeriodNanos;
    private final Cache<Long, Bucket> buckets;
    private final Ticker ticker;

    public MemberTokenBucketLimiter(int capacity, Duration refillPeriod, long maxKeys, Ticker ticker) {
        if (capacity <= 0 || refillPeriod.isZero() || refillPeriod.isNegative() || maxKeys <= 0) {
            throw new IllegalArgumentException("capacity·refillPeriod·maxKeys는 양수여야 합니다.");
        }
        this.capacity = capacity;
        this.refillPeriodNanos = refillPeriod.toNanos();
        this.ticker = ticker;
        this.buckets = Caffeine.newBuilder()
                .ticker(ticker::nanos)
                .maximumSize(maxKeys)
                .expireAfter(new Expiry<Long, Bucket>() {
                    @Override
                    public long expireAfterCreate(Long key, Bucket bucket, long currentTime) {
                        return bucket.refillPeriodNanos();
                    }

                    @Override
                    public long expireAfterUpdate(Long key, Bucket bucket, long currentTime, long currentDuration) {
                        return bucket.refillPeriodNanos();
                    }

                    @Override
                    public long expireAfterRead(Long key, Bucket bucket, long currentTime, long currentDuration) {
                        return bucket.refillPeriodNanos();
                    }
                })
                .build();
    }

    /** 이 회원의 토큰 하나를 소비한다. 같은 회원은 세션·IP와 무관하게 같은 버킷을 쓴다. */
    public RateLimitDecision tryConsume(long memberId) {
        Bucket bucket = buckets.get(memberId, key -> new Bucket(capacity, refillPeriodNanos));
        return bucket.tryConsume(ticker);
    }

    /** 테스트 전용 — 만료 경계 검증에 필요한 캐시 유지보수 강제 실행·크기 확인. */
    void cleanUp() {
        buckets.cleanUp();
    }

    long estimatedSize() {
        return buckets.estimatedSize();
    }
}
