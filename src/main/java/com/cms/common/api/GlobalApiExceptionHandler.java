package com.cms.common.api;

import com.cms.common.exception.DuplicateResourceException;
import com.cms.common.exception.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalApiExceptionHandler {

    /**
     * Validation 실패 (@Valid)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException e,
            HttpServletRequest request
    ){
        FieldError fieldError = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .orElse(null);

        String message = (fieldError == null)
                ? "Validation error"
                : fieldError.getField() + ": " + fieldError.getDefaultMessage();

        ApiErrorResponse response = ApiErrorResponse.of(
                request.getRequestURI(),
                "VALIDATION_ERROR",
                message
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
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
     * 권한 없음
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(
            AccessDeniedException e,
            HttpServletRequest request
    ) {

        ApiErrorResponse response = ApiErrorResponse.of(
                request.getRequestURI(),
                "ACCESS_DENIED",
                "권한이 없습니다."
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
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
