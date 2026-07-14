package com.cms.config.auth;

/**
 * 로그인 연속 실패로 계정이 LOCKED로 자동 전이될 때 잠금 트랜잭션 내에서 발행되는 이벤트.
 * 커밋 성공 후 {@link AdminAccountAutoLockListener}가 감사 로그를 기록한다.
 * 트랜잭션이 롤백되면 소비되지 않는다(잠금이 없으므로 감사도 없음 — 정합).
 *
 * <p>같은 트랜잭션에서 {@link AdminSessionRevokeEvent}를 이 이벤트보다 먼저 발행한다 —
 * AFTER_COMMIT 리스너는 이벤트 발행 순서대로 재생되므로, 세션 만료(인메모리)가
 * 감사 저장(DB)의 지연에 막히지 않는다.
 *
 * @param requestIp  실패 요청 IP (핸들러에서 컬럼 길이로 절단해 전달)
 * @param requestUri 실패 요청 URI (동일)
 */
public record AdminAccountAutoLockEvent(Long memberId, String userId, String requestIp, String requestUri) {
}
