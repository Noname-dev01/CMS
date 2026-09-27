package com.cms.common.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * {@link GlobalApiExceptionHandler}의 신규 매핑(400/405/415/406)·JSON Content-Type 보장·
 * BindException 타입 변환 메시지 비노출·catch-all 안전 진단 로그를 검증하는 순수 단위 테스트다.
 * 실제 Security·MVC 디스패치를 포함한 요청 행렬은 {@code ApiErrorContractIntegrationTest} 참조.
 */
class GlobalApiExceptionHandlerTest {

    private final GlobalApiExceptionHandler handler = new GlobalApiExceptionHandler();

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(GlobalApiExceptionHandler.class)).addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        ((Logger) LoggerFactory.getLogger(GlobalApiExceptionHandler.class)).detachAppender(logAppender);
    }

    private HttpServletRequest requestStub(String uri, String method) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        given(request.getRequestURI()).willReturn(uri);
        given(request.getMethod()).willReturn(method);
        return request;
    }

    // ===================== catch-all(500) 안전 진단 로그 =====================

    @Test
    @DisplayName("예상 못한 예외는 ERROR 이벤트가 정확히 1개 남고, method/route/exceptionClass가 포함된다")
    void handleException_logsExactlyOneErrorEvent_withMethodRouteAndExceptionClass() {
        HttpServletRequest request = requestStub("/admin/api/members/1", "GET");
        given(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .willReturn("/admin/api/members/{id}");

        handler.handleException(new IllegalStateException("secret-exception-message"), request);

        List<ILoggingEvent> errorEvents = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();
        assertThat(errorEvents).hasSize(1);

        String logged = errorEvents.get(0).getFormattedMessage();
        assertThat(logged).contains("GET");
        assertThat(logged).contains("/admin/api/members/{id}");
        assertThat(logged).contains(IllegalStateException.class.getName());
    }

    @Test
    @DisplayName("라우트 패턴 속성이 없으면 고정 대체값 unmatched를 남긴다")
    void handleException_withoutRoutePatternAttribute_fallsBackToUnmatched() {
        HttpServletRequest request = requestStub("/admin/api/whatever", "POST");
        given(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).willReturn(null);

        handler.handleException(new RuntimeException("secret-exception-message"), request);

        String logged = logAppender.list.get(0).getFormattedMessage();
        assertThat(logged).contains("unmatched");
    }

    @Test
    @DisplayName("예외 message·cause 등 민감정보는 로그에 포함되지 않는다")
    void handleException_doesNotLeakExceptionMessageOrCause() {
        HttpServletRequest request = requestStub("/admin/api/reset?token=super-secret-reset-token", "POST");
        given(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).willReturn("/admin/api/reset");

        RuntimeException cause = new RuntimeException("password=hunter2, token=super-secret-reset-token");
        RuntimeException e = new RuntimeException("이것도 노출되면 안 되는 메시지", cause);

        handler.handleException(e, request);

        String logged = logAppender.list.get(0).getFormattedMessage();
        assertThat(logged).doesNotContain("hunter2");
        assertThat(logged).doesNotContain("super-secret-reset-token");
        assertThat(logged).doesNotContain("이것도 노출되면 안 되는 메시지");
        // 로그에는 URI 원문도 넣지 않는다 — 라우트 패턴만 남긴다.
        assertThat(logged).doesNotContain("/admin/api/reset?token=");
    }

    @Test
    @DisplayName("catch-all 응답은 client에 일반 메시지만 반환하고 500 JSON을 유지한다")
    void handleException_responseBody_isGenericMessage() {
        HttpServletRequest request = requestStub("/admin/api/whatever", "GET");
        given(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).willReturn("/admin/api/whatever");

        ResponseEntity<ApiErrorResponse> response =
                handler.handleException(new RuntimeException("secret-exception-message"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).doesNotContain("secret-exception-message");
    }

    // ===================== 신규 400/405/415/406 매핑 =====================

    @Test
    @DisplayName("경로 변수 타입 불일치는 400 INVALID_REQUEST이며 잘못된 값 원문을 재출력하지 않는다")
    void handleTypeMismatch_returns400_withoutEchoingRawValue() {
        HttpServletRequest request = requestStub("/admin/api/menus/injected-secret-value", "GET");
        MethodArgumentTypeMismatchException e = mock(MethodArgumentTypeMismatchException.class);
        given(e.getName()).willReturn("id");

        ResponseEntity<ApiErrorResponse> response = handler.handleTypeMismatch(e, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getBody().message()).contains("id");
        assertThat(response.getBody().message()).doesNotContain("injected-secret-value");
    }

    @Test
    @DisplayName("지원하지 않는 HTTP 메서드는 405이며 지원 메서드로 Allow 헤더를 구성한다")
    void handleMethodNotSupported_setsAllowHeader_fromSupportedMethods() {
        HttpServletRequest request = requestStub("/admin/api/menus/1", "PUT");
        HttpRequestMethodNotSupportedException e = mock(HttpRequestMethodNotSupportedException.class);
        given(e.getSupportedHttpMethods()).willReturn(Set.of(HttpMethod.GET, HttpMethod.PATCH));

        ResponseEntity<ApiErrorResponse> response = handler.handleMethodNotSupported(e, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.PATCH);
        assertThat(response.getBody().code()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    @DisplayName("지원 메서드 정보가 없으면 Allow 헤더 없이 405를 반환한다")
    void handleMethodNotSupported_withoutSupportedMethods_omitsAllowHeader() {
        HttpServletRequest request = requestStub("/admin/api/menus/1", "PUT");
        HttpRequestMethodNotSupportedException e = mock(HttpRequestMethodNotSupportedException.class);
        given(e.getSupportedHttpMethods()).willReturn(null);

        ResponseEntity<ApiErrorResponse> response = handler.handleMethodNotSupported(e, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).isEmpty();
    }

    @Test
    @DisplayName("지원하지 않는 Content-Type은 415 UNSUPPORTED_MEDIA_TYPE JSON을 반환한다")
    void handleUnsupportedMediaType_returns415Json() {
        HttpServletRequest request = requestStub("/admin/api/notices/1/attachments", "POST");
        HttpMediaTypeNotSupportedException e = mock(HttpMediaTypeNotSupportedException.class);

        ResponseEntity<ApiErrorResponse> response = handler.handleUnsupportedMediaType(e, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody().code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    @DisplayName("응답 형식(Accept) 협상 실패는 406 NOT_ACCEPTABLE JSON을 반환한다")
    void handleNotAcceptable_returns406Json() {
        HttpServletRequest request = requestStub("/admin/api/members/1", "GET");
        HttpMediaTypeNotAcceptableException e = mock(HttpMediaTypeNotAcceptableException.class);

        ResponseEntity<ApiErrorResponse> response = handler.handleNotAcceptable(e, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody().code()).isEqualTo("NOT_ACCEPTABLE");
    }

    // ===================== BindException 타입 변환 메시지 비노출 =====================

    @Test
    @DisplayName("타입 변환 실패로 생긴 필드 오류는 고정된 안전한 문구로 대체된다")
    void handleBindException_typeConversionFailure_usesFixedSafeMessage() {
        HttpServletRequest request = requestStub("/admin/api/members", "GET");

        FieldError fieldError = mock(FieldError.class);
        given(fieldError.isBindingFailure()).willReturn(true);
        given(fieldError.getField()).willReturn("userType");
        given(fieldError.getDefaultMessage())
                .willReturn("Failed to convert value 'FAKE_TOKEN_MARKER'; nested exception is ...");

        SearchDto target = new SearchDto();
        BindingResult bindingResult = mock(BindingResult.class);
        given(bindingResult.getFieldErrors()).willReturn(List.of(fieldError));
        given(bindingResult.getTarget()).willReturn(target);

        org.springframework.validation.BindException e =
                new org.springframework.validation.BindException(bindingResult);

        ResponseEntity<ApiErrorResponse> response = handler.handleBindException(e, request);

        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().message()).isEqualTo("userType: 입력값 형식이 올바르지 않습니다.");
        assertThat(response.getBody().message()).doesNotContain("FAKE_TOKEN_MARKER");
    }

    @Test
    @DisplayName("일반 Bean Validation 실패(바인딩 실패 아님)는 기존 메시지를 그대로 유지한다")
    void handleBindException_regularValidationFailure_keepsOriginalMessage() {
        HttpServletRequest request = requestStub("/admin/api/members", "GET");

        FieldError fieldError = mock(FieldError.class);
        given(fieldError.isBindingFailure()).willReturn(false);
        given(fieldError.getField()).willReturn("userId");
        given(fieldError.getDefaultMessage()).willReturn("크기가 50자를 초과할 수 없습니다");

        SearchDto target = new SearchDto();
        BindingResult bindingResult = mock(BindingResult.class);
        given(bindingResult.getFieldErrors()).willReturn(List.of(fieldError));
        given(bindingResult.getTarget()).willReturn(target);

        org.springframework.validation.BindException e =
                new org.springframework.validation.BindException(bindingResult);

        ResponseEntity<ApiErrorResponse> response = handler.handleBindException(e, request);

        assertThat(response.getBody().message()).isEqualTo("userId: 크기가 50자를 초과할 수 없습니다");
    }

    /** buildValidationMessage()의 isDeclaredField() 판정을 실제 필드 조회로 통과시키기 위한 최소 대상. */
    static class SearchDto {
        private String userId;
        private String userType;
    }
}
