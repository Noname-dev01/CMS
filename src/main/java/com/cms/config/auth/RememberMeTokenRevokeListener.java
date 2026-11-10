package com.cms.config.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 역할·상태 변경, 본인 비밀번호 변경, 재설정, 자동 잠금({@link AdminSessionRevokeEvent})이 일어나면 그 회원의 remember-me 토큰을
 * <b>원 트랜잭션 안에서</b> 모두 지운다(PLAN-remember-me-login.md 쟁점 3). 세션 만료는 기존대로 커밋 뒤 {@link AdminSessionRevokeListener}가 한다.
 *
 * <p>BEFORE_COMMIT인 이유: AFTER_COMMIT에서의 JDBC 쓰기는 이미 커밋된 트랜잭션의 연결에 참여해 확정되지 않을 수 있다.
 * 같은 트랜잭션이라 회원 변경과 원자적이고, 삭제가 실패하면 원 변경도 롤백돼 요청이 실패한다(fail-closed — 그대로 재시도할 수 있다).
 * 토큰 삭제가 커밋된 뒤에야 세션이 만료되므로 자동 로그인 사후 검증과 경합하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class RememberMeTokenRevokeListener {

    private final RememberMeTokenRepository tokens;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onRevoke(AdminSessionRevokeEvent event) {
        tokens.removeAllForMember(event.targetMemberId());
    }
}
