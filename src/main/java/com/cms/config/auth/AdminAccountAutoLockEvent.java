package com.cms.config.auth;

import java.time.LocalDateTime;

/**
 * 로그인 연속 실패로 계정이 LOCKED로 자동 전이될 때 잠금 트랜잭션 내에서 발행되는 이벤트.
 * 커밋 성공 후 {@link AdminAccountAutoLockListener}가 감사 로그를, 알림 리스너가 알림(E1·E4)을 기록한다.
 * 트랜잭션이 롤백되면 소비되지 않는다(잠금이 없으므로 감사·알림도 없음 — 정합).
 *
 * <p>같은 트랜잭션에서 {@link AdminSessionRevokeEvent}를 이 이벤트보다 먼저 발행한다 —
 * AFTER_COMMIT 리스너는 이벤트 발행 순서대로 재생되므로, 세션 만료(인메모리)가
 * 감사 저장(DB)의 지연에 막히지 않는다.
 *
 * @param userId          로그인 요청의 {@code username} <b>원문</b>(감사용). 대소문자·후행 공백 변형이 있을 수 있고 길이 제한이 없다 —
 *                        알림 메시지에는 쓰지 않는다(PLAN-admin-notification.md v3 R-1)
 * @param requestIp       실패 요청 IP (핸들러에서 컬럼 길이로 절단해 전달)
 * @param requestUri      실패 요청 URI (동일)
 * @param canonicalUserId DB에 저장된 정규 {@code member.userId}(컬럼 상한 50자) — 알림 메시지용
 * @param lockedAt        잠금이 전이된 시각(앱 Clock) — 리스너의 {@code now}나 커밋 후 재조회(이미 해제됐을 수 있다)를 쓰지 않도록 이벤트에 담는다
 */
public record AdminAccountAutoLockEvent(Long memberId, String userId, String requestIp, String requestUri,
                                        String canonicalUserId, LocalDateTime lockedAt) {
}
