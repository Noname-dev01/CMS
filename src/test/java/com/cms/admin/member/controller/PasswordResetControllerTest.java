package com.cms.admin.member.controller;

import com.cms.admin.AdminMainController;
import com.cms.admin.dashboard.service.DashboardService;
import com.cms.admin.member.service.PasswordResetService;
import com.cms.admin.menu.service.MenuService;
import com.cms.admin.visit.repository.VisitLogRepository;
import com.cms.common.api.GlobalApiExceptionHandler;
import com.cms.common.exception.InvalidRequestException;
import com.cms.config.SecurityConfig;
import com.cms.config.ratelimit.RateLimitFilterConfig;
import com.cms.config.auth.AdminSecurityService;
import com.cms.config.auth.LockingAuthenticationFailureHandler;
import com.cms.config.auth.LoginFailureService;
import com.cms.config.auth.PasswordExpiryService;
import com.cms.config.auth.VisitLoggingAuthenticationSuccessHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 비밀번호 재설정 공개 API·페이지 테스트.
 * 실제 {@link SecurityConfig}를 올려 공개 경로(permitAll)와
 * "미인증 CSRF 실패 → 401 변환" 계약까지 함께 검증한다.
 */
@WebMvcTest(controllers = {PasswordResetController.class, AdminMainController.class})
@Import({
        SecurityConfig.class,
        RateLimitFilterConfig.class,
        PasswordResetControllerTest.MockConfig.class,
        GlobalApiExceptionHandler.class
})
@ActiveProfiles({"test", "webmvc-test"})
// build.gradle의 test 태스크가 전역으로 CMS_RATE_LIMIT_ENABLED=false를 주입하므로,
// 레이트리밋(CsrfFilter 순서) 실증 테스트를 위해 이 슬라이스에서만 명시 활성화한다 —
// 운영 규칙(reset-request capacity=5/3600초)을 application.yml 그대로 사용한다.
@TestPropertySource(properties = "cms.rate-limit.enabled=true")
class PasswordResetControllerTest {

    private static final String VALID_TOKEN = "0123456789abcdef".repeat(4); // 64자 hex

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PasswordResetService passwordResetService;

    @BeforeEach
    void setUp() {
        reset(passwordResetService);
    }

    @TestConfiguration
    static class MockConfig {

        @Bean
        public PasswordResetService passwordResetService() {
            return Mockito.mock(PasswordResetService.class);
        }

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

    // ==================== 공개 페이지 ====================

    @Test
    @DisplayName("비로그인으로 요청 페이지가 200으로 열리고 CSRF meta가 렌더링된다 (@AdminPage advice 예외 없음)")
    void passwordResetPage_anonymous_rendersWithCsrfMeta() throws Exception {
        mockMvc.perform(get("/admin/password-reset"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/password-reset"))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("name=\"_csrf_header\"")));
    }

