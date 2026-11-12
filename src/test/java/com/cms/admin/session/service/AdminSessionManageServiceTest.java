package com.cms.admin.session.service;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.session.dto.response.SessionExpireResult;
import com.cms.admin.session.dto.response.SessionListResponse;
import com.cms.admin.session.dto.response.SessionMemberResponse;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.config.auth.AdminSessionService;
import com.cms.config.auth.CustomUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 세션 관리 서비스 단위 시험(실제 {@link SessionRegistryImpl}). 현재 세션 보호·404·건수·라벨 계약을 고정한다
 * (PLAN-session-management.md 쟁점 3·4·6·8). 감사 행 자체(AOP·DB)는 통합 시험이 본다.
 */
class AdminSessionManageServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SessionRegistry registry = new SessionRegistryImpl();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), KST);
    private final AdminSessionManageService service = new AdminSessionManageService(new AdminSessionService(registry), clock);

    private CustomUserDetails principal(Long memberId, Role role) {
        return new CustomUserDetails(Member.builder()
                .id(memberId).userId("user" + memberId).pwd("encoded").userName("사용자" + memberId)
                .email("user" + memberId + "@test.com").userType(role).status(MemberStatus.ACTIVE)
                .createDate(LocalDateTime.now()).updateDate(LocalDateTime.now()).build());
    }

    @Test
    @DisplayName("목록: 회원별로 묶고 세션 수·현재 세션 표시를 채우며, 원문 세션 ID는 어디에도 없다")
    void getSessions_groupsByMemberAndMarksCurrent() throws Exception {
        registry.registerNewSession("admin-session-A", principal(1L, Role.ROLE_ADMIN));
        Thread.sleep(15);
        registry.registerNewSession("admin-session-B", principal(1L, Role.ROLE_ADMIN));   // 같은 회원, 다른 principal
        Thread.sleep(15);
        registry.registerNewSession("manager-session", principal(2L, Role.ROLE_MANAGER));

        SessionListResponse response = service.getSessions("admin-session-A");

        assertThat(response.totalSessions()).isEqualTo(3);
        assertThat(response.members()).extracting(SessionMemberResponse::memberId).containsExactly(2L, 1L);   // 최근 요청 순
        SessionMemberResponse admin = response.members().get(1);
        assertThat(admin.sessionCount()).isEqualTo(2);
        assertThat(admin.role()).isEqualTo("ROLE_ADMIN");
        assertThat(admin.sessions()).filteredOn(s -> s.current()).hasSize(1)
                .allMatch(s -> s.handle().equals(AdminSessionService.handleOf("admin-session-A")));
        assertThat(response.members().get(0).sessions()).noneMatch(s -> s.current());
        assertThat(response.toString()).doesNotContain("admin-session-A", "admin-session-B", "manager-session");
    }

    @Test
    @DisplayName("목록: 마지막 요청 시각은 주입된 Clock의 시간대(KST)로 변환된다 / 세션이 없으면 빈 목록")
    void getSessions_usesClockZoneAndEmptyCase() {
        assertThat(service.getSessions(null).members()).isEmpty();
        assertThat(service.getSessions(null).totalSessions()).isZero();

        registry.registerNewSession("s1", principal(1L, Role.ROLE_ADMIN));
        LocalDateTime lastRequestAt = service.getSessions(null).members().get(0).lastRequestAt();

        // 변환 시간대가 KST인지: 같은 Instant를 KST로 바꾼 값과 일치한다(시스템 기본 시간대에 의존하지 않는다)
        Instant instant = registry.getSessionInformation("s1").getLastRequest().toInstant();
        assertThat(lastRequestAt).isEqualTo(LocalDateTime.ofInstant(instant, KST));
    }

    @Test
    @DisplayName("단일 만료: 성공하면 소유 회원 ID와 1건을 돌려주고 그 세션만 만료된다")
    void expireSession_success() {
        registry.registerNewSession("target", principal(2L, Role.ROLE_MANAGER));
        registry.registerNewSession("other", principal(2L, Role.ROLE_MANAGER));
        registry.registerNewSession("caller", principal(1L, Role.ROLE_ADMIN));

        SessionExpireResult result = service.expireSession(AdminSessionService.handleOf("target"), "caller");

        assertThat(result.getMemberId()).isEqualTo(2L);
        assertThat(result.getExpiredCount()).isEqualTo(1);
        assertThat(result.getAuditLabel()).isEqualTo("세션 1개 만료");
        assertThat(registry.getSessionInformation("target").isExpired()).isTrue();
        assertThat(registry.getSessionInformation("other").isExpired()).isFalse();
        assertThat(registry.getSessionInformation("caller").isExpired()).isFalse();
    }

    @Test
    @DisplayName("단일 만료: 현재 세션이면 409이고 세션은 유지된다(같은 회원의 세션이라도)")
    void expireSession_currentSession_conflictAndKept() {
        registry.registerNewSession("caller", principal(1L, Role.ROLE_ADMIN));

        assertThatThrownBy(() -> service.expireSession(AdminSessionService.handleOf("caller"), "caller"))
                .isInstanceOf(ConflictException.class)
                .hasMessageNotContaining("caller");
        assertThat(registry.getSessionInformation("caller").isExpired()).isFalse();
    }

    @Test
    @DisplayName("단일 만료: 활성 세션이 없으면(없는 핸들·이미 만료·소멸) 404 — 메시지에 입력값을 싣지 않는다")
    void expireSession_notFound() {
        registry.registerNewSession("expired", principal(2L, Role.ROLE_MANAGER));
        registry.getSessionInformation("expired").expireNow();
        registry.registerNewSession("gone", principal(2L, Role.ROLE_MANAGER));
        registry.removeSessionInformation("gone");

        for (String id : new String[]{"never-existed", "expired", "gone"}) {
            String handle = AdminSessionService.handleOf(id);
            assertThatThrownBy(() -> service.expireSession(handle, "caller"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageNotContaining(handle);
        }
    }

    @Test
    @DisplayName("단일 만료: 현재 세션 ID를 모르면(null) 현재 세션 거부 없이 대상만 만료한다")
    void expireSession_withoutCurrentSessionId() {
        registry.registerNewSession("target", principal(2L, Role.ROLE_MANAGER));

        assertThat(service.expireSession(AdminSessionService.handleOf("target"), null).getMemberId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("회원 단위 만료: 본인 대상이면 현재 세션만 남기고 라벨에 (현재 세션 제외)가 붙는다")
    void expireMemberSessions_self_keepsCurrent() {
        registry.registerNewSession("current", principal(1L, Role.ROLE_ADMIN));
        registry.registerNewSession("old-1", principal(1L, Role.ROLE_ADMIN));
        registry.registerNewSession("old-2", principal(1L, Role.ROLE_ADMIN));

        SessionExpireResult result = service.expireMemberSessions(1L, "current");

        assertThat(result.getExpiredCount()).isEqualTo(2);
        assertThat(result.getAuditLabel()).isEqualTo("세션 2개 만료(현재 세션 제외)");
        assertThat(registry.getSessionInformation("current").isExpired()).isFalse();
        assertThat(registry.getSessionInformation("old-1").isExpired()).isTrue();
        assertThat(registry.getSessionInformation("old-2").isExpired()).isTrue();
    }

    @Test
    @DisplayName("회원 단위 만료: 다른 회원 대상이면 전부 만료하고 라벨에 제외 표시가 없다 / 대상이 없으면 0건 정상")
    void expireMemberSessions_otherMemberAndNone() {
        registry.registerNewSession("caller", principal(1L, Role.ROLE_ADMIN));
        registry.registerNewSession("t1", principal(2L, Role.ROLE_MANAGER));
        registry.registerNewSession("t2", principal(2L, Role.ROLE_MANAGER));

        SessionExpireResult other = service.expireMemberSessions(2L, "caller");
        assertThat(other.getExpiredCount()).isEqualTo(2);
        assertThat(other.getMemberId()).isEqualTo(2L);
        assertThat(other.getAuditLabel()).isEqualTo("세션 2개 만료");
        assertThat(registry.getSessionInformation("caller").isExpired()).isFalse();

        SessionExpireResult none = service.expireMemberSessions(77L, "caller");
        assertThat(none.getExpiredCount()).isZero();
        assertThat(none.getMemberId()).isEqualTo(77L);
        assertThat(none.getAuditLabel()).isEqualTo("세션 0개 만료");
    }

    @Test
    @DisplayName("감사 라벨에는 세션 ID·핸들이 들어가지 않는다")
    void auditLabel_neverContainsSessionIdentifiers() {
        registry.registerNewSession("secret-session-id", principal(2L, Role.ROLE_MANAGER));
        String handle = AdminSessionService.handleOf("secret-session-id");

        SessionExpireResult result = service.expireSession(handle, "caller");

        assertThat(result.getAuditLabel()).doesNotContain("secret-session-id").doesNotContain(handle);
    }
}
