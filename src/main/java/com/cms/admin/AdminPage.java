package com.cms.admin;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Thymeleaf 페이지를 서빙하는 관리자 컨트롤러 마커.
 *
 * <p>{@link AdminSidebarAdvice}가 이 어노테이션이 붙은 컨트롤러에만 사이드바 모델
 * (sidebarMenus, currentUri)을 주입한다. REST API 컨트롤러(@RestController)에는
 * 붙이지 않는다 — 뷰를 렌더링하지 않는 요청에 메뉴 DB 조회가 나가는 것을 막기 위함.
 *
 * <p>새 페이지 컨트롤러에 이 어노테이션을 누락하면 해당 페이지의 사이드바가 비어 보인다.
 * 누락은 AdminPageAnnotationConventionTest가 잡아낸다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AdminPage {
}
