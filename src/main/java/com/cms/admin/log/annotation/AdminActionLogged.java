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

}
