package com.cms.admin.member.service;

/**
 * 2단계 인증이 실제로 바뀌었을 때(등록·본인 해제·ADMIN 해제) 트랜잭션 안에서 발행한다. 커밋 뒤
 * {@link MemberTotpAuditListener}가 감사 행을 남긴다 — {@code @AdminActionLogged}는 정상 반환이면 실패·무변경도 성공으로
 * 기록하므로 쓰지 않는다(PLAN-totp-two-factor.md 결정 4).
 */
public record MemberTotpAuditEvent(String actionType, Long actorMemberId, String actorUserId, Long targetMemberId,
                                   String requestIp, String requestUri, String requestMethod) {
}
