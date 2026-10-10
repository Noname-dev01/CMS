package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.config.auth.AdminSessionService.ActiveSession;
import com.cms.config.auth.AdminSessionService.MemberExpiration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 SessionRegistryImpl로 등록→만료 왕복을 검증한다.
 * CustomUserDetails가 equals를 구현하지 않으므로 member id 비교로 대상을 찾는 계약을 확인한다.
 * 세션 관리 화면용 조회·핸들 만료는 PLAN-session-management.md 쟁점 1·3·4·8의 계약을 고정한다.
 */
class AdminSessionServiceTest {

    private final SessionRegistry sessionRegistry = new SessionRegistryImpl();
    private final AdminSessionService adminSessionService = new AdminSessionService(sessionRegistry);

    private CustomUserDetails principal(Long memberId) {
        return principal(memberId, Role.ROLE_MANAGER);
    }

    private CustomUserDetails principal(Long memberId, Role role) {
        return new CustomUserDetails(Member.builder()
                .id(memberId)
                .userId("user" + memberId)
                .pwd("encoded")
                .userName("사용자" + memberId)
                .email("user" + memberId + "@test.com")
                .userType(role)
                .status(MemberStatus.ACTIVE)
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .build());
    }

    @Test
    @DisplayName("대상 회원의 모든 세션이 만료되고, 다른 회원의 세션은 유지된다")
    void expireSessionsFor_expiresOnlyTargetSessions() {
        sessionRegistry.registerNewSession("session-1", principal(1L));
        sessionRegistry.registerNewSession("session-2", principal(1L));
        sessionRegistry.registerNewSession("session-3", principal(2L));

        adminSessionService.expireSessionsFor(1L);

        assertTrue(sessionRegistry.getSessionInformation("session-1").isExpired());
        assertTrue(sessionRegistry.getSessionInformation("session-2").isExpired());
        assertFalse(sessionRegistry.getSessionInformation("session-3").isExpired());
    }

    @Test
    @DisplayName("등록된 세션이 없는 회원 id는 아무 일도 하지 않는다")
    void expireSessionsFor_noSessions_noop() {
        sessionRegistry.registerNewSession("session-1", principal(1L));

        assertDoesNotThrow(() -> adminSessionService.expireSessionsFor(99L));
        assertFalse(sessionRegistry.getSessionInformation("session-1").isExpired());
    }

    @Test
    @DisplayName("null id는 no-op")
    void expireSessionsFor_nullId_noop() {
        assertDoesNotThrow(() -> adminSessionService.expireSessionsFor(null));
    }

    // ===================== 핸들 =====================

    @Test
    @DisplayName("핸들은 32자 hex이고 결정적이며 원문 세션 ID를 포함하지 않는다")
    void handleOf_isDeterministicHexAndHidesSessionId() {
        String sessionId = "0123456789ABCDEF0123456789ABCDEF";

        String handle = AdminSessionService.handleOf(sessionId);

        assertEquals(32, handle.length());
        assertTrue(handle.matches("^[0-9a-f]{32}$"));
        assertEquals(handle, AdminSessionService.handleOf(sessionId));
        assertFalse(handle.contains(sessionId));
        assertNotEquals(handle, AdminSessionService.handleOf(sessionId + "x"));
    }

    // ===================== 목록 =====================

    @Test
    @DisplayName("목록: 만료되지 않은 세션만, 같은 회원의 서로 다른 principal 세션도 모두 나온다 — 원문 ID 대신 핸들")
    void listActiveSessions_returnsOnlyActiveWithHandles() {
        sessionRegistry.registerNewSession("session-1", principal(1L));   // 로그인마다 principal 인스턴스가 다르다
        sessionRegistry.registerNewSession("session-2", principal(1L));
        sessionRegistry.registerNewSession("session-3", principal(2L, Role.ROLE_ADMIN));
        sessionRegistry.getSessionInformation("session-2").expireNow();

        List<ActiveSession> sessions = adminSessionService.listActiveSessions();

        assertEquals(2, sessions.size());
        assertTrue(sessions.stream().anyMatch(s -> s.memberId().equals(1L)
                && s.handle().equals(AdminSessionService.handleOf("session-1")) && s.role().equals("ROLE_MANAGER")));
        assertTrue(sessions.stream().anyMatch(s -> s.memberId().equals(2L)
                && s.handle().equals(AdminSessionService.handleOf("session-3")) && s.role().equals("ROLE_ADMIN")
                && s.userId().equals("user2") && s.userName().equals("사용자2")));
        assertTrue(sessions.stream().noneMatch(s -> s.handle().contains("session-")));
    }

