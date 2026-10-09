package com.cms.admin.permission;

import com.cms.admin.member.domain.Role;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 판정 함수의 진리표(PLAN-menu-permission-management.md §1, 판정 키는 회원 ID — PLAN-member-permission.md §5-B). */
class AdminPermissionEvaluatorTest {

    private static final long MEMBER_ID = 1L;

    private static PermissionSnapshot grants(PermissionAction... actions) {
        Set<PermissionSnapshot.BoardGrant> set = new java.util.HashSet<>();
        for (PermissionAction action : actions) {
            set.add(new PermissionSnapshot.BoardGrant(MEMBER_ID, 3L, action));
        }
        return new PermissionSnapshot(Set.of(), set);
    }

    private static AdminPermissionEvaluator evaluatorWith(PermissionSnapshot snapshot) {
        PermissionCache cache = mock(PermissionCache.class);
        when(cache.snapshot()).thenReturn(snapshot);
        return new AdminPermissionEvaluator(cache);
    }

    /** 실제 로그인과 같은 주체({@code CustomUserDetails}, 회원 ID = {@link #MEMBER_ID}). */
    private static Authentication user(String role) {
        return userWithId(MEMBER_ID, role);
    }

    private static Authentication userWithId(long id, String role) {
        com.cms.config.auth.CustomUserDetails details = TestMembers.detached(id, Role.valueOf(role));
        return new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
    }

    @Test
    @DisplayName("익명·null 인증은 모든 기능에서 거부")
    void anonymousAndNullAreDenied() {
        AdminPermissionEvaluator evaluator = evaluatorWith(grants(PermissionAction.values()));
        Authentication anonymous = new AnonymousAuthentication();

        for (AdminFeature feature : AdminFeature.values()) {
            assertThat(evaluator.allows(null, feature, PermissionAction.READ)).as(feature.name()).isFalse();
            assertThat(evaluator.allows(anonymous, feature, PermissionAction.READ)).as(feature.name()).isFalse();
        }
    }

