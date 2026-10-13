package com.cms.publicweb.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 경로가 {@code /{siteKey}/…}로 시작하는 공개 컨트롤러의 표지(PLAN-site-menu.md PR ② P2-1). {@link PublicSiteInterceptor}가 표지가 있는
 * 컨트롤러의 핸들러에서만 사이트 키를 해석한다 — 없는·비활성·형식 위반·예약어 키는 핸들러에 도달하기 전에 같은 404로 끝난다.
 * {@code SecurityConfig}가 {@code /{siteKey}} 아래를 무인증으로 여므로 <b>이 표지가 없는 컨트롤러가 그 영역에 매핑되면 안 된다</b>
 * (전체 핸들러 대상 공개 인벤토리 시험이 강제한다). 핸들러는 동기여야 한다(ASYNC 재디스패치에서 재검증 정책이 필요 없게).
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SiteScopedPublic {
}
