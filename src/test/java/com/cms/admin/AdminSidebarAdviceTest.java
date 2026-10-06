package com.cms.admin;

import com.cms.admin.dashboard.dto.response.DashboardStatsResponse;
import com.cms.admin.dashboard.service.DashboardService;
import com.cms.admin.log.controller.AdminActionLogPageController;
import com.cms.admin.member.controller.AdminMemberPageController;
import com.cms.admin.menu.controller.MenuPageController;
import com.cms.admin.menu.dto.response.SidebarMenuResponse;
import com.cms.admin.menu.service.MenuService;
import com.cms.config.auth.AdminSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminSidebarAdvice가 @AdminPage 페이지 컨트롤러 전체에 sidebarMenus·currentUri를
 * 주입하는지 대표 페이지 4곳으로 검증한다.
 */
@WebMvcTest(controllers = {
        AdminMainController.class,
        AdminMemberPageController.class,
        AdminActionLogPageController.class,
        MenuPageController.class,
        com.cms.admin.permission.controller.PermissionPageController.class
})
@Import(AdminSidebarAdviceTest.MockConfig.class)
class AdminSidebarAdviceTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MenuService menuService;

    @Autowired
    com.cms.admin.permission.AdminPermissionEvaluator adminPermissionEvaluator;

    @Autowired
    AdminSecurityService adminSecurityService;

    @Autowired
    DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        reset(menuService, adminSecurityService, dashboardService, adminPermissionEvaluator);
        given(adminPermissionEvaluator.grantedActionKeys(any(), any())).willReturn(java.util.Set.of("NOTICE:READ"));
        given(adminSecurityService.getCurrentAdminName()).willReturn("관리자");
        given(adminSecurityService.getCurrentAdminProfileImageUrl()).willReturn(null);
        given(adminSecurityService.getCurrentAdminId()).willReturn(1L);
        given(dashboardService.getDashboardStats()).willReturn(DashboardStatsResponse.builder().build());
        given(menuService.getSidebarMenus(any())).willReturn(List.of(
                SidebarMenuResponse.builder()
                        .menuNo(1L).menuName("대시보드").menuUrl("/admin")
                        .children(List.of())
                        .build()));
    }

    @TestConfiguration
    static class MockConfig {
        @Bean
        public com.cms.admin.permission.AdminPermissionEvaluator adminPermissionEvaluator() {
            return Mockito.mock(com.cms.admin.permission.AdminPermissionEvaluator.class);
        }

        @Bean
        public MenuService menuService() {
            return Mockito.mock(MenuService.class);
        }

        @Bean
        public AdminSecurityService adminSecurityService() {
            return Mockito.mock(AdminSecurityService.class);
        }

        @Bean
        public DashboardService dashboardService() {
            return Mockito.mock(DashboardService.class);
        }

        /** AdminActionLogPageController가 Clock으로 기간 기본값을 계산한다 — 슬라이스에는 AppConfig가 없다. */
        @Bean
        public Clock clock() {
            return Clock.systemUTC();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin", "/admin/member/info", "/admin/log/manage", "/admin/menu/manage", "/admin/permission/manage"})
    @DisplayName("모든 @AdminPage 페이지는 sidebarMenus·myPermissions·currentUri 모델을 받는다")
    @WithMockUser(roles = "ADMIN")
    void adminPages_receiveSidebarModel(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("sidebarMenus"))
                .andExpect(model().attributeExists("myPermissions"))
                .andExpect(model().attribute("currentUri", path));
    }
}
