package com.cms.admin.log.service;

import com.cms.admin.log.domain.AdminActionLog;
import com.cms.admin.log.domain.AdminActionResult;
import com.cms.admin.log.repository.AdminActionLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 감사 로그 저장이 원 비즈니스 트랜잭션과 분리된 독립 트랜잭션(REQUIRES_NEW)으로
 * 동작하도록 선언되어 있는지 가드한다.
 *
 * - 원 작업 롤백 시에도 FAIL 로그가 보존되고
 * - 로그 저장 실패가 원 작업 트랜잭션을 오염시키지 않으려면
 * 이 전파 속성이 유지되어야 한다.
 */
class AdminActionLogServiceTest {

    @Test
    @DisplayName("log()는 REQUIRES_NEW 독립 트랜잭션으로 선언되어 있다")
    void log_isDeclaredWithRequiresNew() throws NoSuchMethodException {
        Method logMethod = AdminActionLogService.class.getMethod(
                "log",
                Long.class, String.class, String.class, AdminActionResult.class, String.class,
                Long.class, String.class, String.class, String.class, String.class, String.class
        );

        Transactional transactional = logMethod.getAnnotation(Transactional.class);

        assertNotNull(transactional, "log()에 @Transactional이 있어야 한다");
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation(),
                "감사 로그는 REQUIRES_NEW 독립 트랜잭션이어야 한다");
    }

    @Test
    @DisplayName("log()의 createAt은 주입된 KST Clock에서 나온다 (UTC 2026-09-29 15:00 = KST 2026-09-30 00:00)")
    void log_createAtComesFromInjectedClock() {
        AdminActionLogRepository repository = mock(AdminActionLogRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-29T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        AdminActionLogService service = new AdminActionLogService(repository, clock);

        service.log(1L, "admin", "TEST", AdminActionResult.SUCCESS, "TARGET", 2L, null,
                "127.0.0.1", "/uri", "GET", null);

        ArgumentCaptor<AdminActionLog> captor = ArgumentCaptor.forClass(AdminActionLog.class);
        verify(repository).save(captor.capture());
        assertEquals(LocalDateTime.of(2026, 9, 30, 0, 0), captor.getValue().getCreateAt());
    }

    @Test
    @DisplayName("log()는 연속된 String 인자(타입·라벨·IP·URI·메서드·오류)를 각각 올바른 필드에 매핑한다")
    void log_mapsEveryArgumentToItsOwnField() {
        AdminActionLogRepository repository = mock(AdminActionLogRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-29T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        AdminActionLogService service = new AdminActionLogService(repository, clock);

        service.log(1L, "admin", "TEST_ACTION", AdminActionResult.FAIL, "TARGET_TYPE", 2L, "LABEL",
                "127.0.0.1", "/uri", "DELETE", "ERROR");

        ArgumentCaptor<AdminActionLog> captor = ArgumentCaptor.forClass(AdminActionLog.class);
        verify(repository).save(captor.capture());
        AdminActionLog saved = captor.getValue();
        assertEquals(1L, saved.getActionId());
        assertEquals("admin", saved.getActionUserId());
        assertEquals("TEST_ACTION", saved.getActionType());
        assertEquals(AdminActionResult.FAIL, saved.getActionResult());
        assertEquals("TARGET_TYPE", saved.getTargetType());
        assertEquals(2L, saved.getTargetId());
        assertEquals("LABEL", saved.getTargetLabel());
        assertEquals("127.0.0.1", saved.getRequestIp());
        assertEquals("/uri", saved.getRequestUri());
        assertEquals("DELETE", saved.getRequestMethod());
        assertEquals("ERROR", saved.getErrorMessage());
    }
}
