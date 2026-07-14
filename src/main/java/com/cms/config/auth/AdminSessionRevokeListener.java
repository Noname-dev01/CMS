package com.cms.config.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// @Order(10): 자동 잠금 감사 리스너(@Order(20))보다 먼저 — 세션 만료(인메모리)가
// 감사 저장(DB) 지연에 막히지 않는다. (서로 다른 이벤트 타입 간 실제 순서는 발행 순서가
// 결정하므로 LoginFailureService가 세션 폐기 이벤트를 먼저 발행한다 — @Order는 계약 명시용)
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class AdminSessionRevokeListener {

    private final AdminSessionService adminSessionService;

    /**
     * 커밋 성공 후에만 대상자 세션을 만료한다(AFTER_COMMIT).
     * 트랜잭션 내 만료는 "만료 후·커밋 전" 창에서 재로그인한 세션이 살아남는 레이스가 있어 금지.
     *
     * <p>커밋 후 실패는 이미 반환된 성공 응답을 뒤집을 수 없으므로 ERROR 로그 기록이 필수 계약이며,
     * 예외는 전파하지 않는다. 운영 복구 경로: 같은 값으로 재저장(멱등 재잠금)하면 만료가 재시도된다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRevoke(AdminSessionRevokeEvent event) {
        try {
            adminSessionService.expireSessionsFor(event.targetMemberId());
        } catch (Exception e) {
            log.error("대상자 세션 만료 실패 — 같은 값으로 재저장(멱등 재잠금)하면 재시도됩니다. targetMemberId={}",
                    event.targetMemberId(), e);
        }
    }
}
