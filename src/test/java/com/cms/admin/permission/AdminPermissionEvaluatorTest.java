package com.cms.admin.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
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

/** 판정 함수의 진리표(PLAN-menu-permission-management.md §1). */
class AdminPermissionEvaluatorTest {

    private static PermissionSnapshot grants(PermissionAction... actions) {
        Set<PermissionSnapshot.Grant> set = new java.util.HashSet<>();
        for (PermissionAction action : actions) {
            set.add(new PermissionSnapshot.Grant("ROLE_MANAGER", AdminFeature.NOTICE, action));
        }
        return new PermissionSnapshot(set);
    }

    private static AdminPermissionEvaluator evaluatorWith(PermissionSnapshot snapshot) {
        RolePermissionCache cache = mock(RolePermissionCache.class);
        when(cache.snapshot()).thenReturn(snapshot);
        return new AdminPermissionEvaluator(cache);
    }

    private static Authentication user(String role) {
        TestingAuthenticationToken token = new TestingAuthenticationToken("u", "p", role);
        token.setAuthenticated(true);
        return token;
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
        RolePermissionCache cache = mock(RolePermissionCache.class);
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
        RolePermissionCache cache = mock(RolePermissionCache.class);
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
                rogue.add(new PermissionSnapshot.Grant("ROLE_MANAGER", feature, action));
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
            assertThat(all.allows(user("ROLE_MANAGER"), AdminFeature.NOTICE, action)).as(action.name()).isTrue();
        }

        AdminPermissionEvaluator none = evaluatorWith(grants());
        for (PermissionAction action : PermissionAction.values()) {
            assertThat(none.allows(user("ROLE_MANAGER"), AdminFeature.NOTICE, action)).as(action.name()).isFalse();
        }

        AdminPermissionEvaluator readOnly = evaluatorWith(grants(PermissionAction.READ));
        assertThat(readOnly.allows(user("ROLE_MANAGER"), AdminFeature.NOTICE, PermissionAction.READ)).isTrue();
        assertThat(readOnly.allows(user("ROLE_MANAGER"), AdminFeature.NOTICE, PermissionAction.CREATE)).isFalse();

        // 의존 규칙: READ 없이 DELETE 행만 있으면 DELETE도 거부
        AdminPermissionEvaluator deleteWithoutRead = evaluatorWith(grants(PermissionAction.DELETE));
        assertThat(deleteWithoutRead.allows(user("ROLE_MANAGER"), AdminFeature.NOTICE, PermissionAction.DELETE)).isFalse();
    }

    @Test
    @DisplayName("ROLE_USER 등 그 밖의 역할은 위임 가능 기능도 거부")
    void otherRolesDenied() {
        AdminPermissionEvaluator evaluator = evaluatorWith(grants(PermissionAction.values()));

        assertThat(evaluator.allows(user("ROLE_USER"), AdminFeature.NOTICE, PermissionAction.READ)).isFalse();
        assertThat(evaluator.allows(user("ROLE_USER"), AdminFeature.DASHBOARD, PermissionAction.READ)).isFalse();
    }

    @Test
    @DisplayName("미리 받은 스냅샷으로 판정하면 캐시를 다시 조회하지 않는다(요청 안 일관성)")
    void snapshotOverloadDoesNotTouchCache() {
        RolePermissionCache cache = mock(RolePermissionCache.class);
        AdminPermissionEvaluator evaluator = new AdminPermissionEvaluator(cache);

        boolean allowed = evaluator.allows(grants(PermissionAction.READ), user("ROLE_MANAGER"),
                AdminFeature.NOTICE, PermissionAction.READ);

        assertThat(allowed).isTrue();
        verify(cache, never()).snapshot();
    }

    @Test
    @DisplayName("check(): 알 수 없는 기능·동작 이름은 거부(fail-closed), 정상 이름은 SecurityContext의 인증으로 판정")
    void checkParsesNamesFailClosed() {
        AdminPermissionEvaluator evaluator = evaluatorWith(grants(PermissionAction.values()));
        SecurityContextHolder.setContext(new SecurityContextImpl(user("ROLE_MANAGER")));
        try {
            assertThat(evaluator.check("NOTICE", "READ")).isTrue();
            assertThat(evaluator.check("NOTICE", "READS")).isFalse();
            assertThat(evaluator.check("NOTICES", "READ")).isFalse();
            assertThat(evaluator.check(null, "READ")).isFalse();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
