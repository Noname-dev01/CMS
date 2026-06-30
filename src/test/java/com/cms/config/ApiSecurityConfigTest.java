package com.cms.config;

import com.cms.admin.visit.repository.VisitLogRepository;
import com.cms.config.auth.AdminSecurityService;
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
import org.springframework.http.HttpStatus;
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

@WebMvcTest(controllers = AdminApiTestController.class)
@Import({
        SecurityConfig.class,
        ApiSecurityConfigTest.MockConfig.class
})
@ActiveProfiles({"test", "webmvc-test"})
class ApiSecurityConfigTest {

    @Autowired
    MockMvc mockMvc;

    @TestConfiguration
    static class MockConfig {

        @Bean
        public AdminSecurityService adminSecurityService() {
            return Mockito.mock(AdminSecurityService.class);
        }

        @Bean
        public VisitLoggingAuthenticationSuccessHandler visitLoggingAuthenticationSuccessHandler() {
            VisitLogRepository mockRepo = Mockito.mock(VisitLogRepository.class);
            return new VisitLoggingAuthenticationSuccessHandler(mockRepo);
        }
    }

    @Test
    @DisplayName("미인증 /admin/api/** 요청은 JSON 401을 반환한다")
    void adminApi_unauthenticated_returns401Json() throws Exception {
        mockMvc.perform(get("/admin/api/security-test"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/admin/api/security-test"));
    }

    @Test
    @DisplayName("비관리자 /admin/api/** 요청은 JSON 403을 반환한다")
    @WithMockUser(roles = "USER")
    void adminApi_roleUser_returns403Json() throws Exception {
        mockMvc.perform(get("/admin/api/security-test"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/admin/api/security-test"));
    }

    @Test
    @DisplayName("인증된 사용자의 CSRF 토큰 없는 POST 요청은 JSON 403을 반환한다")
    @WithMockUser(roles = "ADMIN")
    void adminApi_postWithoutCsrf_returns403() throws Exception {
        mockMvc.perform(post("/admin/api/security-test")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미인증 상태에서 CSRF 토큰 없는 POST 요청은 JSON 401을 반환한다")
    void adminApi_unauthenticated_postWithoutCsrf_returns401Json() throws Exception {
        mockMvc.perform(post("/admin/api/security-test")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value("/admin/api/security-test"));
    }

    @Test
    @DisplayName("CSRF 토큰 포함 인증된 POST 요청은 성공한다")
    @WithMockUser(roles = "ADMIN")
    void adminApi_postWithCsrf_succeeds() throws Exception {
        mockMvc.perform(post("/admin/api/security-test")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("미인증 /admin/api/** 요청은 로그인 페이지로 리다이렉트하지 않는다")
    void adminApi_unauthenticated_doesNotRedirect() throws Exception {
        mockMvc.perform(get("/admin/api/security-test"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"));
    }
}

// ==================== 슬라이스 테스트 전용 스텁 컨트롤러 ====================

@TestStubController
class AdminApiTestController {

    @GetMapping("/admin/api/security-test")
    ResponseEntity<String> get() {
        return ResponseEntity.ok("{}");
    }

    @PostMapping("/admin/api/security-test")
    ResponseEntity<String> post() {
        return ResponseEntity.status(HttpStatus.CREATED).body("{}");
    }
}
