package com.cms.admin.log.aspect;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.domain.AdminActionResult;
import com.cms.admin.log.service.AdminActionLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 감사 로그 AOP 호출부 단위 테스트.
 * - 성공/실패 시 각각 SUCCESS/FAIL 로그를 저장하는지
 * - 로그 저장이 실패해도 원 작업(정상 완료/원래 예외)을 깨거나 가리지 않는지(예외 격리)
 *
 * REQUIRES_NEW 독립 트랜잭션으로 인한 "롤백돼도 FAIL 로그 보존"의 트랜잭션 경계 자체는
 * 선언적 설정이므로 {@link com.cms.admin.log.service.AdminActionLogServiceTest}에서
 * 전파 속성으로 가드한다.
 */
@ExtendWith(MockitoExtension.class)
class AdminActionLogAspectTest {

    @Mock
    AdminActionLogService adminActionLogService;

    @InjectMocks
    AdminActionLogAspect adminActionLogAspect;

    /** extractTargetId가 리플렉션으로 getId()를 호출하므로 public 게터가 필요하다. */
    public static class SampleResult {
        public Long getId() {
            return 99L;
        }
    }

    @AdminActionLogged(actionType = "TEST_ACTION", targetType = "MEMBER", targetIdExpression = "id")
    @SuppressWarnings("unused")
    private void annotatedSample() {
    }

    private AdminActionLogged sampleAnnotation() throws NoSuchMethodException {
        return getClass()
                .getDeclaredMethod("annotatedSample")
                .getAnnotation(AdminActionLogged.class);
    }

    @Test
    @DisplayName("작업 성공 시 SUCCESS 로그를 저장한다")
    void logSuccess_savesSuccessLog() throws Exception {
        adminActionLogAspect.logSuccess(null, sampleAnnotation(), new SampleResult());

        verify(adminActionLogService).log(
                isNull(),                          // actionId (보안 컨텍스트 없음)
                isNull(),                          // actionUserId
                eq("TEST_ACTION"),                 // actionType
                eq(AdminActionResult.SUCCESS),     // actionResult
                eq("MEMBER"),                      // targetType
                eq(99L),                           // targetId (getId())
                isNull(),                          // requestIp
                isNull(),                          // requestUri
                isNull(),                          // requestMethod
                isNull()                           // errorMessage
        );
    }

    @Test
    @DisplayName("작업 실패 시 FAIL 로그를 에러 메시지와 함께 저장한다")
    void logFailure_savesFailLog() throws Exception {
        adminActionLogAspect.logFailure(sampleAnnotation(), new RuntimeException("boom"));

        verify(adminActionLogService).log(
                isNull(),                          // actionId
                isNull(),                          // actionUserId
                eq("TEST_ACTION"),                 // actionType
                eq(AdminActionResult.FAIL),        // actionResult
                eq("MEMBER"),                      // targetType
                isNull(),                          // targetId (실패 시 null)
                isNull(),                          // requestIp
                isNull(),                          // requestUri
                isNull(),                          // requestMethod
                eq("boom")                         // errorMessage
        );
    }

    @Test
    @DisplayName("로그 저장이 실패해도 정상 완료된 작업을 실패로 뒤집지 않는다")
    void logSuccess_swallowsLoggingError() throws Exception {
        doThrow(new RuntimeException("로그 DB 장애"))
                .when(adminActionLogService)
                .log(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        assertDoesNotThrow(() ->
                adminActionLogAspect.logSuccess(null, sampleAnnotation(), new SampleResult()));
    }

    @Test
    @DisplayName("로그 저장이 실패해도 원래의 비즈니스 예외를 가리지 않는다")
    void logFailure_swallowsLoggingError() throws Exception {
        doThrow(new RuntimeException("로그 DB 장애"))
                .when(adminActionLogService)
                .log(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        // logFailure가 예외를 던지지 않아야, @AfterThrowing에서 전파 중인 원 예외가 그대로 유지된다.
        assertDoesNotThrow(() ->
                adminActionLogAspect.logFailure(sampleAnnotation(), new RuntimeException("business failure")));
    }
}
