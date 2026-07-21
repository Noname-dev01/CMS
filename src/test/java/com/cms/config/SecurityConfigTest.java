package com.cms.config;

import com.cms.admin.menu.service.MenuService;
import com.cms.admin.visit.repository.VisitLogRepository;
import com.cms.config.auth.AdminSecurityService;
import com.cms.config.auth.LockingAuthenticationFailureHandler;
import com.cms.config.auth.LoginFailureService;
import com.cms.config.auth.PasswordExpiryService;
import com.cms.config.auth.VisitLoggingAuthenticationSuccessHandler;
import com.cms.support.TestStubController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {OpenApiDocsTestController.class, AdminDashboardStubController.class, AdminMemberInfoStubController.class, AdminMembersApiStubController.class, AdminMemberManageStubController.class, AdminNoticeStubController.class})
@Import({
        SecurityConfig.class,
        SecurityConfigTest.MockConfig.class
})
@ActiveProfiles({"test", "webmvc-test"})
class SecurityConfigTest {

    @Autowired
    MockMvc mockMvc;

    @TestConfiguration
    static class MockConfig {

        @Bean
        public AdminSecurityService adminSecurityService() {
            return Mockito.mock(AdminSecurityService.class);
        }

        // AdminSidebarAdvice(@ControllerAdvice)가 슬라이스 컨텍스트에 포함되므로 의존 빈이 필요하다.
        @Bean
        public MenuService menuService() {
            return Mockito.mock(MenuService.class);
        }

        // SecurityConfig.filterChain의 성공·실패 핸들러 의존 빈 — 이 슬라이스는 formLogin
        // 성공 경로를 타지 않으므로 LoginFailureService는 순수 mock으로 충분하다.
        @Bean
        public LoginFailureService loginFailureService() {
            return Mockito.mock(LoginFailureService.class);
        }

        @Bean
        public VisitLoggingAuthenticationSuccessHandler visitLoggingAuthenticationSuccessHandler(
                LoginFailureService loginFailureService) {
            VisitLogRepository mockRepo = Mockito.mock(VisitLogRepository.class);
            PasswordExpiryService mockExpiry = Mockito.mock(PasswordExpiryService.class);
            return new VisitLoggingAuthenticationSuccessHandler(mockRepo, loginFailureService, mockExpiry,
                    java.time.Clock.systemDefaultZone());
        }

        @Bean
        public LockingAuthenticationFailureHandler lockingAuthenticationFailureHandler(
                LoginFailureService loginFailureService) {
            return new LockingAuthenticationFailureHandler(loginFailureService);
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

    // ==================== 공지사항 인가 범위 검증 (adversarial-review/plan/PLAN-notice-board.md 설계 결정 1) ====================

    @Test
    @DisplayName("MANAGER는 공지사항 관리 페이지(/admin/notice/manage)에 접근이 가능하다")
    @WithMockUser(roles = "MANAGER")
    void manager_noticeManagePage_ok() throws Exception {
        mockMvc.perform(get("/admin/notice/manage"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("MANAGER는 공지사항 목록 API(/admin/api/notices)에 접근이 가능하다")
    @WithMockUser(roles = "MANAGER")
    void manager_noticesApi_ok() throws Exception {
        mockMvc.perform(get("/admin/api/notices"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("USER는 공지사항 관리 페이지에 접근할 수 없다(403)")
    @WithMockUser(roles = "USER")
    void user_noticeManagePage_forbidden() throws Exception {
        mockMvc.perform(get("/admin/notice/manage"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("USER는 공지사항 목록 API에 접근할 수 없다(403)")
    @WithMockUser(roles = "USER")
    void user_noticesApi_forbidden() throws Exception {
        mockMvc.perform(get("/admin/api/notices"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("미인증 사용자의 공지사항 목록 API 호출은 JSON 401")
    void unauthenticated_noticesApi_json401() throws Exception {
        mockMvc.perform(get("/admin/api/notices"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("미인증 사용자의 공지사항 관리 페이지 접근은 로그인 페이지로 리다이렉트")
    void unauthenticated_noticeManagePage_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin/notice/manage"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/admin/login"));
    }

    @Test
    @DisplayName("CSRF 토큰 없이 공지사항 생성 POST 시 403")
    @WithMockUser(roles = "ADMIN")
    void createNotice_missingCsrf_forbidden() throws Exception {
        mockMvc.perform(post("/admin/api/notices").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CSRF 토큰 포함 시 공지사항 생성 POST가 인가를 통과한다(ADMIN)")
    @WithMockUser(roles = "ADMIN")
    void createNotice_withCsrf_passesAuthorization() throws Exception {
        mockMvc.perform(post("/admin/api/notices").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
    }
}

// ==================== 슬라이스 테스트 전용 스텁 컨트롤러 ====================
// @TestStubController: CmsTestApplication이 FilterType.ANNOTATION으로 제외 → full-context 충돌 없음

@TestStubController
class OpenApiDocsTestController {

    @GetMapping("/v3/api-docs")
    String openApiDocs() {
        return "{}";
    }
}

@TestStubController
class AdminDashboardStubController {

    @GetMapping("/admin")
    String dashboard() {
        return "dashboard";
    }
}

@TestStubController
class AdminMemberInfoStubController {

    @GetMapping("/admin/member/info")
    String memberInfo() {
        return "info";
    }
}

@TestStubController
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

@TestStubController
class AdminMemberManageStubController {

    @GetMapping("/admin/member/manage")
    String memberManage() {
        return "manage";
    }
}

@TestStubController
class AdminNoticeStubController {

    @GetMapping("/admin/notice/manage")
    String noticeManage() {
        return "notice-manage";
    }

    @GetMapping("/admin/api/notices")
    String noticesList() {
        return "[]";
    }

    @PostMapping("/admin/api/notices")
    ResponseEntity<String> noticesCreate() {
        return ResponseEntity.status(201).body("{}");
    }
}