    @Test
    @DisplayName("목록: 등록된 세션이 없으면 빈 목록")
    void listActiveSessions_empty() {
        assertTrue(adminSessionService.listActiveSessions().isEmpty());
    }

    @Test
    @DisplayName("목록 정렬: 마지막 요청 내림차순, 같은 시각이면 회원 ID·핸들 오름차순으로 고정")
    void listActiveSessions_sortsByLastRequestThenMemberThenHandle() {
        SessionRegistry registry = Mockito.mock(SessionRegistry.class);
        CustomUserDetails p1 = principal(1L);
        CustomUserDetails p2 = principal(2L);
        CustomUserDetails p3 = principal(3L);
        Date t0 = new Date(1_000_000L);
        Date t1 = new Date(2_000_000L);
        SessionInformation old = new SessionInformation(p3, "s-old", t0);
        SessionInformation tieA = new SessionInformation(p2, "s-tie-a", t1);
        SessionInformation tieB = new SessionInformation(p1, "s-tie-b", t1);
        Mockito.when(registry.getAllPrincipals()).thenReturn(List.of(p3, p2, p1));
        Mockito.when(registry.getAllSessions(p3, false)).thenReturn(List.of(old));
        Mockito.when(registry.getAllSessions(p2, false)).thenReturn(List.of(tieA));
        Mockito.when(registry.getAllSessions(p1, false)).thenReturn(List.of(tieB));

        List<ActiveSession> sessions = new AdminSessionService(registry).listActiveSessions();

        assertEquals(List.of(1L, 2L, 3L), sessions.stream().map(ActiveSession::memberId).toList());
        assertEquals(Instant.ofEpochMilli(2_000_000L), sessions.get(0).lastRequest());
    }

    // ===================== 핸들로 만료 =====================

    @Test
    @DisplayName("핸들 만료: 그 세션만 만료되고 같은 회원의 다른 세션·다른 회원은 유지된다")
    void expireByHandle_expiresOnlyThatSession() {
        sessionRegistry.registerNewSession("session-1", principal(1L));
        sessionRegistry.registerNewSession("session-2", principal(1L));
        sessionRegistry.registerNewSession("session-3", principal(2L));

        Optional<ActiveSession> result = adminSessionService.expireByHandle(AdminSessionService.handleOf("session-2"));

        assertTrue(result.isPresent());
        assertEquals(1L, result.get().memberId());
        assertTrue(sessionRegistry.getSessionInformation("session-2").isExpired());
        assertFalse(sessionRegistry.getSessionInformation("session-1").isExpired());
        assertFalse(sessionRegistry.getSessionInformation("session-3").isExpired());
    }

    @Test
    @DisplayName("핸들 만료: 없는 핸들·이미 만료된 세션·레지스트리에서 사라진 세션은 빈 값(404 근거)")
    void expireByHandle_missingExpiredOrRemoved_isEmpty() {
        sessionRegistry.registerNewSession("session-1", principal(1L));
        sessionRegistry.registerNewSession("session-2", principal(1L));
        sessionRegistry.registerNewSession("session-3", principal(1L));
        sessionRegistry.getSessionInformation("session-2").expireNow();
        sessionRegistry.removeSessionInformation("session-3");   // 로그아웃·타임아웃으로 소멸

        assertTrue(adminSessionService.expireByHandle(AdminSessionService.handleOf("nope")).isEmpty());
        assertTrue(adminSessionService.expireByHandle(AdminSessionService.handleOf("session-2")).isEmpty());
        assertTrue(adminSessionService.expireByHandle(AdminSessionService.handleOf("session-3")).isEmpty());
        assertFalse(sessionRegistry.getSessionInformation("session-1").isExpired());
    }

