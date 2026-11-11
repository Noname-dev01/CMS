package com.cms.config.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.util.WebUtils;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LoginSessionMutexFilter} — 세션이 있는 폼 로그인({@code POST /admin/login})만 그 세션의 뮤텍스 안에서 나머지 체인을 실행한다
 * (PLAN-sensitive-reauthentication.md 쟁점 7). 재인증과 같은 {@code WebUtils.getSessionMutex}를 쓰는지가 계약이다.
 */
class LoginSessionMutexFilterTest {

    private final LoginSessionMutexFilter filter = new LoginSessionMutexFilter();

    /** 체인이 실행된 순간 그 세션의 뮤텍스를 쥐고 있었는지 기록한다. */
    private static final class RecordingChain implements FilterChain {
        private final HttpSession session;
        final AtomicBoolean called = new AtomicBoolean();
        final AtomicBoolean heldMutex = new AtomicBoolean();

        RecordingChain(HttpSession session) {
            this.session = session;
        }

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            called.set(true);
            heldMutex.set(session != null && Thread.holdsLock(WebUtils.getSessionMutex(session)));
        }
    }

    private static MockHttpServletRequest request(String method, String path, MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        if (session != null) {
            request.setSession(session);
        }
        return request;
    }

    @Test
    @DisplayName("세션이 있는 POST /admin/login은 그 세션의 뮤텍스(WebUtils.getSessionMutex) 안에서 체인을 실행한다")
    void loginWithSession_runsInsideSessionMutex() throws Exception {
        MockHttpSession session = new MockHttpSession();
        RecordingChain chain = new RecordingChain(session);

        filter.doFilter(request("POST", "/admin/login", session), new MockHttpServletResponse(), chain);

        assertThat(chain.called).isTrue();
        assertThat(chain.heldMutex).isTrue();
    }

    @Test
    @DisplayName("세션이 없는 로그인·GET 로그인 화면·다른 경로의 POST는 감싸지 않고 그대로 통과한다")
    void otherRequests_passThroughUnlocked() throws Exception {
        RecordingChain noSession = new RecordingChain(null);
        filter.doFilter(request("POST", "/admin/login", null), new MockHttpServletResponse(), noSession);
        assertThat(noSession.called).isTrue();

        MockHttpSession session = new MockHttpSession();
        RecordingChain get = new RecordingChain(session);
        filter.doFilter(request("GET", "/admin/login", session), new MockHttpServletResponse(), get);
        assertThat(get.called).isTrue();
        assertThat(get.heldMutex).isFalse();

        RecordingChain otherPost = new RecordingChain(session);
        filter.doFilter(request("POST", "/admin/api/members/me/reauthentication", session), new MockHttpServletResponse(), otherPost);
        assertThat(otherPost.called).isTrue();
        assertThat(otherPost.heldMutex).isFalse();
    }
}
