package com.cms.common.api;

import com.cms.common.exception.ConflictException;
import com.cms.common.exception.DuplicateResourceException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

@RestControllerAdvice
public class GlobalApiExceptionHandler {

    /**
     * /admin/api/** 여부 판정 — SecurityConfig가 인가 규칙에 쓰는 것과 동일한
     * RequestMatcher 인스턴스다. SecurityConfig·AdminSessionExpiredStrategy가 이 상수를
     * 참조한다(반대 방향 — common → config 역의존을 만들지 않기 위해 이 클래스가 소유한다).
     * 문자열 비교(uri.startsWith(...))로 대체하면 컨텍스트 경로·세미콜론 매트릭스
     * 파라미터가 섞인 경로에서 Security의 판정과 어긋날 수 있다.
     */
    public static final RequestMatcher API_MATCHER =
            PathPatternRequestMatcher.withDefaults().matcher("/admin/api/**");

    /**
     * Validation 실패 (@Valid)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException e,
            HttpServletRequest request
    ){
        String message = buildValidationMessage(e.getBindingResult());

        ApiErrorResponse response = ApiErrorResponse.of(
                request.getRequestURI(),
                "VALIDATION_ERROR",
                message
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * @ModelAttribute 검증 실패 (@Valid + @Size 등)
     * MethodArgumentNotValidException은 BindException의 하위 타입이므로
     * 해당 전용 핸들러가 우선 매칭되고, 나머지 BindException은 여기서 처리한다.
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiErrorResponse> handleBindException(
            BindException e,
            HttpServletRequest request
    ) {
        String message = buildValidationMessage(e.getBindingResult());

        ApiErrorResponse response = ApiErrorResponse.of(
                request.getRequestURI(),
                "VALIDATION_ERROR",
                message
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * 검증 실패 메시지를 조립한다.
     * <ul>
     *   <li>실제 선언 필드(FieldError)일 때: "필드명: 메시지" 형식 — 어느 필드인지 명시</li>
     *   <li>@AssertTrue 등 게터 기반 교차검증의 파생 프로퍼티명일 때: 메시지만 반환 — 내부 식별자 노출 방지</li>
     *   <li>클래스레벨 ObjectError(global error)일 때: 메시지만 반환</li>
     * </ul>
     */
    private String buildValidationMessage(BindingResult bindingResult) {
        FieldError fieldError = bindingResult.getFieldErrors().stream().findFirst().orElse(null);
        if (fieldError == null) {
            ObjectError globalError = bindingResult.getGlobalErrors().stream().findFirst().orElse(null);
            return globalError != null ? globalError.getDefaultMessage() : "Validation error";
        }
        String field = fieldError.getField();
        if (isDeclaredField(bindingResult.getTarget(), field)) {
            return field + ": " + fieldError.getDefaultMessage();
        }
        return fieldError.getDefaultMessage();
    }

    /**
     * 주어진 필드명이 대상 객체 클래스(혹은 상위 클래스)에 실제로 선언된 필드인지 반환한다.
     * target이 null이면 판단 불가로 보아 true(prefix 유지)를 반환한다.
     */
    private boolean isDeclaredField(Object target, String field) {
        if (target == null) {
            return true;
        }
        // 중첩 경로("address.city")는 최상위 세그먼트만 확인
        String name = field.contains(".") ? field.substring(0, field.indexOf('.')) : field;
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                type.getDeclaredField(name);
                return true;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        return false;
    }

