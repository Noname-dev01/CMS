package com.cms.admin.member.service;

import com.cms.admin.log.domain.AdminActionResult;
import com.cms.admin.log.service.AdminActionLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 2단계 인증 변경 감사(AFTER_COMMIT) — 커밋된 변경만 기록하고, 감사 실패는 변경을 되돌리지 않는다
 * ({@code AdminAccountAutoLockListener}와 같은 격리). 실행 순서는 세션 만료(10) 뒤·알림(100) 앞.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberTotpAuditListener {

    private final AdminActionLogService adminActionLogService;

    @Order(20) // 메서드 리스너는 메서드의 @Order만 읽는다
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTotpChanged(MemberTotpAuditEvent event) {
        try {
            adminActionLogService.log(
                    event.actorMemberId(),
                    event.actorUserId(),
                    event.actionType(),
                    AdminActionResult.SUCCESS,
                    "MEMBER",
                    event.targetMemberId(),
                    null,
                    event.requestIp(),
                    event.requestUri(),
                    event.requestMethod(),
                    null);
        } catch (Exception e) {
            log.error("2단계 인증 변경 감사 기록 실패 (type={}, targetId={})", event.actionType(), event.targetMemberId(), e);
        }
    }
}
