package com.cms.config.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link RateLimitProperties}를 해석한 규칙 목록 + Caffeine 기반 버킷 저장소.
 *
 * <p><b>저장소 설계(PLAN-public-endpoint-rate-limit.md 쟁점 3)</b>: {@code maximumSize}로
 * 크기 상한을 두고, 버킷마다 자기 규칙의 {@code refillPeriod}를 만료 시간으로 쓰는
 * {@link Expiry} 커스텀 구현을 결합한다 — "마지막 접근 후 자기 규칙의 전체 리필 기간만큼
 * 유휴였던 버킷은 이미 완전히 리필된 상태였을 것"이므로 그 시점에 지워도 실제로 뺏기는
 * 토큰이 없다(안전한 만료). 전역 고정 TTL(과거 버그 — 짧은 TTL이 긴 규칙을 우회)도,
 * 무기한 미만료(캐시가 한 번 포화되면 계속 포화 상태로 남는 문제)도 피한다.
 *
 * <p><b>fail-open 명시 수용</b>: Caffeine은 순수 LRU가 아니라 W-TinyLFU라서 상한 도달 시
 * 신규 엔트리가 거부되거나, eviction·expiration이 in-flight 참조와 겹치면 개별 IP의 정확한
 * 누적치가 흐트러질 수 있다. 이 계획은 "무제한 요청을 값싸게 차단하는 최소 방어"를 목표로
 * 하므로 이 잔여 위험을 운영 위험으로 명시 수용한다(정확한 유량 계약을 보장하는 게이트웨이가
 * 아니다) — 자세한 트레이드오프는 계획 문서 쟁점 3 참조.
 *
 * <p>Caffeine builder에 {@link Ticker}(운영에서는 {@link SystemTicker}, 테스트에서는 가짜
 * 구현)를 {@link Bucket}과 동일하게 연결한다 — 그렇지 않으면 테스트에서 버킷 리필은
 * 검증되어도 캐시 만료는 전혀 검증되지 않는 시간원 불일치가 생긴다.
 */
public class TokenBucketRateLimiter {

    private final List<RateLimitRule> rules;
    private final Cache<BucketKey, Bucket> buckets;
    private final Ticker ticker;

    public TokenBucketRateLimiter(RateLimitProperties properties, Ticker ticker) {
        this.ticker = ticker;
        this.rules = properties.getRules().stream()
                .map(TokenBucketRateLimiter::toRule)
                .collect(Collectors.toUnmodifiableList());
        this.buckets = Caffeine.newBuilder()
                .ticker(ticker::nanos)
                .maximumSize(properties.getMaxKeys())
                .expireAfter(new Expiry<BucketKey, Bucket>() {
                    @Override
                    public long expireAfterCreate(BucketKey key, Bucket bucket, long currentTime) {
                        return bucket.refillPeriodNanos();
                    }

                    @Override
                    public long expireAfterUpdate(BucketKey key, Bucket bucket, long currentTime, long currentDuration) {
                        return bucket.refillPeriodNanos();
                    }

                    @Override
                    public long expireAfterRead(BucketKey key, Bucket bucket, long currentTime, long currentDuration) {
                        return bucket.refillPeriodNanos();
                    }
                })
                .build();
    }

    private static RateLimitRule toRule(RateLimitProperties.RuleConfig config) {
        Set<HttpMethod> methods = config.getMethods().stream()
                .map(HttpMethod::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        return new RateLimitRule(
                config.getId(),
                PathPatternRequestMatcher.withDefaults().matcher(config.getPattern()),
                methods,
                config.getCapacity(),
                java.time.Duration.ofSeconds(config.getRefillPeriodSeconds()));
    }

    /** 선언 순서 그대로의 규칙 목록. 필터가 첫 매칭 규칙 하나만 적용하는 데 쓴다(쟁점 8). */
    public List<RateLimitRule> rules() {
        return rules;
    }

    public RateLimitDecision tryConsume(String remoteAddr, RateLimitRule rule) {
        Bucket bucket = buckets.get(
                new BucketKey(rule.id(), remoteAddr),
                key -> new Bucket(rule.capacity(), rule.refillPeriod().toNanos()));
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
