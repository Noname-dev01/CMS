package com.cms.admin;

import com.cms.admin.dashboard.dto.response.DashboardStatsResponse;
import com.cms.admin.dashboard.service.DashboardService;
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

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @BeforeEach
    void setUp() {
        reset(dashboardService, adminSecurityService);
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
}
