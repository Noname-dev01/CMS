package com.cms.config.auth;

/**
 * 타 관리자 계정의 상태·권한 실변경(또는 멱등 재잠금) 시 서비스 트랜잭션 내에서 발행되는 이벤트.
 * 커밋 성공 후 {@link AdminSessionRevokeListener}가 대상자의 기존 세션을 만료 처리한다.
 * 트랜잭션이 롤백되면 소비되지 않는다(상태 변경이 없으므로 만료도 없음 — 정합).
 */
public record AdminSessionRevokeEvent(Long targetMemberId) {
}
