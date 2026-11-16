package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2단계 통과 뒤 승격(PLAN-totp-two-factor.md 결정 2, S11) — 세션을 새로 만들지 않고, 저장 전후로 원래 세션·레지스트리 항목이
 * 살아 있을 때만 완전 권한을 남긴다. 판정과 저장 사이에 로그아웃·강제 만료가 끼면 인증 세션이 남지 않아야 한다.
 */
class TotpSessionPromoterTest {

    private final SessionRegistryImpl registry = new SessionRegistryImpl();
    private final CustomUserDetails principal = new CustomUserDetails(Member.builder().id(3L).userId("u3").pwd("h")
            .userType(Role.ROLE_MANAGER).status(MemberStatus.ACTIVE).build(), true);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest requestWithRegisteredSession(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/login/totp");
        request.setSession(session);
        registry.registerNewSession(session.getId(), principal);
        return request;
    }

    private static SecurityContext saved(MockHttpSession session) {
        return (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    }

    @Test
    @DisplayName("세션·레지스트리 항목이 살아 있으면 원래 역할 권한의 컨텍스트를 그 세션에 저장한다")
    void promotesLiveSession() {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = requestWithRegisteredSession(session);

        boolean promoted = new TotpSessionPromoter(registry).promote(request, new MockHttpServletResponse(), principal, session.getId());

        assertThat(promoted).isTrue();
        assertThat(saved(session).getAuthentication().getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_MANAGER");
    }

    @Test
    @DisplayName("S11: 판정 뒤 저장 전에 로그아웃(세션 무효화)이 끼면 새 세션을 만들지 않고 false")
    void logoutBeforeSaveLeavesNoSession() {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = requestWithRegisteredSession(session);
        String id = session.getId();
        session.invalidate();
        registry.removeSessionInformation(id);

        boolean promoted = new TotpSessionPromoter(registry).promote(request, new MockHttpServletResponse(), principal, id);

        assertThat(promoted).isFalse();
        assertThat(request.getSession(false)).as("세션 생성 금지").isNull();
    }

    @Test
    @DisplayName("S11: 저장 도중 로그아웃이 끼면(저장소가 무효화된 세션에 쓰다 실패) false이고 인증 세션이 남지 않는다")
    void logoutDuringSaveLeavesNoSession() {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = requestWithRegisteredSession(session);
        HttpSessionSecurityContextRepository racing = new HttpSessionSecurityContextRepository() {
            @Override
            public void saveContext(SecurityContext context, HttpServletRequest req, HttpServletResponse res) {
                session.invalidate(); // 다른 요청의 로그아웃
                registry.removeSessionInformation(session.getId());
                super.saveContext(context, req, res);
            }
        };
        racing.setAllowSessionCreation(false);

        boolean promoted = new TotpSessionPromoter(registry, racing).promote(request, new MockHttpServletResponse(), principal, session.getId());

        assertThat(promoted).isFalse();
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    @DisplayName("레지스트리에서 만료 표시된 세션(강제 만료·잠금)은 승격하지 않고 세션을 무효화한다")
    void expiredRegistryEntryIsRejected() {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = requestWithRegisteredSession(session);
        registry.getSessionInformation(session.getId()).expireNow();

        boolean promoted = new TotpSessionPromoter(registry).promote(request, new MockHttpServletResponse(), principal, session.getId());

        assertThat(promoted).isFalse();
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("요청 세션 ID가 판정 시작 때와 다르면 승격하지 않고 그 세션을 무효화한다(fail-closed)")
    void differentSessionIsRejected() {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = requestWithRegisteredSession(session);

        assertThat(new TotpSessionPromoter(registry).promote(request, new MockHttpServletResponse(), principal, "other-id")).isFalse();
        assertThat(session.isInvalid()).isTrue();
    }
}
