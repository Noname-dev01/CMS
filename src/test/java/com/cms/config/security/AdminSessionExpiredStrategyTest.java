package com.cms.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.web.session.SessionInformationExpiredEvent;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 만료된 세션의 다음 요청 응답 계약 검증:
 * API 요청(/admin/api/**)은 JSON 401, 페이지 요청은 /admin/login 리다이렉트.
 */
class AdminSessionExpiredStrategyTest {

    private final AdminSessionExpiredStrategy strategy = new AdminSessionExpiredStrategy();

    private SessionInformationExpiredEvent event(MockHttpServletRequest request,
                                                 MockHttpServletResponse response) {
        return new SessionInformationExpiredEvent(
                new SessionInformation("principal", "session-1", new Date()),
                request,
                response
        );
    }

    @Test
    @DisplayName("API 요청은 JSON 401(UNAUTHORIZED)로 응답한다 — HTML 리다이렉트 금지")
    void apiRequest_returnsJson401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/api/members/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        strategy.onExpiredSessionDetected(event(request, response));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString())
                .contains("\"UNAUTHORIZED\"")
                .contains("세션이 만료되었습니다");
        assertThat(response.getRedirectedUrl()).isNull();
    }

    @Test
    @DisplayName("페이지 요청은 /admin/login으로 리다이렉트한다")
    void pageRequest_redirectsToLogin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/member/manage");
        MockHttpServletResponse response = new MockHttpServletResponse();

        strategy.onExpiredSessionDetected(event(request, response));

        assertThat(response.getRedirectedUrl()).isEqualTo("/admin/login");
    }
}
