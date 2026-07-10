package com.cms.config.security;

import com.cms.common.api.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.SessionInformationExpiredEvent;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.io.IOException;

/**
 * 강제 만료된 세션의 다음 요청을 처리하는 전략 (ConcurrentSessionFilter가 호출).
 * API 요청(/admin/api/**)은 ApiAuthenticationEntryPoint와 동일한 JSON 401 포맷으로 응답하고,
 * 그 외 페이지 요청은 로그인 페이지로 리다이렉트한다 — API 클라이언트가 HTML 리다이렉트를
 * 받는 계약 위반을 차단한다.
 */
public class AdminSessionExpiredStrategy implements SessionInformationExpiredStrategy {

    private static final RequestMatcher API_MATCHER =
            PathPatternRequestMatcher.withDefaults().matcher("/admin/api/**");
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Override
    public void onExpiredSessionDetected(SessionInformationExpiredEvent event) throws IOException {
        HttpServletRequest request = event.getRequest();
        HttpServletResponse response = event.getResponse();

        if (API_MATCHER.matches(request)) {
            ApiErrorResponse body = ApiErrorResponse.of(
                    request.getRequestURI(),
                    "UNAUTHORIZED",
                    "세션이 만료되었습니다. 다시 로그인해주세요."
            );
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(MAPPER.writeValueAsString(body));
            return;
        }

        response.sendRedirect("/admin/login");
    }
}
