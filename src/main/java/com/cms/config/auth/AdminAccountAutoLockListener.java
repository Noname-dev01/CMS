package com.cms.config.auth;

import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.log.domain.AdminActionResult;
import com.cms.admin.log.service.AdminActionLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 계정 자동 잠금(AFTER_COMMIT) 감사 기록 리스너.
 *
 * <p>감사를 잠금 트랜잭션 안에서 직접 호출하지 않는 이유:
 * {@code AdminActionLogService.log()}는 REQUIRES_NEW라 원 트랜잭션이 롤백돼도
 * "잠금 성공" 감사가 남는 불일치가 생기고, 반대로 감사 예외가 전파되면 잠금이 롤백된다.
 * 커밋 후 실행으로 양방향을 격리한다 — 롤백 시 감사 미생성, 감사 실패 시 잠금 불변.
 *
 * <p>실행 순서: 발행 순서상 {@link AdminSessionRevokeEvent}가 먼저 재생되고
 * {@code @Order}로도 세션 만료(인메모리)가 감사 저장(DB) 지연에 막히지 않음을 명시한다.
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class AdminAccountAutoLockListener {

    private final AdminActionLogService adminActionLogService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAutoLock(AdminAccountAutoLockEvent event) {
        log.warn("로그인 연속 실패로 계정 자동 잠금 (userId={}, memberId={})", event.userId(), event.memberId());
        try {
            adminActionLogService.log(
                    null,                              // actionId — 미인증 흐름
                    null,                              // actionUserId — 미인증 흐름
                    AdminActionTypes.ACCOUNT_AUTO_LOCK,
                    AdminActionResult.SUCCESS,         // "자동 잠금 전이 성공" 이벤트 (로그인 실패 로그가 아님)
                    "MEMBER",
                    event.memberId(),
                    event.requestIp(),
                    event.requestUri(),
                    "POST",
                    null
            );
        } catch (Exception e) {
            // 커밋 후라 잠금 자체는 이미 확정 — 감사 실패는 로그로만 추적하고 전파하지 않는다
            log.error("계정 자동 잠금 감사 기록 실패 (memberId={})", event.memberId(), e);
        }
    }
}
