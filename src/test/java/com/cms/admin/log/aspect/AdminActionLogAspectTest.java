package com.cms.admin.log.aspect;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.domain.AdminActionResult;
import com.cms.admin.log.service.AdminActionLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
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
 * - 대상 이름 스냅샷(targetLabel)을 성공 로그에만 기록·절단하고, 저장 실패 로그에는 이스케이프해서 남기는지
 *
 * REQUIRES_NEW 독립 트랜잭션으로 인한 "롤백돼도 FAIL 로그 보존"의 트랜잭션 경계 자체는
 * 선언적 설정이므로 AdminActionLogServiceTest에서 전파 속성으로 가드한다.
 */
@ExtendWith(MockitoExtension.class)
class AdminActionLogAspectTest {

    @Mock
    AdminActionLogService adminActionLogService;

    @InjectMocks
    AdminActionLogAspect adminActionLogAspect;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger aspectLogger;

    @BeforeEach
    void setUpLogger() {
        aspectLogger = (Logger) LoggerFactory.getLogger(AdminActionLogAspect.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        aspectLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDownLogger() {
        aspectLogger.detachAppender(logAppender);
    }

    /** extractTargetId가 리플렉션으로 getId()를 호출하므로 public 게터가 필요하다. */
    public static class SampleResult {
        public Long getId() {
            return 99L;
        }
    }

    /** targetLabelExpression="label"이 getLabel()을 리플렉션으로 호출하므로 public 게터가 필요하다. */
    public static class LabeledResult {
        private final String label;

        public LabeledResult(String label) {
            this.label = label;
        }

        public Long getId() {
            return 99L;
        }

        public String getLabel() {
            return label;
        }
    }

    @AdminActionLogged(actionType = "TEST_ACTION", targetType = "MEMBER", targetIdExpression = "id")
    @SuppressWarnings("unused")
    private void annotatedSample() {
    }

    @AdminActionLogged(actionType = "TEST_ACTION", targetType = "MEMBER", targetIdExpression = "id",
            targetLabelExpression = "label")
    @SuppressWarnings("unused")
    private void annotatedLabeledSample() {
    }

    @AdminActionLogged(actionType = "TEST_ACTION", targetType = "MEMBER", targetIdExpression = "id",
            safeErrorMessage = "고정된 실패 문구입니다.")
    @SuppressWarnings("unused")
    private void annotatedSafeMessageSample() {
    }

    private AdminActionLogged safeMessageAnnotation() throws NoSuchMethodException {
        return getClass()
                .getDeclaredMethod("annotatedSafeMessageSample")
                .getAnnotation(AdminActionLogged.class);
    }

    private AdminActionLogged sampleAnnotation() throws NoSuchMethodException {
        return getClass()
                .getDeclaredMethod("annotatedSample")
                .getAnnotation(AdminActionLogged.class);
    }

    private AdminActionLogged labeledAnnotation() throws NoSuchMethodException {
        return getClass()
                .getDeclaredMethod("annotatedLabeledSample")
                .getAnnotation(AdminActionLogged.class);
    }

    @Test
    @DisplayName("작업 성공 시 SUCCESS 로그를 저장한다 (라벨 미지정이면 targetLabel은 null)")
    void logSuccess_savesSuccessLog() throws Exception {
        adminActionLogAspect.logSuccess(null, sampleAnnotation(), new SampleResult());

        verify(adminActionLogService).log(
                isNull(),                          // actionId (보안 컨텍스트 없음)
                isNull(),                          // actionUserId
                eq("TEST_ACTION"),                 // actionType
                eq(AdminActionResult.SUCCESS),     // actionResult
                eq("MEMBER"),                      // targetType
                eq(99L),                           // targetId (getId())
                isNull(),                          // targetLabel (표현식 미지정)
                isNull(),                          // requestIp
                isNull(),                          // requestUri
                isNull(),                          // requestMethod
                isNull()                           // errorMessage
        );
    }

    @Test
    @DisplayName("targetLabelExpression이 지정되면 결과 getter의 문자열을 targetLabel로 저장한다")
    void logSuccess_savesTargetLabelFromResultGetter() throws Exception {
        adminActionLogAspect.logSuccess(null, labeledAnnotation(), new LabeledResult("메뉴 (/menu)"));

        verify(adminActionLogService).log(
                isNull(), isNull(), eq("TEST_ACTION"), eq(AdminActionResult.SUCCESS), eq("MEMBER"),
                eq(99L),
                eq("메뉴 (/menu)"),                 // targetLabel
                isNull(), isNull(), isNull(), isNull()
        );
    }

    @Test
    @DisplayName("targetLabel은 500자를 넘으면 500자로 자른다 (컬럼 길이 V12)")
    void logSuccess_truncatesTargetLabelTo500() throws Exception {
        adminActionLogAspect.logSuccess(null, labeledAnnotation(), new LabeledResult("가".repeat(501)));

        verify(adminActionLogService).log(
                isNull(), isNull(), eq("TEST_ACTION"), eq(AdminActionResult.SUCCESS), eq("MEMBER"),
                eq(99L),
                eq("가".repeat(500)),
                isNull(), isNull(), isNull(), isNull()
        );
    }

    @Test
    @DisplayName("라벨 getter가 없거나 문자열이 아니면 targetLabel은 null이고 작업 결과에 영향이 없다")
    void logSuccess_labelExtractionFailureYieldsNull() throws Exception {
        // SampleResult에는 getLabel()이 없다 → 추출 실패는 null로 흡수
        adminActionLogAspect.logSuccess(null, labeledAnnotation(), new SampleResult());

        verify(adminActionLogService).log(
                isNull(), isNull(), eq("TEST_ACTION"), eq(AdminActionResult.SUCCESS), eq("MEMBER"),
                eq(99L),
                isNull(),
                isNull(), isNull(), isNull(), isNull()
        );
    }

    @Test
    @DisplayName("작업 실패 시 FAIL 로그를 에러 메시지와 함께 저장한다 (targetId·targetLabel은 null)")
    void logFailure_savesFailLog() throws Exception {
        adminActionLogAspect.logFailure(labeledAnnotation(), new RuntimeException("boom"));

        verify(adminActionLogService).log(
                isNull(),                          // actionId
                isNull(),                          // actionUserId
                eq("TEST_ACTION"),                 // actionType
                eq(AdminActionResult.FAIL),        // actionResult
                eq("MEMBER"),                      // targetType
                isNull(),                          // targetId (실패 시 null)
                isNull(),                          // targetLabel (실패 시 null)
                isNull(),                          // requestIp
                isNull(),                          // requestUri
                isNull(),                          // requestMethod
                eq("boom")                         // errorMessage
        );
    }

    @Test
    @DisplayName("safeErrorMessage가 지정된 액션은 예외 메시지(SQL·사용자 입력이 섞일 수 있음)를 저장하지 않고 고정 문구를 저장한다")
    void logFailure_withSafeErrorMessage_storesFixedMessageNotExceptionMessage() throws Exception {
        RuntimeException commitFailure = new RuntimeException(
                "could not execute statement [Data truncation: INSERT INTO admin_message ... values ('비밀 제목','비밀 본문')]",
                new java.sql.SQLException("원인 체인 메시지: 비밀 본문"));

        adminActionLogAspect.logFailure(safeMessageAnnotation(), commitFailure);

        verify(adminActionLogService).log(
                isNull(), isNull(), eq("TEST_ACTION"), eq(AdminActionResult.FAIL), eq("MEMBER"),
                isNull(), isNull(), isNull(), isNull(), isNull(),
                eq("고정된 실패 문구입니다.")           // 예외 메시지·원인 메시지가 아니다
        );
    }

    @Test
    @DisplayName("safeErrorMessage가 비어 있는 기존 액션은 예외 메시지 저장이 그대로다(동작 불변)")
    void logFailure_withoutSafeErrorMessage_keepsExceptionMessage() throws Exception {
        assertThat(sampleAnnotation().safeErrorMessage()).isEmpty();

        adminActionLogAspect.logFailure(sampleAnnotation(), new RuntimeException("기존 메시지"));

        verify(adminActionLogService).log(
                isNull(), isNull(), eq("TEST_ACTION"), eq(AdminActionResult.FAIL), eq("MEMBER"),
                isNull(), isNull(), isNull(), isNull(), isNull(),
                eq("기존 메시지"));
    }

    @Test
    @DisplayName("safeErrorMessage가 지정된 액션도 SUCCESS의 errorMessage는 null이다")
    void logSuccess_withSafeErrorMessage_errorMessageStaysNull() throws Exception {
        adminActionLogAspect.logSuccess(null, safeMessageAnnotation(), new SampleResult());

        verify(adminActionLogService).log(
                isNull(), isNull(), eq("TEST_ACTION"), eq(AdminActionResult.SUCCESS), eq("MEMBER"),
                eq(99L), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("로그 저장이 실패해도 정상 완료된 작업을 실패로 뒤집지 않는다")
    void logSuccess_swallowsLoggingError() throws Exception {
        doThrow(new RuntimeException("로그 DB 장애"))
                .when(adminActionLogService)
                .log(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        assertDoesNotThrow(() ->
                adminActionLogAspect.logSuccess(null, sampleAnnotation(), new SampleResult()));
    }

    @Test
    @DisplayName("로그 저장이 실패해도 원래의 비즈니스 예외를 가리지 않는다")
    void logFailure_swallowsLoggingError() throws Exception {
        doThrow(new RuntimeException("로그 DB 장애"))
                .when(adminActionLogService)
                .log(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        // logFailure가 예외를 던지지 않아야, @AfterThrowing에서 전파 중인 원 예외가 그대로 유지된다.
        assertDoesNotThrow(() ->
                adminActionLogAspect.logFailure(sampleAnnotation(), new RuntimeException("business failure")));
    }

    @Test
    @DisplayName("성공 로그 저장이 실패하면 ERROR 로그에 actionType·targetId·이스케이프된 라벨을 남긴다 (한 줄 유지)")
    void logSuccess_storeFailure_logsEscapedLabelOnSingleLine() throws Exception {
        doThrow(new RuntimeException("로그 DB 장애"))
                .when(adminActionLogService)
                .log(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        // CRLF·유니코드 줄바꿈(U+0085·U+2028·U+2029)·역슬래시가 섞인 사용자 입력
        String malicious = "/x\r\nFAKE AUDIT\u0085N L P\\n";

        adminActionLogAspect.logSuccess(null, labeledAnnotation(), new LabeledResult(malicious));

        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            String message = event.getFormattedMessage();
            assertThat(message).contains("actionType=TEST_ACTION").contains("targetId=99");
            // 원문 제어문자가 한 글자도 남지 않아야 한다 — 로그 뷰어가 가짜 줄을 만들 수 없다
            assertThat(message).doesNotContain("\r", "\n", "\u0085", " ", " ");
            // 가역 이스케이프: 줄바꿈은 역슬래시+r·n, 유니코드 구분자는 역슬래시+u+4자리 16진수, 원래 역슬래시는 두 개
            assertThat(message).contains("targetLabel=/x\\r\\nFAKE AUDIT\\u0085N\\u2028L\\u2029P\\\\n");
        });
    }

    @Test
    @DisplayName("escapeForLog — 일반 문자열은 그대로, null은 null")
    void escapeForLog_plainAndNull() {
        assertThat(AdminActionLogAspect.escapeForLog("메뉴 (/menu)")).isEqualTo("메뉴 (/menu)");
        assertThat(AdminActionLogAspect.escapeForLog(null)).isNull();
    }

    @Test
    @DisplayName("escapeForLog — 문자 그대로의 \\n(역슬래시+n)과 실제 개행은 서로 다르게 이스케이프된다 (가역)")
    void escapeForLog_isReversible() {
        assertThat(AdminActionLogAspect.escapeForLog("a\\nb")).isEqualTo("a\\\\nb");
        assertThat(AdminActionLogAspect.escapeForLog("a\nb")).isEqualTo("a\\nb");
    }
}
