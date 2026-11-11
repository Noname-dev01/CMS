package com.cms.config.auth;

import com.cms.common.exception.ReauthenticationRequiredException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link FullAuthenticationInterceptor} — 표지가 붙은 핸들러는 완전 인증(폼 로그인)만 통과하고 나머지는 전부 거부한다(fail-closed). */
class FullAuthenticationInterceptorTest {

    private final FullAuthenticationInterceptor interceptor = new FullAuthenticationInterceptor();
    private final MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/admin/api/members/me");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @SuppressWarnings("unused")
    static class Handlers {
        @RequireFullAuthentication
        public void sensitive() {
        }

        public void plain() {
        }
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Handlers(), Handlers.class.getMethod(name));
    }

    private static void authenticate(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Test
    @DisplayName("표지가 붙은 핸들러: 쿠키 자동 로그인(remember-me) 인증은 재인증 예외")
    void rememberMe_isRejected() throws Exception {
        authenticate(new RememberMeAuthenticationToken("key", "user", AuthorityUtils.createAuthorityList("ROLE_ADMIN")));

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler("sensitive")))
                .isInstanceOf(ReauthenticationRequiredException.class);
    }

    @Test
    @DisplayName("표지가 붙은 핸들러: 인증 없음·익명도 거부한다(fail-closed)")
    void missingOrAnonymous_isRejected() throws Exception {
        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler("sensitive")))
                .isInstanceOf(ReauthenticationRequiredException.class);

        authenticate(new AnonymousAuthenticationToken("key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler("sensitive")))
                .isInstanceOf(ReauthenticationRequiredException.class);
    }

    @Test
    @DisplayName("표지가 붙은 핸들러: 폼 로그인 인증은 통과한다")
    void fullAuthentication_passes() throws Exception {
        authenticate(UsernamePasswordAuthenticationToken.authenticated("user", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        assertThat(interceptor.preHandle(request, response, handler("sensitive"))).isTrue();

        TestingAuthenticationToken testing = new TestingAuthenticationToken("user", null, "ROLE_ADMIN");
        authenticate(testing);
        assertThat(interceptor.preHandle(request, response, handler("sensitive"))).isTrue();
    }

    @Test
    @DisplayName("표지 없는 핸들러와 HandlerMethod가 아닌 핸들러는 인증 종류와 무관하게 통과한다")
    void unmarkedHandlers_pass() throws Exception {
        authenticate(new RememberMeAuthenticationToken("key", "user", AuthorityUtils.createAuthorityList("ROLE_ADMIN")));

        assertThat(interceptor.preHandle(request, response, handler("plain"))).isTrue();
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }
}
