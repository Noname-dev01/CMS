package com.cms.admin;

import com.cms.admin.menu.service.MenuService;
import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.AdminPermissionEvaluator;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.PermissionSnapshot;
import com.cms.admin.permission.RolePermissionCache;
import com.cms.config.auth.AdminSecurityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminSidebarAdvice}가 {@code sidebarMenus}와 {@code myPermissions}를 <b>같은 권한 스냅샷 하나</b>로 계산하는지(실제 판정기 + 목 캐시) 확인한다.
 * 보증 범위는 Advice 내부다 — URL 게이트(SecurityConfig)가 별도로 캐시를 읽으므로 전체 HTTP 요청 기준 1회는 단언하지 않는다.
 */
class AdminSidebarAdviceSnapshotTest {

    private final RolePermissionCache cache = mock(RolePermissionCache.class);
    private final AdminPermissionEvaluator evaluator = new AdminPermissionEvaluator(cache);
    private final MenuService menuService = mock(MenuService.class);
    private final AdminSecurityService securityService = mock(AdminSecurityService.class);
    private final AdminSidebarAdvice advice = new AdminSidebarAdvice(menuService, securityService, evaluator);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void login(String role) {
        TestingAuthenticationToken token = new TestingAuthenticationToken("u", "p", role);
        token.setAuthenticated(true);
        SecurityContextHolder.setContext(new SecurityContextImpl(token));
        when(securityService.getCurrentAdminId()).thenReturn(1L);
    }

    private static PermissionSnapshot noticeRead() {
        return new PermissionSnapshot(Set.of(new PermissionSnapshot.Grant("ROLE_MANAGER", AdminFeature.NOTICE, PermissionAction.READ)));
    }

    @Test
    @DisplayName("MANAGER: 스냅샷을 한 번만 받아 사이드바 판정과 myPermissions가 같은 권한 버전을 쓴다(호출 사이에 캐시가 바뀌어도 일치)")
    void manager_snapshotSharedBetweenSidebarAndPermissions() {
        login("ROLE_MANAGER");
        // 첫 조회는 READ 허용, 이후 조회는 빈 스냅샷 — 두 번 조회하면 두 속성이 서로 다른 버전을 보게 된다
        when(cache.snapshot()).thenReturn(noticeRead(), PermissionSnapshot.EMPTY);
        when(menuService.getSidebarMenus(any())).thenReturn(List.of());
        Model model = new ExtendedModelMap();

        advice.sidebarAndPermissions(model);

        verify(cache, times(1)).snapshot();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Predicate<String>> visible = ArgumentCaptor.forClass(Predicate.class);
        verify(menuService).getSidebarMenus(visible.capture());
        assertThat(visible.getValue().test("/admin/notice/manage")).isTrue();
        assertThat(model.getAttribute("myPermissions")).isEqualTo(Set.of("NOTICE:READ"));
    }

    @Test
    @DisplayName("ADMIN: 캐시를 호출하지 않고 위임 기능 4동작이 모두 myPermissions에 들어간다")
    void admin_neverTouchesCache() {
        login("ROLE_ADMIN");
        when(menuService.getSidebarMenus(any())).thenReturn(List.of());
        Model model = new ExtendedModelMap();

        advice.sidebarAndPermissions(model);

        verify(cache, never()).snapshot();
        assertThat(model.getAttribute("myPermissions"))
                .isEqualTo(Set.of("NOTICE:READ", "NOTICE:CREATE", "NOTICE:UPDATE", "NOTICE:DELETE"));
    }

    @Test
    @DisplayName("미인증 요청: 두 속성 모두 빈 값이고 메뉴 조회·캐시 호출이 없다")
    void unauthenticated_emptyWithoutQueries() {
        when(securityService.getCurrentAdminId()).thenReturn(null);
        Model model = new ExtendedModelMap();

        advice.sidebarAndPermissions(model);

        assertThat(model.getAttribute("sidebarMenus")).isEqualTo(List.of());
        assertThat(model.getAttribute("myPermissions")).isEqualTo(Set.of());
        verify(cache, never()).snapshot();
        verify(menuService, never()).getSidebarMenus(any());
    }
}
