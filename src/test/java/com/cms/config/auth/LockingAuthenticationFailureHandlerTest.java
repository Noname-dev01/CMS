package com.cms.config.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.LockedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class LockingAuthenticationFailureHandlerTest {

    private LoginFailureService loginFailureService;
    private LockingAuthenticationFailureHandler handler;

    @BeforeEach
    void setUp() {
        loginFailureService = mock(LoginFailureService.class);
        handler = new LockingAuthenticationFailureHandler(loginFailureService);
    }

    private MockHttpServletRequest loginRequest(String username) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/admin/login");
        req.setRequestURI("/admin/login");
        if (username != null) {
            req.setParameter("username", username);
        }
        req.setRemoteAddr("127.0.0.1");
        return req;
    }

    @Test
    @DisplayName("BadCredentialsException이면 recordFailure가 username·절단된 IP·URI와 함께 호출된다")
    void badCredentials_recordFailureCalledWithArguments() throws Exception {
        MockHttpServletRequest req = loginRequest("admin01");
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationFailure(req, res, new BadCredentialsException("자격 증명 실패"));

        verify(loginFailureService).recordFailure("admin01", "127.0.0.1", "/admin/login");
        assertThat(res.getRedirectedUrl()).isEqualTo("/admin/login-error");
    }

    @Test
    @DisplayName("조작된 45자 초과 IP는 45자로 절단되어 전달된다")
    void badCredentials_longIp_truncatedTo45() throws Exception {
        MockHttpServletRequest req = loginRequest("admin01");
        req.addHeader("X-FORWARDED-FOR", "a".repeat(60));
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationFailure(req, res, new BadCredentialsException("자격 증명 실패"));

        verify(loginFailureService).recordFailure(eq("admin01"), eq("a".repeat(45)), anyString());
    }

    @Test
    @DisplayName("LockedException은 카운트하지 않는다")
    void lockedException_notCounted() throws Exception {
        MockHttpServletRequest req = loginRequest("admin01");
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationFailure(req, res, new LockedException("잠긴 계정입니다."));

        verifyNoInteractions(loginFailureService);
        assertThat(res.getRedirectedUrl()).isEqualTo("/admin/login-error");
    }

    @Test
    @DisplayName("InternalAuthenticationServiceException(실배선에서 상태 예외가 래핑된 타입)은 카운트하지 않는다")
    void internalAuthServiceException_notCounted() throws Exception {
        MockHttpServletRequest req = loginRequest("admin01");
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationFailure(req, res,
                new InternalAuthenticationServiceException("래핑됨", new LockedException("잠긴 계정입니다.")));

        verifyNoInteractions(loginFailureService);
        assertThat(res.getRedirectedUrl()).isEqualTo("/admin/login-error");
    }

    @Test
    @DisplayName("username 파라미터가 없거나 공백이면 카운트하지 않는다")
    void blankUsername_notCounted() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        handler.onAuthenticationFailure(loginRequest(null), res, new BadCredentialsException("실패"));
        handler.onAuthenticationFailure(loginRequest("  "), new MockHttpServletResponse(),
                new BadCredentialsException("실패"));

        verifyNoInteractions(loginFailureService);
    }

    @Test
    @DisplayName("recordFailure가 예외를 던져도 에러 페이지 리다이렉트는 정상 진행된다 (격리)")
    void recordFailureThrows_redirectStillProceeds() throws Exception {
        willThrow(new RuntimeException("DB 오류"))
                .given(loginFailureService).recordFailure(anyString(), anyString(), anyString());
        MockHttpServletRequest req = loginRequest("admin01");
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationFailure(req, res, new BadCredentialsException("자격 증명 실패"));

        assertThat(res.getRedirectedUrl()).isEqualTo("/admin/login-error");
    }
}
