package com.cms.admin.log.service;

import com.cms.admin.log.domain.AdminActionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
                Long.class, String.class, String.class, String.class, String.class
        );

        Transactional transactional = logMethod.getAnnotation(Transactional.class);

        assertNotNull(transactional, "log()에 @Transactional이 있어야 한다");
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation(),
                "감사 로그는 REQUIRES_NEW 독립 트랜잭션이어야 한다");
    }
}