    private static class AnonymousAuthentication extends AnonymousAuthenticationToken {
        AnonymousAuthentication() {
            super("key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        }
    }

    @Test
    @DisplayName("ADMIN은 모든 기능·동작을 허용받고 DB(캐시)를 조회하지 않는다")
    void adminIsAlwaysAllowedWithoutReadingCache() {
        PermissionCache cache = mock(PermissionCache.class);
        AdminPermissionEvaluator evaluator = new AdminPermissionEvaluator(cache);

        for (AdminFeature feature : AdminFeature.values()) {
            for (PermissionAction action : PermissionAction.values()) {
                assertThat(evaluator.allows(user("ROLE_ADMIN"), feature, action)).as(feature + ":" + action).isTrue();
            }
        }
        verify(cache, never()).snapshot();
    }

    @Test
    @DisplayName("상시 허용 기능(대시보드·내 정보)은 MANAGER도 허용(지원 동작만), 캐시 조회 없음")
    void alwaysFeaturesAllowManagerWithoutCache() {
        PermissionCache cache = mock(PermissionCache.class);
        AdminPermissionEvaluator evaluator = new AdminPermissionEvaluator(cache);

        assertThat(evaluator.allows(user("ROLE_MANAGER"), AdminFeature.DASHBOARD, PermissionAction.READ)).isTrue();
        assertThat(evaluator.allows(user("ROLE_MANAGER"), AdminFeature.MY_INFO, PermissionAction.READ)).isTrue();
        assertThat(evaluator.allows(user("ROLE_MANAGER"), AdminFeature.MY_INFO, PermissionAction.UPDATE)).isTrue();
        assertThat(evaluator.allows(user("ROLE_MANAGER"), AdminFeature.MY_INFO, PermissionAction.DELETE))
                .as("지원하지 않는 동작").isFalse();
        verify(cache, never()).snapshot();
    }

    @Test
    @DisplayName("위임 불가 기능은 MANAGER에게 어떤 허용 행이 있어도 거부한다")
    void adminOnlyFeaturesDeniedEvenIfRowsExist() {
        Set<PermissionSnapshot.Grant> rogue = new java.util.HashSet<>();
        for (AdminFeature feature : AdminFeature.ofKind(FeatureKind.ADMIN_ONLY)) {
            for (PermissionAction action : PermissionAction.values()) {
                rogue.add(new PermissionSnapshot.Grant(MEMBER_ID, feature, action));
            }
        }
        AdminPermissionEvaluator evaluator = evaluatorWith(new PermissionSnapshot(rogue));

        for (AdminFeature feature : AdminFeature.ofKind(FeatureKind.ADMIN_ONLY)) {
            for (PermissionAction action : PermissionAction.values()) {
                assertThat(evaluator.allows(user("ROLE_MANAGER"), feature, action)).as(feature + ":" + action).isFalse();
            }
        }
    }

    @Test
    @DisplayName("위임 가능 기능: MANAGER는 허용 행이 있는 동작만 허용(쓰기는 READ 필요)")
    void delegableFollowsGrantsAndReadDependency() {
        AdminPermissionEvaluator all = evaluatorWith(grants(PermissionAction.values()));
        for (PermissionAction action : PermissionAction.values()) {
            assertThat(all.allows(user("ROLE_MANAGER"), AdminFeature.BOARD, action)).as(action.name()).isTrue();
        }

        AdminPermissionEvaluator none = evaluatorWith(grants());
        for (PermissionAction action : PermissionAction.values()) {
            assertThat(none.allows(user("ROLE_MANAGER"), AdminFeature.BOARD, action)).as(action.name()).isFalse();
        }

        AdminPermissionEvaluator readOnly = evaluatorWith(grants(PermissionAction.READ));
        assertThat(readOnly.allows(user("ROLE_MANAGER"), AdminFeature.BOARD, PermissionAction.READ)).isTrue();
        assertThat(readOnly.allows(user("ROLE_MANAGER"), AdminFeature.BOARD, PermissionAction.CREATE)).isFalse();

        // 의존 규칙: READ 없이 DELETE 행만 있으면 DELETE도 거부
        AdminPermissionEvaluator deleteWithoutRead = evaluatorWith(grants(PermissionAction.DELETE));
        assertThat(deleteWithoutRead.allows(user("ROLE_MANAGER"), AdminFeature.BOARD, PermissionAction.DELETE)).isFalse();
    }

    @Test
    @DisplayName("ROLE_USER 등 그 밖의 역할은 위임 가능 기능도 거부")
    void otherRolesDenied() {
        AdminPermissionEvaluator evaluator = evaluatorWith(grants(PermissionAction.values()));

        assertThat(evaluator.allows(user("ROLE_USER"), AdminFeature.BOARD, PermissionAction.READ)).isFalse();
        assertThat(evaluator.allows(user("ROLE_USER"), AdminFeature.DASHBOARD, PermissionAction.READ)).isFalse();
    }

    @Test
    @DisplayName("미리 받은 스냅샷으로 판정하면 캐시를 다시 조회하지 않는다(요청 안 일관성)")
    void snapshotOverloadDoesNotTouchCache() {
        PermissionCache cache = mock(PermissionCache.class);
        AdminPermissionEvaluator evaluator = new AdminPermissionEvaluator(cache);

        boolean allowed = evaluator.allows(grants(PermissionAction.READ), user("ROLE_MANAGER"),
                AdminFeature.BOARD, PermissionAction.READ);

        assertThat(allowed).isTrue();
        verify(cache, never()).snapshot();
    }

    @Test
    @DisplayName("check(): 알 수 없는 기능·동작 이름은 거부(fail-closed), 정상 이름은 SecurityContext의 인증으로 판정")
    void checkParsesNamesFailClosed() {
        AdminPermissionEvaluator evaluator = evaluatorWith(grants(PermissionAction.values()));
        SecurityContextHolder.setContext(new SecurityContextImpl(user("ROLE_MANAGER")));
        try {
            assertThat(evaluator.check("BOARD", "READ")).isTrue();
            assertThat(evaluator.check("BOARD", "READS")).isFalse();
            assertThat(evaluator.check("BOARDS", "READ")).isFalse();
            assertThat(evaluator.check(null, "READ")).isFalse();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ── 메뉴 URL 가시성(사이드바) ──────────────────────────────

    @Test
    @DisplayName("메뉴 가시성: ADMIN은 URL이 null·미분류여도 항상 보이고 스냅샷을 쓰지 않는다")
    void menuVisibility_adminSeesEverything() {
        AdminPermissionEvaluator evaluator = evaluatorWith(PermissionSnapshot.EMPTY);

        var visible = evaluator.menuUrlVisibility(() -> PermissionSnapshot.EMPTY, user("ROLE_ADMIN"));

        assertThat(visible.test(null)).isTrue();
        assertThat(visible.test("/외부")).isTrue();
        assertThat(visible.test("/admin/menu/manage")).isTrue();
    }

    @Test
    @DisplayName("메뉴 가시성: MANAGER는 상시 기능과 READ 허용 기능만, 위임 불가·미분류·null·쿼리스트링 URL은 숨김")
    void menuVisibility_managerFollowsCatalogAndSnapshot() {
        AdminPermissionEvaluator evaluator = evaluatorWith(PermissionSnapshot.EMPTY);

        var withRead = evaluator.menuUrlVisibility(() -> grants(PermissionAction.READ), user("ROLE_MANAGER"));
        assertThat(withRead.test("/admin")).isTrue();
        assertThat(withRead.test("/admin/member/info")).isTrue();
        assertThat(withRead.test("/admin/board/posts")).isTrue();
        assertThat(withRead.test("/admin/menu/manage")).isFalse();
        assertThat(withRead.test("/admin/member/manage")).isFalse();
        assertThat(withRead.test("/admin/permission/manage")).isFalse();
        assertThat(withRead.test("/admin/board/posts?x=1")).isFalse();
        assertThat(withRead.test("/admin/board/posts/")).isFalse();
        assertThat(withRead.test(null)).isFalse();

        var withoutRead = evaluator.menuUrlVisibility(() -> grants(PermissionAction.CREATE), user("ROLE_MANAGER"));
        assertThat(withoutRead.test("/admin/board/posts")).as("READ 없는 쓰기 권한은 의존 규칙상 무효").isFalse();
        assertThat(withoutRead.test("/admin")).isTrue();
    }

    @Test
    @DisplayName("메뉴 가시성: 익명·USER는 아무것도 보지 못한다")
    void menuVisibility_nonAdminRolesSeeNothing() {
        AdminPermissionEvaluator evaluator = evaluatorWith(PermissionSnapshot.EMPTY);

        assertThat(evaluator.menuUrlVisibility(() -> grants(PermissionAction.READ), user("ROLE_USER")).test("/admin")).isFalse();
        assertThat(evaluator.menuUrlVisibility(() -> grants(PermissionAction.READ), null).test("/admin")).isFalse();
    }

    @Test
    @DisplayName("메뉴 가시성: ADMIN·USER·익명 경로는 스냅샷 공급자(캐시)를 호출하지 않고, MANAGER는 한 번만 호출한다")
    void menuVisibility_snapshotSupplierCalledOnlyForManager() {
        AdminPermissionEvaluator evaluator = evaluatorWith(PermissionSnapshot.EMPTY);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<PermissionSnapshot> supplier = () -> {
            calls.incrementAndGet();
            return PermissionSnapshot.EMPTY;
        };

        evaluator.menuUrlVisibility(supplier, user("ROLE_ADMIN")).test("/admin/menu/manage");
        evaluator.menuUrlVisibility(supplier, user("ROLE_USER")).test("/admin");
        evaluator.menuUrlVisibility(supplier, null).test("/admin");
        assertThat(calls.get()).isZero();

        var manager = evaluator.menuUrlVisibility(supplier, user("ROLE_MANAGER"));
        manager.test("/admin");
        manager.test("/admin/board/posts");
        assertThat(calls.get()).isEqualTo(1);
    }

    // ── 화면 버튼용 동작 키(myPermissions) ─────────────────────

    @Test
    @DisplayName("grantedActionKeys: 현재 카탈로그에 기능 단위 위임(DELEGABLE) 기능이 없어 모든 주체에게 빈 집합이고 공급자(캐시)도 호출하지 않는다 — 공지가 게시판 권한으로 흡수됨(PLAN-notice-to-board.md 쟁점 6)")
    void grantedActionKeys_emptyWhileNoDelegableFeature() {
        AdminPermissionEvaluator evaluator = evaluatorWith(PermissionSnapshot.EMPTY);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<PermissionSnapshot> counting = () -> {
            calls.incrementAndGet();
            return grants(PermissionAction.values());
        };

        assertThat(evaluator.grantedActionKeys(counting, user("ROLE_ADMIN"))).isEmpty();
        assertThat(evaluator.grantedActionKeys(counting, user("ROLE_MANAGER"))).isEmpty();
        assertThat(evaluator.grantedActionKeys(counting, user("ROLE_USER"))).isEmpty();
        assertThat(evaluator.grantedActionKeys(counting, null)).isEmpty();
        assertThat(calls.get()).isZero();
        assertThat(AdminFeature.ofKind(FeatureKind.DELEGABLE))
                .as("DELEGABLE 기능이 다시 생기면(②배너 등) 이 시험을 진리표 시험으로 되살린다").isEmpty();
    }


    // ── 사용자별 판정(PLAN-member-permission.md §5-B) ──────────

    @Test
    @DisplayName("교차 회원 격리: 회원 1에게만 준 권한은 회원 2에게 적용되지 않는다(허용·메뉴·버튼 키 모두)")
    void grantsAreIsolatedPerMember() {
        PermissionSnapshot onlyMember1 = grants(PermissionAction.values()); // MEMBER_ID = 1 에게만 허용
        AdminPermissionEvaluator evaluator = evaluatorWith(onlyMember1);
        Authentication member2 = userWithId(2L, "ROLE_MANAGER");

        for (PermissionAction action : PermissionAction.values()) {
            assertThat(evaluator.allows(user("ROLE_MANAGER"), AdminFeature.BOARD, action)).as("회원 1 " + action).isTrue();
            assertThat(evaluator.allows(member2, AdminFeature.BOARD, action)).as("회원 2 " + action).isFalse();
        }
        assertThat(evaluator.menuUrlVisibility(() -> onlyMember1, member2).test("/admin/board/posts")).isFalse();
        assertThat(evaluator.menuUrlVisibility(() -> onlyMember1, member2).test("/admin")).as("상시 허용은 그대로").isTrue();
        assertThat(evaluator.grantedActionKeys(() -> onlyMember1, member2)).isEmpty();
        assertThat(evaluator.grantedActionKeys(() -> onlyMember1, user("ROLE_MANAGER"))).as("기능 단위 위임 기능이 없어 버튼 키는 비어 있다").isEmpty();
    }

    @Test
    @DisplayName("회원을 식별할 수 없는 주체(CustomUserDetails가 아님)는 위임 기능을 거부하고 상시 허용만 통과한다(fail-closed)")
    void principalWithoutMemberIdIsDeniedForDelegableFeatures() {
        PermissionCache cache = mock(PermissionCache.class);
        when(cache.snapshot()).thenReturn(grants(PermissionAction.values()));
        AdminPermissionEvaluator evaluator = new AdminPermissionEvaluator(cache);
        TestingAuthenticationToken mockUser = new TestingAuthenticationToken("u", "p", "ROLE_MANAGER");
        mockUser.setAuthenticated(true);

        for (PermissionAction action : PermissionAction.values()) {
            assertThat(evaluator.allows(mockUser, AdminFeature.BOARD, action)).as(action.name()).isFalse();
        }
        assertThat(evaluator.allows(mockUser, AdminFeature.DASHBOARD, PermissionAction.READ)).isTrue();
        assertThat(evaluator.menuUrlVisibility(() -> grants(PermissionAction.READ), mockUser).test("/admin/board/posts")).isFalse();
        assertThat(evaluator.grantedActionKeys(() -> grants(PermissionAction.READ), mockUser)).isEmpty();
        verify(cache, never()).snapshot();
    }

    @Test
    @DisplayName("anyManagerMenuUrlVisibility: 카탈로그 분류만 본다 — 상시·위임 가능 기능 URL은 true, 위임 불가·미분류·null은 false(캐시 불필요)")
    void anyManagerMenuUrlVisibility_catalogOnly() {
        var visible = new AdminPermissionEvaluator(mock(PermissionCache.class)).anyManagerMenuUrlVisibility();

        assertThat(visible.test("/admin")).isTrue();
        assertThat(visible.test("/admin/member/info")).isTrue();
        assertThat(visible.test("/admin/board/posts")).isTrue();
        assertThat(visible.test("/admin/menu/manage")).isFalse();
        assertThat(visible.test("/admin/permission/manage")).isFalse();
        assertThat(visible.test("/외부")).isFalse();
        assertThat(visible.test(null)).isFalse();
    }
}
