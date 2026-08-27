package com.cms.config;

import com.cms.admin.menu.service.MenuService;
import com.cms.admin.visit.repository.VisitLogRepository;
import com.cms.config.auth.AdminSecurityService;
import com.cms.config.auth.LockingAuthenticationFailureHandler;
import com.cms.config.auth.LoginFailureService;
import com.cms.config.auth.PasswordExpiryService;
import com.cms.config.auth.VisitLoggingAuthenticationSuccessHandler;
import com.cms.config.ratelimit.RateLimitFilterConfig;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {OpenApiDocsTestController.class, AdminDashboardStubController.class, AdminMemberInfoStubController.class, AdminMembersApiStubController.class, AdminMemberManageStubController.class, AdminNoticeStubController.class, PublicNoticeStubController.class, ActuatorHealthStubController.class, ActuatorEnvStubController.class})
@Import({
        SecurityConfig.class,
        RateLimitFilterConfig.class,
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

    // ==================== 공지사항 인가 범위 검증 ====================

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

    // ==================== 공개 공지 페이지 인가 범위 검증 (2026-07-28 승인) ====================

    @Test
    @DisplayName("비인증 GET /notices는 200 (permitAll, 로그인 리다이렉트 아님)")
    void publicNotices_unauthenticatedGet_ok() throws Exception {
        mockMvc.perform(get("/notices"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("비인증 HEAD /notices는 200 — v3에서 추가한 HEAD 허용 규칙의 회귀 테스트")
    void publicNotices_unauthenticatedHead_ok() throws Exception {
        mockMvc.perform(head("/notices"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CSRF 없는 비인증 POST /notices는 CsrfFilter가 먼저 차단해 403")
    void publicNotices_unauthenticatedPost_missingCsrf_forbidden() throws Exception {
        mockMvc.perform(post("/notices"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CSRF 포함 비인증 POST /notices는 denyAll+익명 판별로 /admin/login 302 리다이렉트 (405/401이 아님)")
    void publicNotices_unauthenticatedPost_withCsrf_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/notices").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/admin/login"));
    }

    @Test
    @DisplayName("CSRF 포함 ADMIN POST /notices는 denyAll+인증 사용자 판별로 정확히 403 (denyAll이 역할 불문임을 확인)")
    @WithMockUser(roles = "ADMIN")
    void publicNotices_adminPost_withCsrf_forbidden() throws Exception {
        mockMvc.perform(post("/notices").with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ==================== 공개 첨부 다운로드 인가 범위 검증 (2026-08-03 추가) ====================
    // /notices/**가 하위 세그먼트 전체를 포괄하므로, 이 라우트는 코드 수정 없이 이미 무인증
    // 공개다 — "라우트 추가만으로 무인증 공개된다"는 암묵적 동작을 명시적으로 고정한다.

    @Test
    @DisplayName("비인증 GET /notices/{id}/attachments/{attachmentId}는 200 (permitAll)")
    void publicNoticeAttachment_unauthenticatedGet_ok() throws Exception {
        mockMvc.perform(get("/notices/1/attachments/1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("비인증 HEAD /notices/{id}/attachments/{attachmentId}는 200 (permitAll)")
    void publicNoticeAttachment_unauthenticatedHead_ok() throws Exception {
        mockMvc.perform(head("/notices/1/attachments/1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CSRF 포함 비인증 POST /notices/{id}/attachments/{attachmentId}는 denyAll+익명 판별로 /admin/login 302 리다이렉트")
    void publicNoticeAttachment_unauthenticatedPost_withCsrf_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/notices/1/attachments/1").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/admin/login"));
    }

    // ==================== actuator 인가 범위 검증 (PLAN-prod-profile.md 결정 3, 2026-07-29 승인) ====================

    @Test
    @DisplayName("비인증 GET /actuator/health는 200 (permitAll, 무인증 공개 유지)")
    void actuatorHealth_unauthenticatedGet_ok() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("비인증 GET /actuator/env는 denyAll+익명 판별로 /admin/login 302 리다이렉트 (JSON 아님)")
    void actuatorEnv_unauthenticatedGet_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/admin/login"));
    }

    @Test
    @DisplayName("ADMIN GET /actuator/env는 denyAll+인증 사용자 판별로 정확히 403 (역할 불문 차단)")
    @WithMockUser(roles = "ADMIN")
    void actuatorEnv_adminGet_forbidden() throws Exception {
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isForbidden());
    }

    // ==================== 핸들러 없는 경로의 404 응답 검증 (PLAN-not-found-handling.md) ====================
    // /admin/api/**는 JSON 404, 그 외는 sendError(404)+null(컨테이너 ERROR 디스패치 트리거).
    // MockMvc는 컨테이너 ERROR 디스패치를 수행하지 않으므로 여기서는 상태코드·본문(JSON인 경우)까지만
    // 단언하고, 실제 HTML 렌더링·ERROR 재디스패치 이후의 필터 재평가는 Playwright로 검증한다.

    @Test
    @DisplayName("ADMIN의 GET /admin/api/존재하지않는경로는 JSON 404 RESOURCE_NOT_FOUND")
    @WithMockUser(roles = "ADMIN")
    void notFound_adminApiUnmappedPath_json404() throws Exception {
        mockMvc.perform(get("/admin/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Accept: text/html이어도 /admin/api/** 미매핑 경로는 여전히 JSON 404 (콘텐츠 협상 실패로 다른 응답이 되지 않음)")
    @WithMockUser(roles = "ADMIN")
    void notFound_adminApiUnmappedPath_acceptTextHtml_stillJson404() throws Exception {
        mockMvc.perform(get("/admin/api/does-not-exist").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Accept: application/xml이어도 /admin/api/** 미매핑 경로는 여전히 JSON 404")
    @WithMockUser(roles = "ADMIN")
    void notFound_adminApiUnmappedPath_acceptXml_stillJson404() throws Exception {
        mockMvc.perform(get("/admin/api/does-not-exist").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("ADMIN의 GET /admin/존재하지않는경로는 404 (상태코드만 — HTML 렌더링은 Playwright로 검증)")
    @WithMockUser(roles = "ADMIN")
    void notFound_adminPageUnmappedPath_404() throws Exception {
        mockMvc.perform(get("/admin/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("비인증 GET /존재하지않는경로(공개)는 404")
    void notFound_publicUnmappedPath_404() throws Exception {
        mockMvc.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("비인증 GET /admin/api/존재하지않는경로는 기존대로 JSON 401 (Security가 DispatcherServlet 도달 전에 차단 — 무회귀)")
    void notFound_unauthenticatedAdminApiUnmappedPath_json401() throws Exception {
        mockMvc.perform(get("/admin/api/does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("CSRF 포함 비인증 POST /존재하지않는경로는 404 (MVC 예외 분기까지 도달 — 이 슬라이스는 실제 CSRF 필터를 통과함, " +
            "단 ERROR 재디스패치 자체는 MockMvc 밖이라 재디스패치에서 403으로 안 뒤집힘까지는 이 테스트로 증명되지 않음 — 종단 확인은 Playwright)")
    void notFound_unauthenticatedPostUnmappedPath_withCsrf_404() throws Exception {
        mockMvc.perform(post("/does-not-exist").with(csrf()))
                .andExpect(status().isNotFound());
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

@TestStubController
class ActuatorHealthStubController {

    @GetMapping("/actuator/health")
    String health() {
        return "{\"status\":\"UP\"}";
    }
}

@TestStubController
class ActuatorEnvStubController {

    @GetMapping("/actuator/env")
    String env() {
        // denyAll이 이 매핑 자체에 도달하지 못하게 막는지 검증하는 스텁 —
        // 실제 /actuator/env 핸들러가 없어도(exposure=health only) denyAll 규칙은
        // 별도로 실제 등록 여부를 신경 쓰지 않고 경로 자체를 막아야 한다.
        return "{}";
    }
}

@TestStubController
class PublicNoticeStubController {

    @GetMapping("/notices")
    String list() {
        return "public-notices-list";
    }

    @PostMapping("/notices")
    ResponseEntity<String> create() {
        // denyAll이 이 매핑 자체에 도달하지 못하게 막는지 검증하는 스텁 — 실제로는 존재하지 않는 엔드포인트.
        return ResponseEntity.ok("{}");
    }

    @GetMapping("/notices/{id}/attachments/{attachmentId}")
    String attachment() {
        return "public-notice-attachment";
    }

    @PostMapping("/notices/{id}/attachments/{attachmentId}")
    ResponseEntity<String> attachmentCreate() {
        // denyAll이 이 매핑 자체에 도달하지 못하게 막는지 검증하는 스텁 — 실제로는 존재하지 않는 엔드포인트.
        return ResponseEntity.ok("{}");
    }
}
