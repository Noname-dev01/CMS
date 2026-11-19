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

    /** 2단계 등록 시각(스냅샷의 사용 중 표지) */
    private static final java.time.LocalDateTime TOTP_ENABLED_AT = java.time.LocalDateTime.of(2026, 11, 19, 9, 0);

    /**
     * UTC 날짜(07-17)와 KST 날짜(07-18)가 갈리는 시각의 고정 Clock —
     * visitAt이 LocalDateTime.now() 직접 호출로 남아 있으면(UTC CI) 날짜 단언이 실패한다.
     */
    private static final java.time.Clock FIXED_KST_CLOCK = java.time.Clock.fixed(
            java.time.Instant.parse("2026-07-17T20:00:00Z"), java.time.ZoneId.of("Asia/Seoul"));

    private VisitLogRepository visitLogRepository;
    private LoginFailureService loginFailureService;
    private com.cms.admin.notification.service.NotificationRecorder notificationRecorder;
    private VisitLoggingAuthenticationSuccessHandler handler;

    @BeforeEach
    void setUp() {
        visitLogRepository = mock(VisitLogRepository.class);
        loginFailureService = mock(LoginFailureService.class);
        notificationRecorder = mock(com.cms.admin.notification.service.NotificationRecorder.class);
        handler = new VisitLoggingAuthenticationSuccessHandler(visitLogRepository, loginFailureService,
                notificationRecorder, FIXED_KST_CLOCK);
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
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.ACTIVE, principalRole, AUTH_HASH, null)));
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
    @DisplayName("ROLE_ADMIN의 로그인 완료(2단계 통과 뒤 recordCompletedLogin) 시 visitLogRepository.save()가 호출된다")
    void recordCompletedLogin_roleAdmin_saveCalled() throws Exception {
        // ADMIN은 2단계가 필수라 폼 로그인 성공 처리기에 완전 토큰으로 오지 않는다 — 방문은 2단계 통과 때 기록한다(PLAN-totp-required-admin.md 결정 4)
        Authentication auth = mock(Authentication.class);
        given(auth.getName()).willReturn("testUser");
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        given(auth.getAuthorities()).willAnswer(inv -> authorities);
        given(auth.getPrincipal()).willReturn(new CustomUserDetails(memberOf(Role.ROLE_ADMIN)));
        given(visitLogRepository.save(any())).willReturn(mock(VisitLog.class));

        handler.recordCompletedLogin(requestWithIp("127.0.0.1"), auth);

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
    @DisplayName("관리자 로그인 성공 시 읽은 알림 정리가 호출된다")
    void onAuthSuccess_admin_purgesOldReadNotifications() throws Exception {
        Authentication auth = authWith("ROLE_MANAGER");
        given(visitLogRepository.save(any())).willReturn(mock(VisitLog.class));

        handler.onAuthenticationSuccess(requestWithIp("127.0.0.1"), new MockHttpServletResponse(), auth);

        verify(notificationRecorder).purgeOldReadNotifications(1L);
    }

    @Test
    @DisplayName("재확인에서 거부된 로그인(잠긴 계정)에는 알림을 만들거나 정리하지 않는다")
    void onAuthSuccess_rejected_noNotificationWork() throws Exception {
        Authentication auth = authWith("ROLE_MANAGER");
        given(loginFailureService.resetFailuresAndCheckActive(anyString()))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.LOCKED, Role.ROLE_MANAGER, AUTH_HASH, null)));

        handler.onAuthenticationSuccess(requestWithIp("127.0.0.1"), new MockHttpServletResponse(), auth);

        org.mockito.Mockito.verifyNoInteractions(notificationRecorder);
    }

    @Test
    @DisplayName("ROLE_USER 로그인에는 알림 처리를 하지 않는다")
    void onAuthSuccess_roleUser_noNotificationWork() throws Exception {
        Authentication auth = authWith("ROLE_USER");

        handler.onAuthenticationSuccess(requestWithIp("127.0.0.1"), new MockHttpServletResponse(), auth);

        org.mockito.Mockito.verifyNoInteractions(notificationRecorder);
    }

    @Test
    @DisplayName("읽은 알림 정리가 예외를 던져도 로그인은 성공한다")
    void onAuthSuccess_notificationFailure_doesNotBlockLogin() throws Exception {
        Authentication auth = authWith("ROLE_MANAGER");
        given(visitLogRepository.save(any())).willReturn(mock(VisitLog.class));
        org.mockito.Mockito.doThrow(new IllegalStateException("정리 실패 주입")).when(notificationRecorder)
                .purgeOldReadNotifications(any());
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(requestWithIp("127.0.0.1"), res, auth);

        assertThat(res.getRedirectedUrl()).isEqualTo("/admin");     // 로그인 성공(거부 리다이렉트가 아님)
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

    // ==================== 방문 시각 시간원 검증 ====================

    @Test
    @DisplayName("visitAt은 주입된 KST Clock 기준으로 기록된다 — UTC JVM에서 LocalDateTime.now() 직접 호출이면 날짜가 달라져 실패")
    void onAuthSuccess_visitAt_usesInjectedKstClock() throws Exception {
        Authentication auth = authWith("ROLE_MANAGER");

        ArgumentCaptor<VisitLog> captor = ArgumentCaptor.forClass(VisitLog.class);
        given(visitLogRepository.save(captor.capture())).willReturn(mock(VisitLog.class));

        handler.onAuthenticationSuccess(requestWithIp("127.0.0.1"), new MockHttpServletResponse(), auth);

        // 고정 Clock의 KST 시각(2026-07-18T05:00) — UTC 기준(07-17T20:00)과 날짜가 다르다
        assertThat(captor.getValue().getVisitAt())
                .isEqualTo(java.time.LocalDateTime.now(FIXED_KST_CLOCK))
                .isEqualTo(java.time.LocalDateTime.of(2026, 7, 18, 5, 0, 0));
    }

    // ==================== IP 추출 검증 ====================

    @Test
    @DisplayName("X-FORWARDED-FOR 헤더는 무시하고 RemoteAddr만 신뢰한다(IP 위조 차단, 감사 H-03)")
    void onAuthSuccess_ignoresForwardedForHeader() throws Exception {
        Authentication auth = authWith("ROLE_MANAGER");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-FORWARDED-FOR", "1.2.3.4, 5.6.7.8, 9.10.11.12");
        req.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        ArgumentCaptor<VisitLog> captor = ArgumentCaptor.forClass(VisitLog.class);
        given(visitLogRepository.save(captor.capture())).willReturn(mock(VisitLog.class));

        handler.onAuthenticationSuccess(req, res, auth);

        assertThat(captor.getValue().getRequestIp()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("X-Real-IP 헤더도 무시하고 RemoteAddr만 신뢰한다")
    void onAuthSuccess_ignoresXRealIpHeader() throws Exception {
        Authentication auth = authWith("ROLE_MANAGER");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Real-IP", "192.168.0.1");
        req.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        ArgumentCaptor<VisitLog> captor = ArgumentCaptor.forClass(VisitLog.class);
        given(visitLogRepository.save(captor.capture())).willReturn(mock(VisitLog.class));

        handler.onAuthenticationSuccess(req, res, auth);

        assertThat(captor.getValue().getRequestIp()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("RemoteAddr이 45자를 초과하면 45자로 절단되어 저장된다")
    void onAuthSuccess_longRemoteAddr_truncatedTo45() throws Exception {
        Authentication auth = authWith("ROLE_MANAGER");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("a".repeat(60));
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
        Authentication auth = authWith("ROLE_MANAGER");
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
        Authentication auth = authWith("ROLE_MANAGER");
        given(loginFailureService.resetFailuresAndCheckActive(anyString()))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.LOCKED, Role.ROLE_MANAGER, AUTH_HASH, null)));

        assertRejected(auth);
    }

    @Test
    @DisplayName("fresh 역할이 인증 권한과 불일치(MANAGER→USER 강등)하면 거부된다")
    void onAuthSuccess_roleDemotedDuringAuth_rejected() throws Exception {
        Authentication auth = authWith("ROLE_MANAGER");
        given(loginFailureService.resetFailuresAndCheckActive(anyString()))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.ACTIVE, Role.ROLE_USER, AUTH_HASH, null)));

        assertRejected(auth);
    }

    @Test
    @DisplayName("fresh 해시가 인증 당시 해시와 불일치(인증 중 비밀번호 변경)하면 거부된다")
    void onAuthSuccess_passwordChangedDuringAuth_rejected() throws Exception {
        Authentication auth = authWith("ROLE_MANAGER");
        given(loginFailureService.resetFailuresAndCheckActive(anyString()))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.ACTIVE, Role.ROLE_MANAGER, "{bcrypt}new-hash", null)));

        assertRejected(auth);
    }

    // ==================== 2단계 인증(TOTP) — PLAN-totp-two-factor.md 결정 1 ====================

    /** 비밀번호만 통과한 중간 상태 토큰 — 권한은 TOTP_PENDING뿐, principal은 원래 역할을 가진 회원이다. */
    private Authentication pendingAuth(Role role) {
        Authentication auth = mock(Authentication.class);
        given(auth.getName()).willReturn("testUser");
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(TotpAuthorities.PENDING));
        given(auth.getAuthorities()).willAnswer(inv -> authorities);
        given(auth.getPrincipal()).willReturn(new CustomUserDetails(memberOf(role), true));
        return auth;
    }

    @Test
    @DisplayName("중간 상태 + 스냅샷 일치(2단계 사용 중) → 실패 횟수를 리셋하지 않는 조회만 하고, 세션에 대기 상태를 두고 코드 화면으로. 방문 기록 없음")
    void onAuthSuccess_pending_redirectsToCodePageWithoutReset() throws Exception {
        Authentication auth = pendingAuth(Role.ROLE_MANAGER);
        given(loginFailureService.checkActive("testUser"))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.ACTIVE, Role.ROLE_MANAGER, AUTH_HASH, TOTP_ENABLED_AT)));
        MockHttpServletRequest req = requestWithIp("127.0.0.1");
        MockHttpSession session = new MockHttpSession();
        req.setSession(session);
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(req, res, auth);

        assertThat(res.getRedirectedUrl()).isEqualTo("/admin/login/totp");
        TotpPendingState state = (TotpPendingState) session.getAttribute(TotpPendingState.SESSION_ATTRIBUTE);
        assertThat(state.memberId()).isEqualTo(1L);
        assertThat(state.since()).isEqualTo(java.time.LocalDateTime.now(FIXED_KST_CLOCK));
        verify(loginFailureService, never()).resetFailuresAndCheckActive(anyString());
        verifyNoInteractions(visitLogRepository, notificationRecorder);
    }

    @Test
    @DisplayName("FM3: 중간 상태인데 스냅샷은 2단계 미사용(로그인 중 해제 커밋) → 거부")
    void onAuthSuccess_pendingButDisabledMeanwhile_rejected() throws Exception {
        Authentication auth = pendingAuth(Role.ROLE_ADMIN);
        given(loginFailureService.checkActive("testUser"))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.ACTIVE, Role.ROLE_ADMIN, AUTH_HASH, null)));

        assertRejected(auth);
    }

    @Test
    @DisplayName("FM3: 완전 토큰인데 스냅샷은 2단계 사용 중(로그인 중 등록 커밋) → 거부 — 등록 전 스냅샷으로 만든 완전 권한 세션을 남기지 않는다")
    void onAuthSuccess_fullButEnabledMeanwhile_rejected() throws Exception {
        Authentication auth = authWith("ROLE_MANAGER");
        given(loginFailureService.resetFailuresAndCheckActive(anyString()))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.ACTIVE, Role.ROLE_MANAGER, AUTH_HASH, TOTP_ENABLED_AT)));

        assertRejected(auth);
    }

    @Test
    @DisplayName("중간 상태라도 상태·역할·해시 불일치는 기존처럼 거부")
    void onAuthSuccess_pendingWithStaleRole_rejected() throws Exception {
        Authentication auth = pendingAuth(Role.ROLE_ADMIN);
        given(loginFailureService.checkActive("testUser"))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.ACTIVE, Role.ROLE_MANAGER, AUTH_HASH, TOTP_ENABLED_AT)));

        assertRejected(auth);
    }

    // ==================== 2단계 인증 필수화(ADMIN) — PLAN-totp-required-admin.md 결정 4 ====================

    /** 필수 역할의 미등록 강제 등록 상태 토큰 — 권한은 TOTP_ENROLLMENT_REQUIRED뿐, principal은 원래 역할을 가진 회원이다. */
    private Authentication enrollmentRequiredAuth(Role role) {
        Authentication auth = mock(Authentication.class);
        given(auth.getName()).willReturn("testUser");
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(TotpAuthorities.ENROLLMENT_REQUIRED));
        given(auth.getAuthorities()).willAnswer(inv -> authorities);
        given(auth.getPrincipal()).willReturn(new CustomUserDetails(memberOf(role), false));
        return auth;
    }

    @Test
    @DisplayName("강제 등록 상태 + 스냅샷 일치(미등록 ADMIN) → 실패 횟수 리셋 없는 조회만, 강제 등록 화면으로. 방문 기록·코드 대기 표지 없음")
    void onAuthSuccess_enrollmentRequired_redirectsToEnrollmentPage() throws Exception {
        Authentication auth = enrollmentRequiredAuth(Role.ROLE_ADMIN);
        given(loginFailureService.checkActive("testUser"))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.ACTIVE, Role.ROLE_ADMIN, AUTH_HASH, null)));
        MockHttpServletRequest req = requestWithIp("127.0.0.1");
        MockHttpSession session = new MockHttpSession();
        req.setSession(session);
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(req, res, auth);

        assertThat(res.getRedirectedUrl()).isEqualTo("/admin/login/totp-enrollment");
        assertThat(session.isInvalid()).isFalse();
        assertThat(session.getAttribute(TotpPendingState.SESSION_ATTRIBUTE)).isNull();
        verify(loginFailureService, never()).resetFailuresAndCheckActive(anyString());
        verifyNoInteractions(visitLogRepository, notificationRecorder);
    }

    @Test
    @DisplayName("FM2: 강제 등록 상태인데 스냅샷은 2단계 사용 중(다른 세션에서 등록 커밋) → 거부")
    void onAuthSuccess_enrollmentRequiredButEnrolledMeanwhile_rejected() throws Exception {
        Authentication auth = enrollmentRequiredAuth(Role.ROLE_ADMIN);
        given(loginFailureService.checkActive("testUser"))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.ACTIVE, Role.ROLE_ADMIN, AUTH_HASH, TOTP_ENABLED_AT)));

        assertRejected(auth);
    }

    @Test
    @DisplayName("FM2: 강제 등록 상태인데 스냅샷 역할이 MANAGER(로그인 중 강등 커밋) → 거부")
    void onAuthSuccess_enrollmentRequiredButDemotedMeanwhile_rejected() throws Exception {
        Authentication auth = enrollmentRequiredAuth(Role.ROLE_ADMIN);
        given(loginFailureService.checkActive("testUser"))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.ACTIVE, Role.ROLE_MANAGER, AUTH_HASH, null)));

        assertRejected(auth);
    }

    @Test
    @DisplayName("FM2: 2단계 없는 ADMIN 완전 토큰(필수 역할) → 거부 — 2단계를 거치지 않은 완전 권한 ADMIN 세션을 남기지 않는다")
    void onAuthSuccess_fullAdminWithoutTotp_rejected() throws Exception {
        Authentication auth = authWith("ROLE_ADMIN");

        assertRejected(auth);
    }

    @Test
    @DisplayName("FM2: MANAGER 완전 토큰인데 스냅샷은 ADMIN·미등록(로그인 중 승격 커밋) → 거부")
    void onAuthSuccess_fullManagerPromotedMeanwhile_rejected() throws Exception {
        Authentication auth = authWith("ROLE_MANAGER");
        given(loginFailureService.resetFailuresAndCheckActive(anyString()))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.ACTIVE, Role.ROLE_ADMIN, AUTH_HASH, null)));

        assertRejected(auth);
    }

    @Test
    @DisplayName("fresh 조회 결과가 없으면(empty) 거부된다")
    void onAuthSuccess_memberNotFound_rejected() throws Exception {
        Authentication auth = authWith("ROLE_MANAGER");
        given(loginFailureService.resetFailuresAndCheckActive(anyString())).willReturn(Optional.empty());

        assertRejected(auth);
    }

    @Test
    @DisplayName("재확인 호출이 예외를 던지면 로그인이 거부된다 (fail-closed — 재확인은 인증 결정)")
    void onAuthSuccess_recheckThrows_rejected() throws Exception {
        Authentication auth = authWith("ROLE_MANAGER");
        given(loginFailureService.resetFailuresAndCheckActive(anyString()))
                .willThrow(new RuntimeException("DB 오류"));

        assertRejected(auth);
    }

    @Test
    @DisplayName("principal이 CustomUserDetails가 아니면(해시 비교 불가) 거부된다")
    void onAuthSuccess_unexpectedPrincipalType_rejected() throws Exception {
        Authentication auth = authWith("ROLE_MANAGER");
        given(auth.getPrincipal()).willReturn("plain-string-principal");

        assertRejected(auth);
    }

    @Test
    @DisplayName("재확인 통과 시 기본 성공 리다이렉트(/admin)가 수행된다")
    void onAuthSuccess_recheckPasses_redirectsToAdmin() throws Exception {
        Authentication auth = authWith("ROLE_MANAGER");
        MockHttpServletRequest req = requestWithIp("127.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        given(visitLogRepository.save(any())).willReturn(mock(VisitLog.class));

        handler.onAuthenticationSuccess(req, res, auth);

        assertThat(res.getRedirectedUrl()).isEqualTo("/admin");
    }
    // ==================== 패스키 단독 로그인 — PLAN-passkey-login.md 결정 4 ====================

    /** 패스키 로그인 토큰 — 권한은 원래 역할, 검증 때 읽은 TOTP 등록 시각을 싣는다. */
    private Authentication passkeyAuth(Role role) {
        return new PasskeyLoginAuthenticationToken(new CustomUserDetails(memberOf(role), true), TOTP_ENABLED_AT);
    }

    private MockHttpSession assertPasskeyRejected(Authentication auth) throws Exception {
        MockHttpServletRequest req = requestWithIp("127.0.0.1");
        MockHttpSession session = new MockHttpSession();
        req.setSession(session);
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(req, res, auth);

        assertThat(session.isInvalid()).as("이미 등록된 세션이 무효화되어야 한다").isTrue();
        assertThat(res.getRedirectedUrl()).isEqualTo("/admin/login?passkeyError");
        verifyNoInteractions(visitLogRepository);
        return session;
    }

    @Test
    @DisplayName("패스키 토큰 + 같은 TOTP 등록 시각 → 실패 횟수 리셋 경로로 재확인, 방문 기록, 남은 중간 상태 표지 제거, /admin")
    void onAuthSuccess_passkey_sameGeneration_completesLogin() throws Exception {
        given(loginFailureService.resetFailuresAndCheckActive("testUser"))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.ACTIVE, Role.ROLE_ADMIN, AUTH_HASH, TOTP_ENABLED_AT)));
        given(visitLogRepository.save(any())).willReturn(mock(VisitLog.class));
        MockHttpServletRequest req = requestWithIp("127.0.0.1");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(TotpPendingState.SESSION_ATTRIBUTE, new TotpPendingState(1L, java.time.LocalDateTime.now(FIXED_KST_CLOCK)));
        session.setAttribute(PasskeyAssertionState.SESSION_ATTRIBUTE, "stale");
        session.setAttribute(PasskeyLoginState.SESSION_ATTRIBUTE, "stale");
        req.setSession(session);
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(req, res, passkeyAuth(Role.ROLE_ADMIN));

        assertThat(res.getRedirectedUrl()).isEqualTo("/admin");
        assertThat(session.isInvalid()).isFalse();
        assertThat(session.getAttribute(TotpPendingState.SESSION_ATTRIBUTE)).isNull();
        assertThat(session.getAttribute(PasskeyAssertionState.SESSION_ATTRIBUTE)).isNull();
        assertThat(session.getAttribute(PasskeyLoginState.SESSION_ATTRIBUTE)).isNull();
        verify(loginFailureService, never()).checkActive(anyString());
        verify(visitLogRepository).save(any(VisitLog.class));
    }

    @Test
    @DisplayName("F5: 패스키 토큰인데 스냅샷은 2단계 미사용(로그인 중 해제 커밋) → 거부")
    void onAuthSuccess_passkey_totpDisabledMeanwhile_rejected() throws Exception {
        given(loginFailureService.resetFailuresAndCheckActive("testUser"))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.ACTIVE, Role.ROLE_MANAGER, AUTH_HASH, null)));

        assertPasskeyRejected(passkeyAuth(Role.ROLE_MANAGER));
    }

    @Test
    @DisplayName("F5(리뷰 1-2): 패스키 토큰인데 스냅샷의 등록 시각이 다르다(로그인 중 해제 → 재등록 커밋) → 거부")
    void onAuthSuccess_passkey_totpReenrolledMeanwhile_rejected() throws Exception {
        given(loginFailureService.resetFailuresAndCheckActive("testUser"))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.ACTIVE, Role.ROLE_MANAGER, AUTH_HASH, TOTP_ENABLED_AT.plusSeconds(1))));

        assertPasskeyRejected(passkeyAuth(Role.ROLE_MANAGER));
    }

    @Test
    @DisplayName("F5: 패스키 토큰이라도 상태·역할·해시 불일치는 거부")
    void onAuthSuccess_passkey_staleRoleOrHash_rejected() throws Exception {
        given(loginFailureService.resetFailuresAndCheckActive("testUser"))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.ACTIVE, Role.ROLE_MANAGER, AUTH_HASH, TOTP_ENABLED_AT)));
        assertPasskeyRejected(passkeyAuth(Role.ROLE_ADMIN));

        given(loginFailureService.resetFailuresAndCheckActive("testUser"))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.ACTIVE, Role.ROLE_ADMIN, "{bcrypt}new-hash", TOTP_ENABLED_AT)));
        assertPasskeyRejected(passkeyAuth(Role.ROLE_ADMIN));

        given(loginFailureService.resetFailuresAndCheckActive("testUser"))
                .willReturn(Optional.of(new MemberSnapshot(MemberStatus.LOCKED, Role.ROLE_ADMIN, AUTH_HASH, TOTP_ENABLED_AT)));
        assertPasskeyRejected(passkeyAuth(Role.ROLE_ADMIN));
    }
}
