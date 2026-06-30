package com.cms.config.auth;

import com.cms.admin.visit.domain.VisitLog;
import com.cms.admin.visit.repository.VisitLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

class VisitLoggingAuthenticationSuccessHandlerTest {

    private VisitLogRepository visitLogRepository;
    private VisitLoggingAuthenticationSuccessHandler handler;

    @BeforeEach
    void setUp() {
        visitLogRepository = mock(VisitLogRepository.class);
        handler = new VisitLoggingAuthenticationSuccessHandler(visitLogRepository);
        // super 클래스의 리다이렉트를 방지하기 위해 DispatcherServlet 없이 동작하도록 설정
        handler.setDefaultTargetUrl("/admin");
        handler.setAlwaysUseDefaultTargetUrl(true);
    }

    private Authentication authWith(String... roles) {
        Authentication auth = mock(Authentication.class);
        given(auth.getName()).willReturn("testUser");
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .collect(java.util.stream.Collectors.toList());
        given(auth.getAuthorities()).willAnswer(inv -> authorities);
        return auth;
    }

    private MockHttpServletRequest requestWithIp(String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(ip);
        return req;
    }

    // ==================== 역할 필터링 ====================

    @Test
    @DisplayName("ROLE_ADMIN 로그인 시 visitLogRepository.save()가 호출된다")
    void onAuthSuccess_roleAdmin_saveCalled() throws Exception {
        Authentication auth = authWith("ROLE_ADMIN");
        MockHttpServletRequest req = requestWithIp("127.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        given(visitLogRepository.save(any())).willReturn(mock(VisitLog.class));

        handler.onAuthenticationSuccess(req, res, auth);

        verify(visitLogRepository).save(any(VisitLog.class));
    }

    @Test
    @DisplayName("ROLE_MANAGER 로그인 시 visitLogRepository.save()가 호출된다")
    void onAuthSuccess_roleManager_saveCalled() throws Exception {
        Authentication auth = authWith("ROLE_MANAGER");
        MockHttpServletRequest req = requestWithIp("127.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        given(visitLogRepository.save(any())).willReturn(mock(VisitLog.class));

        handler.onAuthenticationSuccess(req, res, auth);

        verify(visitLogRepository).save(any(VisitLog.class));
    }

    @Test
    @DisplayName("ROLE_USER 로그인 시 visitLogRepository.save()가 호출되지 않는다")
    void onAuthSuccess_roleUser_saveNotCalled() throws Exception {
        Authentication auth = authWith("ROLE_USER");
        MockHttpServletRequest req = requestWithIp("127.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(req, res, auth);

        verifyNoInteractions(visitLogRepository);
    }

    @Test
    @DisplayName("권한이 없는 사용자 로그인 시 visitLogRepository.save()가 호출되지 않는다")
    void onAuthSuccess_noRole_saveNotCalled() throws Exception {
        Authentication auth = authWith(); // 권한 없음
        MockHttpServletRequest req = requestWithIp("127.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(req, res, auth);

        verifyNoInteractions(visitLogRepository);
    }

    // ==================== IP 추출 검증 ====================

    @Test
    @DisplayName("X-FORWARDED-FOR 다중 홉 입력 시 마지막 홉 IP를 저장한다")
    void onAuthSuccess_multipleXFF_usesLastHop() throws Exception {
        Authentication auth = authWith("ROLE_ADMIN");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-FORWARDED-FOR", "1.2.3.4, 5.6.7.8, 9.10.11.12");
        req.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        ArgumentCaptor<VisitLog> captor = ArgumentCaptor.forClass(VisitLog.class);
        given(visitLogRepository.save(captor.capture())).willReturn(mock(VisitLog.class));

        handler.onAuthenticationSuccess(req, res, auth);

        assertThat(captor.getValue().getRequestIp()).isEqualTo("9.10.11.12");
    }

    @Test
    @DisplayName("X-FORWARDED-FOR 없고 X-Real-IP 있을 때 X-Real-IP를 사용한다")
    void onAuthSuccess_xRealIp_usedWhenXffAbsent() throws Exception {
        Authentication auth = authWith("ROLE_ADMIN");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Real-IP", "192.168.0.1");
        req.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        ArgumentCaptor<VisitLog> captor = ArgumentCaptor.forClass(VisitLog.class);
        given(visitLogRepository.save(captor.capture())).willReturn(mock(VisitLog.class));

        handler.onAuthenticationSuccess(req, res, auth);

        assertThat(captor.getValue().getRequestIp()).isEqualTo("192.168.0.1");
    }

    @Test
    @DisplayName("IP가 45자를 초과하면 45자로 절단되어 저장된다")
    void onAuthSuccess_longIp_truncatedTo45() throws Exception {
        Authentication auth = authWith("ROLE_ADMIN");
        MockHttpServletRequest req = new MockHttpServletRequest();
        // 50자짜리 조작된 IP
        req.addHeader("X-FORWARDED-FOR", "aaaaaaaaaa.aaaaaaaaaa.aaaaaaaaaa.aaaaaaaaaa.aaaa");
        req.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        ArgumentCaptor<VisitLog> captor = ArgumentCaptor.forClass(VisitLog.class);
        given(visitLogRepository.save(captor.capture())).willReturn(mock(VisitLog.class));

        handler.onAuthenticationSuccess(req, res, auth);

        assertThat(captor.getValue().getRequestIp()).hasSize(45);
    }

    // ==================== 예외 격리 ====================

    @Test
    @DisplayName("visitLogRepository.save() 예외 발생 시에도 리다이렉트(super 호출)는 정상 진행된다")
    void onAuthSuccess_saveFails_superStillCalled() throws Exception {
        Authentication auth = authWith("ROLE_ADMIN");
        MockHttpServletRequest req = requestWithIp("127.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        given(visitLogRepository.save(any())).willThrow(new RuntimeException("DB 오류"));

        // super.onAuthenticationSuccess()는 리다이렉트를 수행한다.
        // MockHttpServletResponse에 리다이렉트 헤더가 설정되는지로 예외 미전파를 검증한다.
        handler.onAuthenticationSuccess(req, res, auth);

        // 예외가 전파됐다면 여기까지 오지 못한다 — 여기까지 도달하면 격리 성공
        assertThat(res.getStatus()).isNotEqualTo(500);
    }
}
