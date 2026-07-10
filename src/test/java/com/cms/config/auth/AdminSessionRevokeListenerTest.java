package com.cms.config.auth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

class AdminSessionRevokeListenerTest {

    private final AdminSessionService adminSessionService = mock(AdminSessionService.class);
    private final AdminSessionRevokeListener listener = new AdminSessionRevokeListener(adminSessionService);

    private ListAppender<ILoggingEvent> logAppender;
    private Logger listenerLogger;

    @BeforeEach
    void setUpLogger() {
        listenerLogger = (Logger) LoggerFactory.getLogger(AdminSessionRevokeListener.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        listenerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDownLogger() {
        listenerLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("리스너는 AFTER_COMMIT에 바인딩되어 있다 — 커밋 전·롤백 시 만료 미실행 보장")
    void listener_isBoundToAfterCommit() throws NoSuchMethodException {
        TransactionalEventListener annotation = AdminSessionRevokeListener.class
                .getMethod("onRevoke", AdminSessionRevokeEvent.class)
                .getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    @DisplayName("만료 실패 시 예외를 전파하지 않고 ERROR 로그를 남긴다 — 필수 관측성 계약")
    void expireFailure_isIsolated_andLoggedAsError() {
        willThrow(new IllegalStateException("만료 실패 주입"))
                .given(adminSessionService).expireSessionsFor(anyLong());

        assertDoesNotThrow(() -> listener.onRevoke(new AdminSessionRevokeEvent(2L)));

        assertThat(logAppender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage()).contains("세션 만료 실패");
                });
    }
}
