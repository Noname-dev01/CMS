package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 SessionRegistryImpl로 등록→만료 왕복을 검증한다.
 * CustomUserDetails가 equals를 구현하지 않으므로 member id 비교로 대상을 찾는 계약을 확인한다.
 */
class AdminSessionServiceTest {

    private final SessionRegistry sessionRegistry = new SessionRegistryImpl();
    private final AdminSessionService adminSessionService = new AdminSessionService(sessionRegistry);

    private CustomUserDetails principal(Long memberId) {
        return new CustomUserDetails(Member.builder()
                .id(memberId)
                .userId("user" + memberId)
                .pwd("encoded")
                .userName("사용자" + memberId)
                .email("user" + memberId + "@test.com")
                .userType(Role.ROLE_MANAGER)
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
}
