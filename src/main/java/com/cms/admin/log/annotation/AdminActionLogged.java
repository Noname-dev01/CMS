package com.cms.admin.log.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AdminActionLogged {

    String actionType();
    String targetType();

    /**
     * 추출할 getter 필드명
     * ex) "id" -> getId()
     */
    String targetIdExpression() default "";

    /**
     * 대상 이름 스냅샷으로 남길 문자열 getter 필드명 (성공 로그에만 기록, 최대 500자)
     * ex) "auditLabel" -> getAuditLabel()
     */
    String targetLabelExpression() default "";

    /**
     * 실패(FAIL) 감사 행의 {@code errorMessage}로 저장할 고정 문구. 값이 있으면 예외 메시지 대신 이 문구를 저장한다 —
     * 서비스 메서드 반환 이후 트랜잭션 어드바이저가 던지는 flush·커밋 예외의 메시지에는 SQL·값이 섞일 수 있어, 사용자 입력이
     * 감사 행에 남아서는 안 되는 액션(쪽지 발송)이 쓴다. 비어 있으면 기존 동작({@code e.getMessage()} 저장)이다.
     * 원래 예외와 원인 체인은 영향받지 않고 그대로 전파된다. 서버 로그의 프레임워크 예외 출력은 이 속성의 보호 범위가 아니다.
     */
    String safeErrorMessage() default "";

}
