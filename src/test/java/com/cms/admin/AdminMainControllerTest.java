package com.cms.admin;

import com.cms.admin.dashboard.dto.response.DashboardStatsResponse;
import com.cms.admin.dashboard.service.DashboardService;
import com.cms.admin.menu.dto.response.SidebarMenuResponse;
import com.cms.admin.menu.service.MenuService;
import com.cms.config.auth.AdminSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = AdminMainController.class)
@Import({
        AdminMainControllerTest.MockConfig.class
})
class AdminMainControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DashboardService dashboardService;

    @Autowired
    AdminSecurityService adminSecurityService;

    @Autowired
    MenuService menuService;

    @BeforeEach
    void setUp() {
        reset(dashboardService, adminSecurityService, menuService);
        // AdminViewAdvice가 com.cms.admin 전체에 적용되므로 기본 스텁이 필요하다.
        given(adminSecurityService.getCurrentAdminName()).willReturn("관리자");
        given(adminSecurityService.getCurrentAdminProfileImageUrl()).willReturn(null);
    }

    @TestConfiguration
    static class MockConfig {

        @Bean
        public DashboardService dashboardService() {
            return Mockito.mock(DashboardService.class);
        }

        @Bean
        public AdminSecurityService adminSecurityService() {
            return Mockito.mock(AdminSecurityService.class);
        }

        // AdminSidebarAdvice(@ControllerAdvice)가 슬라이스 컨텍스트에 포함되므로 의존 빈이 필요하다.
        @Bean
        public MenuService menuService() {
            return Mockito.mock(MenuService.class);
        }
    }

    @Test
    @DisplayName("대시보드 요청 시 admin/index 뷰를 반환하고 stats 모델이 존재한다")
    @WithMockUser(roles = "ADMIN")
    void main_returnsIndexViewWithStats() throws Exception {
        DashboardStatsResponse stats = DashboardStatsResponse.builder()
                .newMembersThisMonth(3L)
                .todayVisitors(5L)
                .monthVisitors(20L)
                .totalVisitors(100L)
                .build();
        given(dashboardService.getDashboardStats()).willReturn(stats);

        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/index"))
                .andExpect(model().attributeExists("stats"));
    }

    @Test
    @DisplayName("대시보드 요청 시 통계가 null(조회 불가)이어도 200 OK 반환 — 그레이스풀 다운")
    @WithMockUser(roles = "ADMIN")
    void main_nullStats_returnsOk() throws Exception {
        DashboardStatsResponse nullStats = DashboardStatsResponse.builder().build(); // 모든 필드 null
        given(dashboardService.getDashboardStats()).willReturn(nullStats);

        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/index"))
                .andExpect(model().attributeExists("stats"));
    }

    @Test
    @DisplayName("미인증 사용자는 401 Unauthorized를 받는다(@WebMvcTest 기본 보안; 실제 리다이렉트는 SecurityConfigTest에서 검증)")
    void main_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("사이드바는 AdminSidebarAdvice가 주입한 메뉴 데이터로 렌더링된다")
    @WithMockUser(roles = "ADMIN")
    void main_rendersSidebarFromMenuData() throws Exception {
        given(dashboardService.getDashboardStats()).willReturn(DashboardStatsResponse.builder().build());
        given(adminSecurityService.getCurrentAdminId()).willReturn(1L);
        given(adminSecurityService.hasAdminAuthority()).willReturn(true);
        given(menuService.getSidebarMenus(anyBoolean())).willReturn(List.of(
                SidebarMenuResponse.builder()
                        .menuNo(1L).menuName("대시보드").menuUrl("/admin")
                        .menuIcon("fas fa-fw fa-tachometer-alt").children(List.of())
                        .build(),
                SidebarMenuResponse.builder()
                        .menuNo(2L).menuName("회원 관리").menuIcon("fas fa-fw fa-user-shield")
                        .children(List.of(SidebarMenuResponse.builder()
                                .menuNo(3L).menuName("내 정보").menuUrl("/admin/member/info").children(List.of())
                                .build()))
                        .build()))
                ;

        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("sidebarMenus"))
                .andExpect(model().attribute("currentUri", "/admin"))
                .andExpect(content().string(containsString("대시보드")))
                .andExpect(content().string(containsString("내 정보")))
                .andExpect(content().string(containsString("collapseMenu2")));
    }

    @Test
    @DisplayName("사이드바 메뉴 조회는 인증 정보가 없으면(DB 조회 생략) 빈 목록으로 렌더링된다")
    @WithMockUser(roles = "ADMIN")
    void main_sidebarEmptyWhenNoCurrentAdmin() throws Exception {
        given(dashboardService.getDashboardStats()).willReturn(DashboardStatsResponse.builder().build());
        given(adminSecurityService.getCurrentAdminId()).willReturn(null);

        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("sidebarMenus", List.of()));
    }
}