    /**
     * JSON 파싱 실패 (Enum 값 오류 등)
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleJsonParse(
            HttpMessageNotReadableException e,
            HttpServletRequest request
    ) {

        ApiErrorResponse response = ApiErrorResponse.of(
                request.getRequestURI(),
                "JSON_PARSE_ERROR",
                "요청 JSON 형식이 올바르지 않습니다."
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }


    /**
     * 비즈니스 요청 오류
     */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(
            InvalidRequestException e,
            HttpServletRequest request
    ) {

        ApiErrorResponse response = ApiErrorResponse.of(
                request.getRequestURI(),
                "INVALID_REQUEST",
                e.getMessage()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }


    /**
     * multipart 업로드 크기 초과(spring.servlet.multipart.max-file-size/max-request-size).
     * MultipartException의 하위 타입이라 Exception 폴백보다 먼저 구체적으로 처리해야 한다 —
     * 처리하지 않으면 500 INTERNAL_ERROR로 떨어진다(PLAN-notice-attachment.md 쟁점 8).
     * 10MB 초과는 413이 더 정확할 수 있으나, 프로젝트가 좁게 유지하는 상태 코드 팔레트(CLAUDE.md
     * 상태 코드 규칙 표에 413 없음, 기존 크기 검증도 400으로 통일)와의 일관성을 위해 400을 유지한다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException e,
            HttpServletRequest request
    ) {
        ApiErrorResponse response = ApiErrorResponse.of(
                request.getRequestURI(),
                "INVALID_REQUEST",
                "첨부파일이 허용 크기를 초과했습니다."
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * multipart 요청에 필수 파트(예: 파일 첨부의 "file")가 아예 빠진 경우.
     * 서비스 검증에 도달하기 전 서블릿 레벨에서 발생하므로 Exception 폴백보다 먼저
     * 구체적으로 처리해야 한다 — 처리하지 않으면 요청 오류가 500 INTERNAL_ERROR로 떨어진다.
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingServletRequestPart(
            MissingServletRequestPartException e,
            HttpServletRequest request
    ) {
        ApiErrorResponse response = ApiErrorResponse.of(
                request.getRequestURI(),
                "INVALID_REQUEST",
                "필수 요청 파트가 누락되었습니다: " + e.getRequestPartName()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * 중복 리소스
     */
    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicate(
            DuplicateResourceException e,
            HttpServletRequest request
    ) {

        ApiErrorResponse response = ApiErrorResponse.of(
                request.getRequestURI(),
                "DUPLICATE_RESOURCE",
                e.getMessage()
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    /**
     * 상태 충돌 (중복 리소스가 아닌 그 외 409 상황)
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(
            ConflictException e,
            HttpServletRequest request
    ) {

        ApiErrorResponse response = ApiErrorResponse.of(
                request.getRequestURI(),
                "RESOURCE_CONFLICT",
                e.getMessage()
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    /**
     * 비관적 락 획득 실패 (락 대기 타임아웃, 데드락 감지 롤백 등 — CannotAcquireLockException 포함).
     * flush/커밋 시점에도 발생할 수 있어 서비스 try-catch로는 안정적으로 잡을 수 없으므로
     * 전역 핸들러에서 409로 변환한다. 정상적인 동시성 충돌이며 재시도로 해소된다.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handlePessimisticLockingFailure(
            PessimisticLockingFailureException e,
            HttpServletRequest request
    ) {
        ApiErrorResponse response = ApiErrorResponse.of(
                request.getRequestURI(),
                "RESOURCE_CONFLICT",
                "동시 변경과 충돌했습니다. 다시 시도해주세요."
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    /**
     * 데이터 무결성 제약 조건 위반
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException e,
            HttpServletRequest request
    ) {
        String message = "중복된 데이터이거나 제약 조건을 위반했습니다.";

        String rootMessage = e.getMostSpecificCause() != null
                ? e.getMostSpecificCause().getMessage()
                : "";

        if (rootMessage.contains("uk_member_user_id")) {
            message = "이미 사용 중인 아이디입니다.";
        } else if (rootMessage.contains("uk_member_email")) {
            message = "이미 사용 중인 이메일입니다.";
        }

        ApiErrorResponse response = ApiErrorResponse.of(
                request.getRequestURI(),
                "DUPLICATE_RESOURCE",
                message
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }


    /**
     * 자원 없음
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(
            ResourceNotFoundException e,
            HttpServletRequest request
    ) {
        ApiErrorResponse response = ApiErrorResponse.of(
                request.getRequestURI(),
                "RESOURCE_NOT_FOUND",
                e.getMessage()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    /**
     * 권한 없음
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(
            AccessDeniedException e,
            HttpServletRequest request
    ) {
        String message = (e.getMessage() != null && !e.getMessage().isBlank())
                ? e.getMessage()
                : "권한이 없습니다.";

        ApiErrorResponse response = ApiErrorResponse.of(
                request.getRequestURI(),
                "ACCESS_DENIED",
                message
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }


    /**
     * 핸들러 없는 경로(정적 리소스 미존재 포함). NoResourceFoundException은
     * spring.web.resources.add-mappings=true(기본값)일 때 정적 리소스 핸들러가
     * "/**"를 잡고 있어 실제로 발생하는 경로다. NoHandlerFoundException은 그 설정이
     * 꺼지면 대신 발생하므로 함께 등록해둔다(기본 설정에서는 도달하지 않음 —
     * add-mappings=false로 전환한 슬라이스 테스트로 실제 디스패치 계약을 고정한다).
     * 이 핸들러가 없으면 아래 Exception catch-all이 잡아 정상적인 404를 500
     * INTERNAL_ERROR로 바꿔버린다(docs/troubleshooting.md 참조).
     * /admin/api/**는 JSON 404, 그 외는 기존 error/404.html을 그대로 쓰기 위해
     * sendError + null 반환한다 — HttpEntityMethodProcessor는 반환값이 null이면
     * requestHandled=true로 처리하고 종료하므로 ResponseEntity 반환 타입에서도 안전하다
     * (PublicNoticeController.attachment()와 동일 계약). JSON 응답은 Content-Type을
     * 명시한다 — 명시하지 않으면 Accept: text/html 등에서 콘텐츠 협상이 실패할 수 있다
     * (ApiAuthenticationEntryPoint·ApiAccessDeniedHandler와 동일한 이유).
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleNoHandlerFound(
            Exception e,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        if (API_MATCHER.matches(request)) {
            ApiErrorResponse body = ApiErrorResponse.of(
                    request.getRequestURI(), "RESOURCE_NOT_FOUND", "요청하신 경로를 찾을 수 없습니다.");
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        }
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
        return null;
    }

    /**
     * 예상 못한 서버 오류
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleException(
            Exception e,
            HttpServletRequest request
    ) {

        ApiErrorResponse response = ApiErrorResponse.of(
                request.getRequestURI(),
                "INTERNAL_ERROR",
                "서버 오류가 발생했습니다."
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
