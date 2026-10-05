package com.cms.admin.notification.service;

import com.cms.admin.notification.event.NotificationRequestedEvent;
import com.cms.config.auth.AdminAccountAutoLockEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 알림 생성 리스너(AFTER_COMMIT) — 원 트랜잭션이 커밋된 뒤에만 저장하므로 롤백된 변경의 알림은 생기지 않는다.
 * 저장 실패는 원 업무(이미 커밋됨)를 되돌릴 수 없어 ERROR 로그만 남기고 삼킨다(최선 노력 — 감사 로그와 같은 계약).
 *
 * <p><b>실행 순서(v4 R-13)</b>: Spring 6.2의 메서드 리스너는 <b>메서드의 {@code @Order}만</b> 읽는다(클래스 {@code @Order}는 무시되고, 없으면
 * {@code LOWEST_PRECEDENCE}). 세션 만료(10)·권한 캐시 무효화(10)·감사(20)가 먼저 끝난 뒤 알림(100)을 저장해, 알림 저장의 연결·락 대기가
 * 세션 만료와 권한 회수 반영을 막지 않게 한다. 같은 AFTER_COMMIT 단계에서만 순서가 적용되므로 권한 캐시 무효화는 AFTER_COMMIT에도 있다
 * ({@code PermissionChangedListener} 참조).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    /** 알림 리스너의 순서 값 — 세션 만료(10)·캐시 무효화(10)·감사(20)보다 큰 값 = 나중에 실행. */
    public static final int ORDER = 100;

    private final NotificationRecorder notificationRecorder;

    @Order(ORDER)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(NotificationRequestedEvent event) {
        try {
            notificationRecorder.record(event.memberId(), event.type(), event.message(), event.linkUrl());
        } catch (Exception e) {
            log.error("알림 저장 실패 — 원 변경은 이미 커밋됐다 (memberId={}, type={})", event.memberId(), event.type(), e);
        }
    }

    @Order(ORDER)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAutoLock(AdminAccountAutoLockEvent event) {
        try {
            notificationRecorder.recordAutoLock(event);
        } catch (Exception e) {
            log.error("자동 잠금 알림 저장 실패 — 잠금은 이미 커밋됐다 (memberId={})", event.memberId(), e);
        }
    }
}