    @Test
    @DisplayName("비로그인으로 confirm 페이지가 200으로 열리고 referrer 차단 meta가 렌더링된다")
    void confirmPage_anonymous_rendersWithProtections() throws Exception {
        mockMvc.perform(get("/admin/password-reset/confirm"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/password-reset-confirm"))
                .andExpect(content().string(containsString("name=\"referrer\" content=\"no-referrer\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                // 토큰을 다루는 페이지는 외부 도메인 리소스를 로드하지 않는다
                .andExpect(content().string(not(containsString("fonts.googleapis.com"))));
    }

    // ==================== 재설정 메일 발송 요청 API ====================

    @Test
    @DisplayName("비로그인 + CSRF 포함 요청은 200이고 서비스에 이메일·IP가 전달된다")
    void requestReset_anonymousWithCsrf_returns200() throws Exception {
        mockMvc.perform(post("/admin/api/password-reset-requests")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@test.com\"}")
                        .header("X-FORWARDED-FOR", "10.0.0.1, 10.0.0.2"))
                .andExpect(status().isOk());

        // X-FORWARDED-FOR 마지막 홉이 IP로 추출된다
        verify(passwordResetService).requestReset(eq("admin@test.com"), eq("10.0.0.2"));
    }

    @Test
    @DisplayName("공백 포함 이메일은 서비스 진입 전에 400 VALIDATION_ERROR로 거부된다 (@Email이 1차 차단)")
    void requestReset_emailWithSpaces_returns400() throws Exception {
        mockMvc.perform(post("/admin/api/password-reset-requests")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\" admin@test.com \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(passwordResetService);
    }

    @Test
    @DisplayName("이메일 형식 오류는 400 VALIDATION_ERROR")
    void requestReset_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/admin/api/password-reset-requests")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("CSRF 토큰 없는 미인증 POST는 401 (CsrfFilter 실패를 SecurityConfig가 401로 변환)")
    void requestReset_withoutCsrf_returns401() throws Exception {
        mockMvc.perform(post("/admin/api/password-reset-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@test.com\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(passwordResetService);
    }

    // ==================== 레이트리밋(RateLimitFilter) — CsrfFilter 순서 실증 ====================
    // (PLAN-public-endpoint-rate-limit.md 쟁점 4, codex 9라운드 지적) RateLimitFilter가
    // CsrfFilter 다음에 있어야 CSRF 검증 실패 요청이 quota를 소비하지 않는다 — 그렇지 않으면
    // 외부 사이트가 피해자 브라우저로 토큰 없는 form POST를 반복시켜 피해자 IP의 비밀번호
    // 재설정 quota(운영 설정: 5회/3600초)를 고갈시키는 교차 사이트 공격이 가능해진다.
    // application.yml의 실제 운영 규칙(reset-request, capacity=5)을 그대로 사용해 검증한다 —
    // 다른 테스트 메서드와 슬라이스 컨텍스트(TokenBucketRateLimiter 싱글턴)를 공유하므로
    // remoteAddr을 이 테스트 전용 IP로 격리한다.

    private static org.springframework.test.web.servlet.request.RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    @DisplayName("CSRF 토큰 없는 POST를 5회(운영 capacity)보다 많이 반복해도 quota가 소비되지 않는다 (전부 JSON 401)")
    void requestReset_repeatedCsrfFailures_doNotConsumeRateLimitQuota() throws Exception {
        var ip = from("40.40.40.1");
        for (int i = 0; i < 8; i++) { // 운영 capacity(5)보다 많은 8회 반복
            mockMvc.perform(post("/admin/api/password-reset-requests")
                            .with(ip)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"admin@test.com\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        verifyNoInteractions(passwordResetService);
    }

    @Test
    @DisplayName("CSRF 실패가 quota를 소비하지 않았으므로, 이어서 유효한 CSRF로 운영 capacity(5)회 전부 통과하고 6회째 429")
    void requestReset_afterCsrfFailures_validCsrfStillHasFullQuota() throws Exception {
        var ip = from("40.40.40.2");
        for (int i = 0; i < 8; i++) {
            mockMvc.perform(post("/admin/api/password-reset-requests")
                            .with(ip)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"admin@test.com\"}"))
                    .andExpect(status().isUnauthorized());
        }

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/admin/api/password-reset-requests")
                            .with(ip)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"admin@test.com\"}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/admin/api/password-reset-requests")
                        .with(ip)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@test.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    // ==================== 재설정 실행 API ====================

    @Test
    @DisplayName("비로그인 + CSRF 포함 유효 요청은 204")
    void resetPassword_anonymousWithCsrf_returns204() throws Exception {
        mockMvc.perform(post("/admin/api/password-resets")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + VALID_TOKEN + "\","
                                + "\"newPassword\":\"NewPassword1!\",\"confirmPassword\":\"NewPassword1!\"}"))
                .andExpect(status().isNoContent());

        verify(passwordResetService).resetPassword(VALID_TOKEN, "NewPassword1!", "NewPassword1!");
    }

    @Test
    @DisplayName("64자 hex가 아닌 토큰은 서비스 진입 전에 400 — 응답에 토큰 원문이 노출되지 않는다")
    void resetPassword_malformedToken_returns400() throws Exception {
        String malformed = "XYZ123"; // 형식 위반
        mockMvc.perform(post("/admin/api/password-resets")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + malformed + "\","
                                + "\"newPassword\":\"NewPassword1!\",\"confirmPassword\":\"NewPassword1!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                // rejected value(토큰)가 응답 본문에 포함되면 안 된다
                .andExpect(content().string(not(containsString(malformed))));

        verifyNoInteractions(passwordResetService);
    }

    @Test
    @DisplayName("서비스가 토큰을 거부하면 400 INVALID_REQUEST (사유 비구분)")
    void resetPassword_invalidToken_returns400() throws Exception {
        willThrow(new InvalidRequestException("유효하지 않은 재설정 토큰입니다."))
                .given(passwordResetService).resetPassword(anyString(), anyString(), anyString());

        mockMvc.perform(post("/admin/api/password-resets")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + VALID_TOKEN + "\","
                                + "\"newPassword\":\"NewPassword1!\",\"confirmPassword\":\"NewPassword1!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("CSRF 토큰 없는 재설정 실행도 401")
    void resetPassword_withoutCsrf_returns401() throws Exception {
        mockMvc.perform(post("/admin/api/password-resets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + VALID_TOKEN + "\","
                                + "\"newPassword\":\"NewPassword1!\",\"confirmPassword\":\"NewPassword1!\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(passwordResetService);
    }
}
