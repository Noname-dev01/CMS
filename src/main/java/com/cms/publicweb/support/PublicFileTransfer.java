package com.cms.publicweb.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 파일 본문을 응답으로 보내는 공개 GET 핸들러의 표지(PLAN-public-transfer-limit.md D2). {@link PublicTransferInterceptor}가 이 표지가 있는 핸들러에만 동시 상한을 적용한다.
 * HEAD 핸들러에는 달지 않는다(본문을 읽지 않는다). 공개 코드에 응답 본문을 쓰는 새 경로를 만들면 이 표지를 달고 {@code PublicTransferConventionTest}의 승인 목록을 고친다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicFileTransfer {
}
