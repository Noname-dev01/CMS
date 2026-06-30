package com.cms.config;

import com.cms.admin.visit.repository.VisitLogRepository;
import com.cms.config.auth.AdminSecurityService;
import com.cms.config.auth.VisitLoggingAuthenticationSuccessHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {OpenApiDocsTestController.class, AdminDashboardStubController.class, AdminMemberInfoStubController.class, AdminMembersApiStubController.class, AdminMemberManageStubController.class})
@Import({
        SecurityConfig.class,
        SecurityConfigTest.MockConfig.class
})
class SecurityConfigTest {

    @Autowired
    MockMvc mockMvc;

    @TestConfiguration
    static class MockConfig {

        @Bean
        public AdminSecurityService adminSecurityService() {
            return Mockito.mock(AdminSecurityService.class);
        }

        /**
         * filterChain 시그니처에 핸들러 파라미터가 추가됐으므로 mock 빈이 없으면 컨텍스트 로딩 실패.
         */
        @Bean
        public VisitLoggingAuthenticationSuccessHandler visitLoggingAuthenticationSuccessHandler() {
            VisitLogRepository mockRepo = Mockito.mock(VisitLogRepository.class);
            return new VisitLoggingAuthenticationSuccessHandler(mockRepo);
        }
    }

    // ==================== 기존 OpenAPI 테스트 ====================

    @Test
    @DisplayName("OpenAPI docs require authentication")
    void openApiDocs_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/admin/login"));
    }

    @Test
    @DisplayName("OpenAPI docs reject non-admin users")
    @WithMockUser(roles = "USER")
    void openApiDocs_userRole_forbidden() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("OpenAPI docs allow admin users")
    @WithMockUser(roles = "ADMIN")
    void openApiDocs_adminRole_ok() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    // ==================== MANAGER 인가 범위 검증 ====================

    @Test
    @DisplayName("MANAGER는 대시보드(/admin) 접근이 가능하다")
    @WithMockUser(roles = "MANAGER")
    void manager_dashboard_ok() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("MANAGER는 내 정보 페이지(/admin/member/info) 접근이 가능하다")
    @WithMockUser(roles = "MANAGER")
    void manager_memberInfo_ok() throws Exception {
        mockMvc.perform(get("/admin/member/info"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("MANAGER는 self API(/admin/api/members/me)에 접근이 가능하다")
    @WithMockUser(roles = "MANAGER")
    void manager_selfApi_ok() throws Exception {
        mockMvc.perform(get("/admin/api/members/me"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("MANAGER는 관리자 목록 API(/admin/api/members)에 접근할 수 없다(403)")
    @WithMockUser(roles = "MANAGER")
    void manager_membersListApi_forbidden() throws Exception {
        mockMvc.perform(get("/admin/api/members")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("MANAGER는 관리자 관리 페이지(/admin/member/manage)에 접근할 수 없다(403)")
    @WithMockUser(roles = "MANAGER")
    void manager_memberManagePage_forbidden() throws Exception {
        mockMvc.perform(get("/admin/member/manage"))
                .andExpect(status().isForbidden());
    }
}

// ==================== 슬라이스 테스트용 더미 컨트롤러 ====================
// @SpringBootTest 전체 스캔 충돌 방지: CmsApplicationTests.TestBootConfig의 excludeFilters에 이 클래스들을 등록한다.

@RestController
class OpenApiDocsTestController {

    @GetMapping("/v3/api-docs")
    String openApiDocs() {
        return "{}";
    }
}

@RestController
class AdminDashboardStubController {

    @GetMapping("/admin")
    String dashboard() {
        return "dashboard";
    }
}

@RestController
class AdminMemberInfoStubController {

    @GetMapping("/admin/member/info")
    String memberInfo() {
        return "info";
    }
}

@RestController
class AdminMembersApiStubController {

    @GetMapping("/admin/api/members/me")
    String membersMe() {
        return "{}";
    }

    @GetMapping("/admin/api/members")
    String membersList() {
        return "[]";
    }
}

@RestController
class AdminMemberManageStubController {

    @GetMapping("/admin/member/manage")
    String memberManage() {
        return "manage";
    }
}
