package com.cms.config.ratelimit;

/**
 * 레이트리밋 캐시 키. {@code ruleId + ":" + remoteAddr} 같은 문자열 연결 대신 record를 쓴다 —
 * {@code ruleId}에 구분자가 섞여도 값 기반 동등성이 안전하게 보장된다(equals/hashCode 자동 생성).
 *
 * <p>같은 IP가 서로 다른 규칙에 접근해도 {@code ruleId}가 다르므로 항상 별도 버킷을 받는다 —
 * 규칙 간 capacity/refillPeriod 설정이 서로 뒤섞이는 문제를 구조적으로 방지한다.
 */
public record BucketKey(String ruleId, String remoteAddr) {
}
