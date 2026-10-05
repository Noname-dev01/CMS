package com.cms.admin.message.service;

import com.cms.admin.message.config.MessageProperties;
import com.cms.common.exception.RateLimitedException;
import com.cms.config.ratelimit.MemberTokenBucketLimiter;
import com.cms.config.ratelimit.RateLimitDecision;
import com.cms.config.ratelimit.Ticker;

import java.time.Duration;

/**
 * 쪽지의 수신자 검색·발송 시도 남용 제한(PLAN-admin-message.md D17, R1-7·R2-6·R2-7) — 인증 회원 ID 키의 인메모리 토큰 버킷.
 * 규칙 ID는 {@link #SEARCH_RULE}·{@link #ATTEMPT_RULE}이며 두 버킷은 서로 독립이다. 공개 경로 필터와 분리돼 있어
 * {@code cms.rate-limit.enabled=false}에도 유지된다. <b>버스트 N + 평균 분당 N</b> 계약이며(첫 60초 최대 2N−1회) 단일 인스턴스·
 * fail-open을 수용한다. 검색은 요청당 <b>한 번만</b> 소비한다(정확 일치·부분 검색 두 조회가 각각 소비하지 않는다).
 *
 * <p>발송 한도(최근 1분·24시간, 삭제와 무관한 DB 이력)는 이 클래스가 아니라 발송 트랜잭션이 센다.
 */
public class MessageRateLimiter {

    public static final String SEARCH_RULE = "message-search";
    public static final String ATTEMPT_RULE = "message-attempt";

    /** 고정 문구 — 사용자 입력을 담지 않는다. */
    static final String TOO_MANY_REQUESTS_MESSAGE = "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.";

    private final MemberTokenBucketLimiter search;
    private final MemberTokenBucketLimiter attempt;

    public MessageRateLimiter(MessageProperties properties, Ticker ticker) {
        this.search = new MemberTokenBucketLimiter(
                properties.getSearchPerMinute(), Duration.ofMinutes(1), properties.getMaxKeys(), ticker);
        this.attempt = new MemberTokenBucketLimiter(
                properties.getAttemptPerMinute(), Duration.ofMinutes(1), properties.getMaxKeys(), ticker);
    }

    /** 수신자 검색 한 요청이 토큰 하나를 쓴다. 한도 초과면 {@link RateLimitedException}(429). */
    public void consumeSearch(long memberId) {
        require(search.tryConsume(memberId));
    }

    /** 발송 시도(수신자 확인 포함) 한 요청이 토큰 하나를 쓴다 — 거부된 시도도 소비한다. 한도 초과면 {@link RateLimitedException}. */
    public void consumeAttempt(long memberId) {
        require(attempt.tryConsume(memberId));
    }

    private static void require(RateLimitDecision decision) {
        if (!decision.allowed()) {
            throw new RateLimitedException(TOO_MANY_REQUESTS_MESSAGE, decision.retryAfterSeconds());
        }
    }
}
