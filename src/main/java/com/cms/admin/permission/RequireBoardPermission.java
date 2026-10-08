package com.cms.admin.permission;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 게시판 단위 동작 요구(PLAN-board.md 쟁점 3). 메타 {@link PreAuthorize}가 {@link AdminPermissionEvaluator#checkBoard}에
 * <b>핸들러 메서드의 {@code boardId} 파라미터</b>({@code #boardId})를 넘긴다 — 붙이는 핸들러는 경로 변수 이름이 반드시 {@code boardId}여야 한다
 * (이름이 다르면 null → 거부, fail-closed). {@code AdminEndpointAuthorizationConventionTest}가 게시판 게이트 안의 API에 이 어노테이션과
 * 경로의 {@code {boardId}}를 강제한다. 페이지 컨트롤러에는 붙이지 않는다(HTML 403은 URL 게이트 몫).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("@adminPermission.checkBoard(#boardId, '{action}')")
public @interface RequireBoardPermission {

    PermissionAction action();
}
