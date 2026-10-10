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
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {OpenApiDocsTestController.class, AdminDashboardStubController.class, AdminMemberInfoStubController.class, AdminMessagePageStubController.class, AdminSearchApiStubController.class, AdminMembersApiStubController.class, AdminMemberManageStubController.class, AdminNoticeStubController.class, PublicNoticeStubController.class, PublicBoardStubController.class, HomeStubController.class, PublicBannerStubController.class, AdminBannerStubController.class, ActuatorHealthStubController.class, ActuatorEnvStubController.class})
@Import({
        SecurityConfig.class,
        PermissionTestConfig.class,
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
                    org.mockito.Mockito.mock(com.cms.admin.notification.service.NotificationRecorder.class),
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
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    @DisplayName("미인증 페이지 요청의 로그인 리다이렉트 Location 정확값을 고정한다")
    void unauthenticated_page_loginRedirectLocation() throws Exception {
        // 다른 테스트의 "**/admin/login" 패턴은 절대·상대 URI를 모두 통과시키므로 형식 변화를 감지하지 못한다.
        // Security 7부터 sendRedirect에 상대 URI를 넘긴다(Boot 3.5까지는 http://localhost/admin/login) — MockMvc는
        // 이 값을 그대로 보여 준다. 실제 Tomcat 응답은 절대 URL로 나감을 실서버로 확인했다(PLAN-spring-boot-4.md §2).
        mockMvc.perform(get("/admin/member/settings"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/admin/login"));
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
    @DisplayName("MANAGER는 내 설정 페이지(/admin/member/settings) 접근이 가능하다")
    @WithMockUser(roles = "MANAGER")
    void manager_memberSettings_ok() throws Exception {
        mockMvc.perform(get("/admin/member/settings"))
                .andExpect(status().isOk());
    }

    // ===================== 쪽지함 페이지(/admin/member/messages) — MY_INFO 게이트에 정확 경로 1개 추가(2026-10-05 승인) =====================

    @Test
    @DisplayName("쪽지함: ADMIN·MANAGER(권한 0개)는 /admin/member/messages 페이지에 접근할 수 있다(GET·HEAD)")
    @WithMockUser(roles = "MANAGER")
    void manager_messagesPage_ok() throws Exception {
        mockMvc.perform(get("/admin/member/messages")).andExpect(status().isOk());
        mockMvc.perform(head("/admin/member/messages")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("쪽지함: ADMIN도 접근할 수 있다")
    @WithMockUser(roles = "ADMIN")
    void admin_messagesPage_ok() throws Exception {
        mockMvc.perform(get("/admin/member/messages")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("쪽지함: ROLE_USER는 403이고 비로그인은 로그인으로 리다이렉트된다")
    void messagesPage_userForbidden_anonymousRedirects() throws Exception {
        mockMvc.perform(get("/admin/member/messages")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("u").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/member/messages"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    @DisplayName("쪽지함: 게이트는 정확 경로 1개다 — 하위·유사 경로(/messages/x, /messagesX, /messages/../x)는 MANAGER에게 403(ADMIN 캐치올)이다")
    @WithMockUser(roles = "MANAGER")
    void manager_messagesPageLookalikePaths_forbidden() throws Exception {
        for (String path : new String[]{"/admin/member/messages/x", "/admin/member/messages/x/y", "/admin/member/messagesX",
                "/admin/member/message", "/admin/member/messages.json"}) {
            mockMvc.perform(get(path)).andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("쪽지함: 세미콜론 매트릭스 파라미터가 든 경로는 방화벽이 400으로 거부해 게이트에 닿지 않는다(403보다 더 엄격한 차단)")
    @WithMockUser(roles = "MANAGER")
    void manager_messagesPageMatrixParameterPath_rejectedByFirewall() throws Exception {
        mockMvc.perform(get("/admin/member/messages;x=1/y")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/admin/member/messages;jsessionid=1")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("쪽지함: 게이트는 메서드를 구분하지 않는다 — 유효한 CSRF 토큰의 POST는 게이트를 통과해 핸들러가 없어 405다(MANAGER 403이 아님을 기록)")
    @WithMockUser(roles = "MANAGER")
    void manager_messagesPagePost_passesGateButHasNoHandler() throws Exception {
        // 그래서 이 경로에 쓰기 핸들러를 추가하면 안 된다 — MessagePageMethodConventionTest가 CI에서 막는다
        mockMvc.perform(post("/admin/member/messages").with(csrf())).andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("쪽지함: CSRF 토큰이 없는 POST의 403은 위 405 시험과 별개의 CSRF 거부다")
    @WithMockUser(roles = "MANAGER")
    void manager_messagesPagePost_withoutCsrf_forbidden() throws Exception {
        mockMvc.perform(post("/admin/member/messages")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("비로그인 사용자는 내 설정 페이지(/admin/member/settings)에서 로그인으로 리다이렉트된다")
    void unauthenticated_memberSettings_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin/member/settings"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
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

    // ==================== 통합 검색 인가 범위 검증 ====================

    @Test
    @DisplayName("MANAGER는 통합 검색 API(/admin/api/search-results)에 접근이 가능하다")
    @WithMockUser(roles = "MANAGER")
    void manager_searchApi_ok() throws Exception {
        mockMvc.perform(get("/admin/api/search-results"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADMIN은 통합 검색 API에 접근이 가능하다")
    @WithMockUser(roles = "ADMIN")
    void admin_searchApi_ok() throws Exception {
        mockMvc.perform(get("/admin/api/search-results"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("USER는 통합 검색 API에 접근할 수 없다(403)")
    @WithMockUser(roles = "USER")
    void user_searchApi_forbidden() throws Exception {
        mockMvc.perform(get("/admin/api/search-results"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미인증 사용자의 통합 검색 API 호출은 JSON 401")
    void unauthenticated_searchApi_json401() throws Exception {
        mockMvc.perform(get("/admin/api/search-results"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("통합 검색 API 하위 경로는 게이트에 포함되지 않아 MANAGER가 접근할 수 없다(ADMIN 캐치올, 403)")
    @WithMockUser(roles = "MANAGER")
    void manager_searchApiSubPath_forbidden() throws Exception {
        mockMvc.perform(get("/admin/api/search-results/x"))
                .andExpect(status().isForbidden());
    }

    // ==================== 공지사항 인가 범위 검증 ====================

    @Test
    @DisplayName("MANAGER는 옛 공지 화면 주소(/admin/notice/manage — 게시글 관리로 가는 리다이렉트 전용 게이트 경로)에 접근이 가능하다")
    @WithManager
    void manager_noticeManagePage_ok() throws Exception {
        mockMvc.perform(get("/admin/notice/manage"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("MANAGER는 게시글 목록 API(/admin/api/boards/{boardId}/posts)에 접근이 가능하다(어느 게시판이든 조회 권한 — 공지는 공지 게시판의 게시글)")
    @WithManager
    void manager_boardPostsApi_ok() throws Exception {
        mockMvc.perform(get("/admin/api/boards/1/posts"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("옛 공지 API 경로(/admin/api/notices/**)와 리다이렉트 경로의 하위 경로는 게이트가 없어 ADMIN 캐치올로 떨어진다 — 모든 권한을 가진 MANAGER도 403 (PLAN-notice-to-board.md 쟁점 9)")
    @WithManager
    void manager_legacyNoticePaths_forbidden() throws Exception {
        mockMvc.perform(get("/admin/api/notices")).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/api/notices/1")).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/api/notices/content-images")).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/notice/manage/extra")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("어느 게시판에도 조회 권한이 없는 MANAGER는 리다이렉트 전용 경로 /admin/notice/manage도 403이다(게이트가 기능 단위 READ)")
    @WithMockUser(roles = "MANAGER")
    void manager_withoutBoardRead_noticeRedirectPath_forbidden() throws Exception {
        mockMvc.perform(get("/admin/notice/manage")).andExpect(status().isForbidden());
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
        mockMvc.perform(get("/admin/api/boards/1/posts"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("미인증 사용자의 공지사항 목록 API 호출은 JSON 401")
    void unauthenticated_noticesApi_json401() throws Exception {
        mockMvc.perform(get("/admin/api/boards/1/posts"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("미인증 사용자의 공지사항 관리 페이지 접근은 로그인 페이지로 리다이렉트")
    void unauthenticated_noticeManagePage_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin/notice/manage"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    @DisplayName("CSRF 토큰 없이 공지사항 생성 POST 시 403")
    @WithMockUser(roles = "ADMIN")
    void createNotice_missingCsrf_forbidden() throws Exception {
        mockMvc.perform(post("/admin/api/boards/1/posts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CSRF 토큰 포함 시 공지사항 생성 POST가 인가를 통과한다(ADMIN)")
    @WithMockUser(roles = "ADMIN")
    void createNotice_withCsrf_passesAuthorization() throws Exception {
        mockMvc.perform(post("/admin/api/boards/1/posts").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
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
                .andExpect(redirectedUrl("/admin/login"));
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
                .andExpect(redirectedUrl("/admin/login"));
    }

    // ==================== 공개 게시판 인가 범위 검증 (2026-10-08 승인, PLAN-board.md 쟁점 10) ====================
    // /boards/**가 하위 세그먼트 전체를 포괄하므로 목록·상세·첨부 라우트는 별도 규칙 없이 무인증 공개다 — 암묵적 동작을 명시적으로 고정한다.

    @Test
    @DisplayName("비인증 GET /boards/{id}, /boards/{id}/posts/{postId}, 첨부 다운로드는 200 (permitAll, 로그인 리다이렉트 아님)")
    void publicBoards_unauthenticatedGet_ok() throws Exception {
        mockMvc.perform(get("/boards/1")).andExpect(status().isOk());
        mockMvc.perform(get("/boards/1/posts/2")).andExpect(status().isOk());
        mockMvc.perform(get("/boards/1/posts/2/attachments/3")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("비인증 HEAD /boards/{id}/posts/{postId}/attachments/{attachmentId}는 200 (permitAll)")
    void publicBoards_unauthenticatedHead_ok() throws Exception {
        mockMvc.perform(head("/boards/1/posts/2/attachments/3")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("CSRF 없는 비인증 POST /boards/{id}는 CsrfFilter가 먼저 차단해 403")
    void publicBoards_unauthenticatedPost_missingCsrf_forbidden() throws Exception {
        mockMvc.perform(post("/boards/1")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CSRF 포함 비인증 POST/PATCH/DELETE /boards/**는 denyAll+익명 판별로 /admin/login 302 리다이렉트 (405/401이 아님)")
    void publicBoards_unauthenticatedWrite_withCsrf_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/boards/1").with(csrf())).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(post("/boards/1/posts/2/attachments/3").with(csrf())).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(delete("/boards/1/posts/2").with(csrf())).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    @DisplayName("CSRF 포함 ADMIN POST /boards/{id}는 denyAll+인증 사용자 판별로 정확히 403 (denyAll이 역할 불문임을 확인)")
    @WithMockUser(roles = "ADMIN")
    void publicBoards_adminPost_withCsrf_forbidden() throws Exception {
        mockMvc.perform(post("/boards/1").with(csrf())).andExpect(status().isForbidden());
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
                .andExpect(redirectedUrl("/admin/login"));
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

    // ==================== 기본 거부 (감사 M-08, PLAN-default-deny-authorization.md) ====================

    @Test
    @DisplayName("비인증 GET 미분류 경로는 기본 거부 — 404가 아니라 로그인 페이지로 302")
    void defaultDeny_anonymousUnclassifiedPath_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/does-not-exist"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }

    // ==================== 공개 메인(/)과 배너 이미지 (2026-10-09 승인, PLAN-public-home-banner.md 쟁점 1) ====================

    @Test
    @DisplayName("비인증 GET / 는 200 (정확 경로 공개 — 더는 로그인으로 302되지 않는다)")
    void publicHome_anonymousGet_ok() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("비인증 HEAD / 는 200")
    void publicHome_anonymousHead_ok() throws Exception {
        mockMvc.perform(head("/")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("/ 의 그 외 메서드는 막힌다 — CSRF 없으면 403, CSRF 포함 비인증은 /admin/login 302, ADMIN도 403 (denyAll)")
    @WithMockUser(roles = "ADMIN")
    void publicHome_writeMethods_denied() throws Exception {
        mockMvc.perform(post("/")).andExpect(status().isForbidden());
        mockMvc.perform(post("/").with(csrf())).andExpect(status().isForbidden());
        mockMvc.perform(delete("/").with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("/ 쓰기 시도는 비인증이면 /admin/login 302 (denyAll+익명 판별)")
    void publicHome_unauthenticatedWrite_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/").with(csrf())).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    @DisplayName("/ 를 열어도 하위·유사 경로는 기본 거부 그대로다 — 정확 경로 1개만 공개된다")
    void publicHome_doesNotOpenOtherPaths() throws Exception {
        mockMvc.perform(get("/index.html")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(get("/home")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(get("/does-not-exist")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    @DisplayName("비인증 GET·HEAD /banners/{id}/image는 200 (permitAll)")
    void publicBannerImage_anonymousGetAndHead_ok() throws Exception {
        mockMvc.perform(get("/banners/1/image")).andExpect(status().isOk());
        mockMvc.perform(head("/banners/1/image")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("/banners/** 의 이미지 외 경로·쓰기 메서드는 막힌다 (denyAll)")
    void publicBanners_otherPathsAndWrites_denied() throws Exception {
        mockMvc.perform(get("/banners")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(get("/banners/1")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(get("/banners/1/image/extra")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(post("/banners/1/image").with(csrf())).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(post("/banners/1/image")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN POST /banners/{id}/image는 denyAll로 403 (역할 불문)")
    @WithMockUser(roles = "ADMIN")
    void publicBanners_adminWrite_forbidden() throws Exception {
        mockMvc.perform(post("/banners/1/image").with(csrf())).andExpect(status().isForbidden());
    }

    // ==================== 배너 관리 게이트 (DELEGABLE BANNER — 카탈로그에서 자동 생성) ====================

    @Test
    @DisplayName("비인증 배너 관리 페이지·API는 로그인으로 보낸다(페이지 302, API JSON 401)")
    void adminBanner_unauthenticated() throws Exception {
        mockMvc.perform(get("/admin/banner/manage")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(get("/admin/api/banners")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("배너 권한(member_permission BANNER)이 없는 MANAGER는 페이지·API 게이트에서 403 — 위임 전에는 열리지 않는다")
    @WithManager
    void adminBanner_managerWithoutGrant_forbidden() throws Exception {
        mockMvc.perform(get("/admin/banner/manage")).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/api/banners")).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/api/banners/1/image")).andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/api/banners/order").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN은 배너 관리 페이지·API 게이트를 통과한다")
    @WithMockUser(roles = "ADMIN")
    void adminBanner_admin_ok() throws Exception {
        mockMvc.perform(get("/admin/banner/manage")).andExpect(status().isOk());
        mockMvc.perform(get("/admin/api/banners")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN도 미분류 경로는 403 (denyAll은 역할과 무관)")
    void defaultDeny_adminUnclassifiedPath_403() throws Exception {
        mockMvc.perform(get("/does-not-exist"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("MANAGER도 미분류 경로는 403")
    void defaultDeny_managerUnclassifiedPath_403() throws Exception {
        mockMvc.perform(get("/does-not-exist"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("/favicon.ico는 명시 공개라 기존대로 404 (로그인 리다이렉트로 바뀌지 않음)")
    void defaultDeny_favicon_stays404() throws Exception {
        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("정적 예약 접두사 4개는 존재하지 않는 파일이어도 404 (기본 거부 302로 바뀌지 않음)")
    void defaultDeny_staticPrefixes_publicGet404() throws Exception {
        for (String prefix : new String[]{"/css", "/js", "/img", "/vendor"}) {
            mockMvc.perform(get(prefix + "/__none__.txt"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(head(prefix + "/__none__.txt"))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("정적 접두사 하위라도 GET/HEAD 외 메서드는 공개되지 않는다 (CSRF 포함 비인증 POST → 302)")
    void defaultDeny_staticPrefix_postNotPublic() throws Exception {
        mockMvc.perform(post("/css/__none__.txt").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    @DisplayName("static/ 최상위 항목이 SecurityConfig의 공개 정적 경로와 일치한다 (새 정적 디렉터리 누락 시 화면이 조용히 깨지는 것 방지)")
    void defaultDeny_staticTopLevelEntries_matchPublicPaths() throws Exception {
        java.util.Set<String> actual = new java.util.TreeSet<>();
        for (org.springframework.core.io.Resource r
                : new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath:/static/*")) {
            actual.add(r.getFilename());
        }
        java.util.Set<String> allowed = new java.util.TreeSet<>();
        for (String p : SecurityConfig.STATIC_PUBLIC_PATHS) {
            // "/css/**" → "css", "/favicon.ico" → "favicon.ico"
            allowed.add(p.substring(1).replaceAll("/\\*\\*$", ""));
        }
        // favicon.ico는 파일이 없어도 공개 목록에 있으므로 실제 항목의 부분집합이면 된다.
        org.assertj.core.api.Assertions.assertThat(allowed)
                .as("static/ 최상위 항목은 전부 SecurityConfig.STATIC_PUBLIC_PATHS에 있어야 한다")
                .containsAll(actual);
    }

    @Test
    @DisplayName("비인증 GET /admin/api/존재하지않는경로는 기존대로 JSON 401 (Security가 DispatcherServlet 도달 전에 차단 — 무회귀)")
    void notFound_unauthenticatedAdminApiUnmappedPath_json401() throws Exception {
        mockMvc.perform(get("/admin/api/does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("CSRF 포함 비인증 POST 미분류 경로는 기본 거부 — 로그인 페이지로 302 " +
            "(ERROR 재디스패치는 MockMvc 밖이라 DefaultDenyErrorDispatchIntegrationTest가 실서버로 검증)")
    void defaultDeny_unauthenticatedPostUnclassifiedPath_withCsrf_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/does-not-exist").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
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

    @GetMapping("/admin/member/settings")
    String memberSettings() {
        return "settings";
    }
}

@TestStubController
class AdminSearchApiStubController {

    @GetMapping({"/admin/api/search-results", "/admin/api/search-results/x"})
    String searchResults() {
        return "{}";
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

    @GetMapping("/admin/api/boards/{boardId}/posts")
    String noticesList() {
        return "[]";
    }

    @PostMapping("/admin/api/boards/{boardId}/posts")
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
class PublicBoardStubController {

    @GetMapping("/boards/{boardId}")
    String list() {
        return "public-board-list";
    }

    @PostMapping("/boards/{boardId}")
    ResponseEntity<String> create() {
        // denyAll이 이 매핑 자체에 도달하지 못하게 막는지 검증하는 스텁 — 실제로는 존재하지 않는 엔드포인트.
        return ResponseEntity.ok("{}");
    }

    @GetMapping("/boards/{boardId}/posts/{postId}")
    String detail() {
        return "public-board-detail";
    }

    @DeleteMapping("/boards/{boardId}/posts/{postId}")
    ResponseEntity<String> deletePost() {
        return ResponseEntity.ok("{}");
    }

    @GetMapping("/boards/{boardId}/posts/{postId}/attachments/{attachmentId}")
    String attachment() {
        return "public-board-attachment";
    }

    @PostMapping("/boards/{boardId}/posts/{postId}/attachments/{attachmentId}")
    ResponseEntity<String> attachmentCreate() {
        return ResponseEntity.ok("{}");
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

@TestStubController
class AdminMessagePageStubController {

    /** 쪽지함 페이지 스텁 — 실제 컨트롤러처럼 GET 핸들러만 둔다(HEAD는 GET이 처리). */
    @GetMapping("/admin/member/messages")
    String messagesPage() {
        return "messages";
    }
}

@TestStubController
class HomeStubController {

    @GetMapping("/")
    String home() {
        return "public-home";
    }

    @PostMapping("/")
    ResponseEntity<String> write() {
        // denyAll이 이 매핑 자체에 도달하지 못하게 막는지 검증하는 스텁 — 실제로는 존재하지 않는 엔드포인트.
        return ResponseEntity.ok("{}");
    }

    @DeleteMapping("/")
    ResponseEntity<String> delete() {
        return ResponseEntity.ok("{}");
    }
}

@TestStubController
class PublicBannerStubController {

    @GetMapping("/banners/{id}/image")
    String image() {
        return "banner-image";
    }

    @PostMapping("/banners/{id}/image")
    ResponseEntity<String> write() {
        return ResponseEntity.ok("{}");
    }

    @GetMapping("/banners/{id}")
    String detail() {
        return "banner-detail";
    }
}

@TestStubController
class AdminBannerStubController {

    @GetMapping("/admin/banner/manage")
    String page() {
        return "banner-manage";
    }

    @GetMapping("/admin/api/banners")
    String list() {
        return "[]";
    }

    @GetMapping("/admin/api/banners/{id}/image")
    String image() {
        return "image";
    }

    @org.springframework.web.bind.annotation.PutMapping("/admin/api/banners/order")
    ResponseEntity<String> order() {
        return ResponseEntity.ok("[]");
    }
}
