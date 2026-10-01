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

}
