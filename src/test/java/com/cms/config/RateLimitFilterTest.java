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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Clock;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code RateLimitFilter}의 규칙 매칭·우선순위·응답을 검증한다
 * (PLAN-public-endpoint-rate-limit.md 쟁점 1·4·8).
 *
 * <p>cms.rate-limit을 낮은 capacity로 오버라이드해 빠르게 소진시킨다 — 규칙 순서(쟁점 8)까지
 * 함께 검증하기 위해 좁은 패턴(narrow)을 넓은 패턴(wide)보다 먼저 선언한다.
 */
@WebMvcTest(controllers = RateLimitFilterTestStubController.class)
@Import({
        SecurityConfig.class,
        RateLimitFilterConfig.class,
        RateLimitFilterTest.MockConfig.class
})
@ActiveProfiles({"test", "webmvc-test"})
@TestPropertySource(properties = {
        "cms.rate-limit.enabled=true",
        "cms.rate-limit.max-keys=1000",
        "cms.rate-limit.rules[0].id=narrow",
        "cms.rate-limit.rules[0].pattern=/rl-test/limited",
        "cms.rate-limit.rules[0].methods=GET,HEAD",
        "cms.rate-limit.rules[0].capacity=2",
        "cms.rate-limit.rules[0].refill-period-seconds=60",
        "cms.rate-limit.rules[1].id=wide",
        "cms.rate-limit.rules[1].pattern=/rl-test/**",
        "cms.rate-limit.rules[1].methods=GET,HEAD",
        "cms.rate-limit.rules[1].capacity=100",
        "cms.rate-limit.rules[1].refill-period-seconds=60"
})
class RateLimitFilterTest {

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

    /**
     * TokenBucketRateLimiter(Caffeine 캐시)는 슬라이스 컨텍스트에서 싱글턴이라 여러 테스트
     * 메서드가 상태를 공유한다 — MockMvc의 기본 remoteAddr("127.0.0.1")를 그대로 쓰면 테스트
     * 간 버킷이 서로 간섭한다. 테스트마다 서로 다른 원격 주소를 부여해 격리한다.
     */
    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    @DisplayName("좁은 규칙(narrow, capacity=2)이 넓은 규칙(wide, capacity=100)보다 먼저 매칭돼 더 엄격한 한도가 적용된다")
    void narrowRuleTakesPrecedence() throws Exception {
        RequestPostProcessor ip = from("10.10.10.1");
        mockMvc.perform(get("/rl-test/limited").with(ip)).andExpect(status().isOk());
        mockMvc.perform(get("/rl-test/limited").with(ip)).andExpect(status().isOk());
        mockMvc.perform(get("/rl-test/limited").with(ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("HEAD 요청도 GET과 동일한 규칙을 소비한다 (HEAD 우회 방지)")
    void headRequest_consumesSameBucketAsGet() throws Exception {
        RequestPostProcessor ip = from("10.10.10.2");
        mockMvc.perform(get("/rl-test/limited").with(ip)).andExpect(status().isOk());
        mockMvc.perform(head("/rl-test/limited").with(ip)).andExpect(status().isOk());
        mockMvc.perform(get("/rl-test/limited").with(ip)).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("규칙에 매칭되지 않는 경로는 무제한이다")
    void unmatchedPath_isUnlimited() throws Exception {
        RequestPostProcessor ip = from("10.10.10.3");
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/rl-test/unlimited").with(ip)).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("/actuator/health는 규칙에 없으므로 무제한이다")
    void actuatorHealth_isUnlimited() throws Exception {
        RequestPostProcessor ip = from("10.10.10.4");
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/actuator/health").with(ip)).andExpect(status().isOk());
        }
    }
}

@TestStubController
class RateLimitFilterTestStubController {

    @GetMapping("/rl-test/limited")
    String limited() {
        return "ok";
    }

    @GetMapping("/rl-test/unlimited")
    String unlimited() {
        return "ok";
    }

    @GetMapping("/actuator/health")
    String health() {
        return "{\"status\":\"UP\"}";
    }
}
