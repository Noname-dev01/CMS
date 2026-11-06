package com.cms.publicweb.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@link PublicFileTransfer} 핸들러의 동시 상한(PLAN-public-transfer-limit.md D2·D5·D6). {@link PublicSiteInterceptor} 뒤에 등록해 없는 사이트의 요청은 슬롯을 쓰지 않는다.
 *
 * <p>슬롯은 공개 판정 전에 잡는다 — 포화 상태에서는 파일 유무와 무관하게 같은 응답이라 존재 여부가 드러나지 않는다. 로그인 세션은 슬롯을 쓰지 않는다(관리 편집기 이미지가 익명 포화로 깨지지 않게).
 *
 * <p><b>반환은 요청 속성의 토큰으로만 한다.</b> {@code afterCompletion}은 {@code preHandle}이 {@code true}를 반환한 모든 요청(표지 없는 요청·HEAD·로그인 면제 포함)에 불리므로 호출 자체는
 * 획득의 증거가 아니다. 토큰을 지우면서 반환하므로 같은 요청이 두 번 반환하지 않는다. 공개 핸들러는 동기만 허용되므로 전송은 {@code afterCompletion} 전에 끝난다.
 */
@RequiredArgsConstructor
public class PublicTransferInterceptor implements HandlerInterceptor {

    private static final String TOKEN_ATTRIBUTE = PublicTransferInterceptor.class.getName() + ".acquiredIp";
    private static final String RETRY_AFTER_SECONDS = "5";

    private final ObjectProvider<PublicTransferLimiter> limiter;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod method)
                || !method.hasMethodAnnotation(PublicFileTransfer.class)
                || isAuthenticatedUser()) {
            return true;
        }
        // X-Forwarded-For는 위조할 수 있어 쓰지 않는다(레이트리밋과 같은 규약).
        String ip = request.getRemoteAddr();
        return switch (limiter.getObject().tryAcquire(ip)) {
            case ACQUIRED -> {
                request.setAttribute(TOKEN_ATTRIBUTE, ip);
                yield true;
            }
            case IP_LIMIT -> reject(response, 429);
            case TOTAL_LIMIT -> reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        };
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (request.getAttribute(TOKEN_ATTRIBUTE) instanceof String ip) {
            request.removeAttribute(TOKEN_ATTRIBUTE);
            limiter.getObject().release(ip);
        }
    }

    private static boolean reject(HttpServletResponse response, int status) throws Exception {
        response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        response.sendError(status);
        return false;
    }

    private static boolean isAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