    @Test
    @DisplayName("핸들 만료는 멱등 — 객체를 미리 얻은 두 호출이 겹쳐도 만료 상태는 같다(같은 세션을 두 번 만료해도 예외 없음)")
    void expireByHandle_isIdempotentOnSameSessionObject() {
        sessionRegistry.registerNewSession("session-1", principal(1L));
        SessionInformation info = sessionRegistry.getSessionInformation("session-1");

        info.expireNow();
        assertDoesNotThrow(info::expireNow);
        assertTrue(info.isExpired());
    }

    // ===================== 회원 단위 만료(현재 세션 제외) =====================

    @Test
    @DisplayName("회원 단위 만료: 제외할 세션은 건너뛰고 나머지만 만료하며 제외 여부를 알려 준다")
    void expireSessionsFor_withExcept_skipsCurrentSession() {
        sessionRegistry.registerNewSession("session-1", principal(1L));
        sessionRegistry.registerNewSession("session-2", principal(1L));
        sessionRegistry.registerNewSession("session-3", principal(1L));
        sessionRegistry.registerNewSession("session-4", principal(2L));

        MemberExpiration result = adminSessionService.expireSessionsFor(1L, "session-2");

        assertEquals(2, result.expiredCount());
        assertTrue(result.currentExcluded());
        assertTrue(sessionRegistry.getSessionInformation("session-1").isExpired());
        assertFalse(sessionRegistry.getSessionInformation("session-2").isExpired());
        assertTrue(sessionRegistry.getSessionInformation("session-3").isExpired());
        assertFalse(sessionRegistry.getSessionInformation("session-4").isExpired());
    }

    @Test
    @DisplayName("회원 단위 만료: 제외 세션이 대상 회원의 것이 아니면(다른 회원 대상) 전부 만료하고 제외 없음")
    void expireSessionsFor_exceptBelongsToOtherMember_expiresAll() {
        sessionRegistry.registerNewSession("session-1", principal(1L));
        sessionRegistry.registerNewSession("session-2", principal(1L));
        sessionRegistry.registerNewSession("caller", principal(9L, Role.ROLE_ADMIN));

        MemberExpiration result = adminSessionService.expireSessionsFor(1L, "caller");

        assertEquals(2, result.expiredCount());
        assertFalse(result.currentExcluded());
        assertFalse(sessionRegistry.getSessionInformation("caller").isExpired());
    }

    @Test
    @DisplayName("회원 단위 만료: 이미 만료된 세션은 세지 않고, 대상이 없으면 0건·제외 없음, null 회원은 0건")
    void expireSessionsFor_countsOnlyActiveSessions() {
        sessionRegistry.registerNewSession("session-1", principal(1L));
        sessionRegistry.registerNewSession("session-2", principal(1L));
        sessionRegistry.getSessionInformation("session-1").expireNow();

        assertEquals(1, adminSessionService.expireSessionsFor(1L, null).expiredCount());
        assertEquals(new MemberExpiration(0, false), adminSessionService.expireSessionsFor(99L, "session-2"));
        assertEquals(new MemberExpiration(0, false), adminSessionService.expireSessionsFor(null, "session-2"));
    }

    @Test
    @DisplayName("회원 단위 만료: 제외 세션이 null(현재 세션 없음)이면 제외 없이 전부 만료")
    void expireSessionsFor_nullExcept_expiresAll() {
        sessionRegistry.registerNewSession("session-1", principal(1L));

        MemberExpiration result = adminSessionService.expireSessionsFor(1L, null);

        assertEquals(1, result.expiredCount());
        assertFalse(result.currentExcluded());
    }
}
