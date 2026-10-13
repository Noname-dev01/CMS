package com.cms.publicweb.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.Optional;

/**
 * {@link SiteScopedPublic} 컨트롤러의 핸들러 앞에서 {@code /{siteKey}}를 해석한다(PLAN-site-menu.md PR ② P2-1). 형식 위반·예약어·없는 키·비활성·
 * 관리자 타입 사이트는 구분 없이 {@code sendError(404)} — 컨테이너 오류 재디스패치로 기존 공용 404 화면이 나온다(첨부 다운로드와 같은 방식).
 * 성공하면 {@link PublicSite}를 요청 속성에 싣고 {@link PublicSiteModelAdvice}가 템플릿 모델로 옮긴다.
 *
 * <p>경로 패턴이 아니라 <b>핸들러의 표지</b>로 적용 대상을 정한다 — 패턴 기반이면 {@code /error} 재디스패치와 정적 리소스까지 가로채 404 루프를
 * 만들 수 있다. 조회 중 예외(DB 장애 등)는 잡지 않는다: {@code @ControllerAdvice}의 예외 처리가 기존 HTML 500 페이지로 답한다.
 */
@RequiredArgsConstructor
public class PublicSiteInterceptor implements HandlerInterceptor {

    private final ObjectProvider<PublicSiteService> publicSiteService;

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)
                || !AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), SiteScopedPublic.class)) {
            return true;
        }
        Map<String, String> variables =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String key = variables == null ? null : variables.get(PublicSiteRoutes.KEY_VARIABLE);

        Optional<PublicSite> site = publicSiteService.getObject().findActive(key);
        if (site.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }
        request.setAttribute(PublicSiteRoutes.REQUEST_ATTRIBUTE, site.get());
        return true;
    }
}
