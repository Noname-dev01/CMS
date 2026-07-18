package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.visit.domain.VisitLog;
import com.cms.admin.visit.repository.VisitLogRepository;
import com.cms.config.auth.LoginFailureService.MemberSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

class VisitLoggingAuthenticationSuccessHandlerTest {

    /** 인증 당시 비밀번호 해시 — 정상 경로 스냅샷과 일치시킨다 */
    private static final String AUTH_HASH = "{bcrypt}hash-at-authentication";

    private VisitLogRepository visitLogRepository;
    private LoginFailureService loginFailureService;
    private PasswordExpiryService passwordExpiryService;
    private VisitLoggingAuthenticationSuccessHandler handler;

    @BeforeEach
    void setUp() {
        visitLogRepository = mock(VisitLogRepository.class);
        loginFailureService = mock(LoginFailureService.class);
        passwordExpiryService = mock(PasswordExpiryService.class);
        handler = new VisitLoggingAuthenticationSuccessHandler(visitLogRepository, loginFailureService, passwordExpiryService);
        // super 클래스의 리다이렉트를 방지하기 위해 DispatcherServlet 없이 동작하도록 설정
        handler.setDefaultTargetUrl("/admin");
        handler.setAlwaysUseDefaultTargetUrl(true);
    }

    /**
     * 인증 객체 + 정상 경로 스냅샷 스텁을 함께 구성한다.
     * principal은 실제 CustomUserDetails(인증 당시 해시 보유)로 제공하고,
     * fresh 스냅샷은 첫 번째 역할·ACTIVE·동일 해시로 일치시킨다.
     * (스텁을 Mockito 기본값(empty)으로 두면 모든 성공 테스트가 거부 분기로 오염된다.)
     */
    private Authentication authWith(String... roles) {
        Role principalRole = roles.length > 0 ? Role.valueOf(roles[0]) : Role.ROLE_USER;

        Authentication auth = mock(Authentication.class);
        given(auth.getName()).willReturn("testUser");
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .collect(java.util.stream.Collectors.toList());
        given(auth.getAuthorities()).willAnswer(inv -> authorities);
        given(auth.getPrincipal()).willReturn(new CustomUserDetails(memberOf(principalRole)));

        given(loginFailureService.resetFailuresAndCheckActive(anyString()))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.ACTIVE, principalRole, AUTH_HASH)));
        return auth;
    }

    private Member memberOf(Role role) {
        return Member.builder()
                .id(1L)
                .userId("testUser")
                .pwd(AUTH_HASH)
                .userName("테스트")
                .email("test@example.com")
                .userType(role)
                .status(MemberStatus.ACTIVE)
                .build();
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

    // ==================== 인증 완료 직전 재확인 (fail-closed) ====================

    /** 세션이 있는 요청을 만들고, 거부 시 세션 무효화·login-error 리다이렉트·방문 로그 미저장을 공통 단언한다. */
    private MockHttpSession assertRejected(Authentication auth) throws Exception {
        MockHttpServletRequest req = requestWithIp("127.0.0.1");
        MockHttpSession session = new MockHttpSession();
        req.setSession(session);
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(req, res, auth);

        assertThat(session.isInvalid()).as("이미 등록된 세션이 무효화되어야 한다").isTrue();
        assertThat(res.getRedirectedUrl()).isEqualTo("/admin/login-error");
        verifyNoInteractions(visitLogRepository);
        return session;
    }

    @Test
    @DisplayName("fresh 상태가 LOCKED이면 세션 무효화 + login-error 리다이렉트, 방문 로그 미저장")
    void onAuthSuccess_freshStatusLocked_rejected() throws Exception {
        Authentication auth = authWith("ROLE_ADMIN");
        given(loginFailureService.resetFailuresAndCheckActive(anyString()))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.LOCKED, Role.ROLE_ADMIN, AUTH_HASH)));

        assertRejected(auth);
    }

    @Test
    @DisplayName("fresh 역할이 인증 권한과 불일치(ADMIN→MANAGER 강등)하면 거부된다")
    void onAuthSuccess_roleDemotedDuringAuth_rejected() throws Exception {
        Authentication auth = authWith("ROLE_ADMIN");
        given(loginFailureService.resetFailuresAndCheckActive(anyString()))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.ACTIVE, Role.ROLE_MANAGER, AUTH_HASH)));

        assertRejected(auth);
    }

    @Test
    @DisplayName("fresh 해시가 인증 당시 해시와 불일치(인증 중 비밀번호 변경)하면 거부된다")
    void onAuthSuccess_passwordChangedDuringAuth_rejected() throws Exception {
        Authentication auth = authWith("ROLE_ADMIN");
        given(loginFailureService.resetFailuresAndCheckActive(anyString()))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.ACTIVE, Role.ROLE_ADMIN, "{bcrypt}new-hash")));

        assertRejected(auth);
    }

    @Test
    @DisplayName("fresh 조회 결과가 없으면(empty) 거부된다")
    void onAuthSuccess_memberNotFound_rejected() throws Exception {
        Authentication auth = authWith("ROLE_ADMIN");
        given(loginFailureService.resetFailuresAndCheckActive(anyString())).willReturn(Optional.empty());

        assertRejected(auth);
    }

    @Test
    @DisplayName("재확인 호출이 예외를 던지면 로그인이 거부된다 (fail-closed — 재확인은 인증 결정)")
    void onAuthSuccess_recheckThrows_rejected() throws Exception {
        Authentication auth = authWith("ROLE_ADMIN");
        given(loginFailureService.resetFailuresAndCheckActive(anyString()))
                .willThrow(new RuntimeException("DB 오류"));

        assertRejected(auth);
    }

    @Test
    @DisplayName("principal이 CustomUserDetails가 아니면(해시 비교 불가) 거부된다")
    void onAuthSuccess_unexpectedPrincipalType_rejected() throws Exception {
        Authentication auth = authWith("ROLE_ADMIN");
        given(auth.getPrincipal()).willReturn("plain-string-principal");

        assertRejected(auth);
    }

    @Test
    @DisplayName("재확인 통과 시 기본 성공 리다이렉트(/admin)가 수행된다")
    void onAuthSuccess_recheckPasses_redirectsToAdmin() throws Exception {
        Authentication auth = authWith("ROLE_ADMIN");
        MockHttpServletRequest req = requestWithIp("127.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        given(visitLogRepository.save(any())).willReturn(mock(VisitLog.class));

        handler.onAuthenticationSuccess(req, res, auth);

        assertThat(res.getRedirectedUrl()).isEqualTo("/admin");
    }
}
