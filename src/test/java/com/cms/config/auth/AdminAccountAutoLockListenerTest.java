package com.cms.config.auth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.log.domain.AdminActionResult;
import com.cms.admin.log.service.AdminActionLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 계정 자동 잠금 감사 리스너 테스트 — AdminSessionRevokeListenerTest 선례를 미러.
 */
class AdminAccountAutoLockListenerTest {

    private final AdminActionLogService adminActionLogService = mock(AdminActionLogService.class);
    private final AdminAccountAutoLockListener listener = new AdminAccountAutoLockListener(adminActionLogService);

    private ListAppender<ILoggingEvent> logAppender;
    private Logger listenerLogger;

    @BeforeEach
    void setUpLogger() {
        listenerLogger = (Logger) LoggerFactory.getLogger(AdminAccountAutoLockListener.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        listenerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDownLogger() {
        listenerLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("리스너는 AFTER_COMMIT에 바인딩되어 있다 — 잠금 롤백 시 감사 미생성 보장")
    void listener_isBoundToAfterCommit() throws NoSuchMethodException {
        TransactionalEventListener annotation = AdminAccountAutoLockListener.class
                .getMethod("onAutoLock", AdminAccountAutoLockEvent.class)
                .getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    @DisplayName("세션 폐기 리스너(@Order 10)보다 나중(@Order 20)으로 순서가 명시되어 있다")
    void listener_orderedAfterSessionRevokeListener() {
        Order autoLockOrder = AdminAccountAutoLockListener.class.getAnnotation(Order.class);
        Order revokeOrder = AdminSessionRevokeListener.class.getAnnotation(Order.class);

        assertThat(autoLockOrder).isNotNull();
        assertThat(revokeOrder).isNotNull();
        assertThat(revokeOrder.value()).isLessThan(autoLockOrder.value());
    }

    @Test
    @DisplayName("감사 필드 매핑 — actionId/actionUserId null, SUCCESS, MEMBER, targetId, IP·URI, POST, errorMessage null")
    void onAutoLock_auditFieldMapping() {
        listener.onAutoLock(new AdminAccountAutoLockEvent(7L, "admin01", "1.2.3.4", "/admin/login"));

        verify(adminActionLogService).log(
                isNull(),                               // actionId — 미인증 흐름
                isNull(),                               // actionUserId — 미인증 흐름
                org.mockito.ArgumentMatchers.eq(AdminActionTypes.ACCOUNT_AUTO_LOCK),
                org.mockito.ArgumentMatchers.eq(AdminActionResult.SUCCESS),
                org.mockito.ArgumentMatchers.eq("MEMBER"),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("1.2.3.4"),
                org.mockito.ArgumentMatchers.eq("/admin/login"),
                org.mockito.ArgumentMatchers.eq("POST"),
                isNull()                                // errorMessage
        );
    }

    @Test
    @DisplayName("감사 저장 실패 시 예외를 전파하지 않고 ERROR 로그를 남긴다 — 잠금은 이미 커밋됨")
    void auditFailure_isIsolated_andLoggedAsError() {
        willThrow(new IllegalStateException("감사 저장 실패 주입"))
                .given(adminActionLogService).log(any(), any(), anyString(), any(), anyString(),
                        anyLong(), any(), any(), any(), any());

        assertDoesNotThrow(() ->
                listener.onAutoLock(new AdminAccountAutoLockEvent(7L, "admin01", "1.2.3.4", "/admin/login")));

        assertThat(logAppender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage()).contains("감사 기록 실패");
                });
    }
}
