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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.time.Clock;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 레이트리밋 초과 시 경로별 응답 포맷을 검증한다(PLAN-public-endpoint-rate-limit.md 쟁점 5).
 * {@code /admin/api/**}는 기존 {@code ApiErrorResponse} 포맷 JSON 429, 그 외는
 * {@code sendError(429)}를 호출한다 — MockMvc는 실제 컨테이너 ERROR 재디스패치를 수행하지
 * 않으므로(기존 {@code SecurityConfigTest}와 동일한 한계) 여기서는 상태 코드까지만 확인하고,
 * 실제 {@code error/429.html} 렌더링은 Playwright 실기 검증으로 확인한다(쟁점 10).
 */
@WebMvcTest(controllers = RateLimitResponseTestStubController.class)
@Import({
        SecurityConfig.class,
        RateLimitFilterConfig.class,
        RateLimitResponseTest.MockConfig.class
})
@ActiveProfiles({"test", "webmvc-test"})
@TestPropertySource(properties = {
        "cms.rate-limit.enabled=true",
        "cms.rate-limit.max-keys=1000",
        "cms.rate-limit.rules[0].id=api-rule",
        "cms.rate-limit.rules[0].pattern=/admin/api/rl-response-test",
        "cms.rate-limit.rules[0].methods=POST",
        "cms.rate-limit.rules[0].capacity=1",
        "cms.rate-limit.rules[0].refill-period-seconds=60",
        "cms.rate-limit.rules[1].id=page-rule",
        "cms.rate-limit.rules[1].pattern=/rl-response-test/page",
        "cms.rate-limit.rules[1].methods=GET",
        "cms.rate-limit.rules[1].capacity=1",
        "cms.rate-limit.rules[1].refill-period-seconds=60"
})
class RateLimitResponseTest {

    @Autowired
    MockMvc mockMvc;

    @TestConfiguration
    static class MockConfig {
        @Bean
        public AdminSecurityService adminSecurityService() {
            return Mockito.mock(AdminSecurityService.class);
        }

        @Bean
        public MenuService menuService() {
            return Mockito.mock(MenuService.class);
        }

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
                    Clock.systemDefaultZone());
        }

        @Bean
        public LockingAuthenticationFailureHandler lockingAuthenticationFailureHandler(
                LoginFailureService loginFailureService) {
            return new LockingAuthenticationFailureHandler(loginFailureService);
        }
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("/admin/api/** 초과 시 기존 ApiErrorResponse 포맷 JSON 429(code=RATE_LIMITED)")
    void adminApiPath_exceedsLimit_returnsJsonRateLimited() throws Exception {
        RequestPostProcessor ip = from("20.20.20.1");
        mockMvc.perform(post("/admin/api/rl-response-test").with(csrf()).with(ip))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/api/rl-response-test").with(csrf()).with(ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("/admin/api/** 밖의 공개 경로는 초과 시 sendError(429) 호출 — 상태 코드만 확인, 실제 렌더링은 Playwright")
    void nonApiPath_exceedsLimit_sendsError() throws Exception {
        RequestPostProcessor ip = from("20.20.20.2");
        mockMvc.perform(get("/rl-response-test/page").with(ip)).andExpect(status().isOk());
        mockMvc.perform(get("/rl-response-test/page").with(ip)).andExpect(status().isTooManyRequests());
    }
}

@TestStubController
class RateLimitResponseTestStubController {

    @PostMapping("/admin/api/rl-response-test")
    ResponseEntity<Void> apiEndpoint() {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/rl-response-test/page")
    String pageEndpoint() {
        return "ok";
    }
}
