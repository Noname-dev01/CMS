package com.cms.common.api;

import com.cms.admin.menu.service.MenuService;
import com.cms.admin.visit.repository.VisitLogRepository;
import com.cms.config.SecurityConfig;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link GlobalApiExceptionHandler}의 신규 400/405/415/406 매핑·JSON Content-Type 보장·catch-all
 * 안전 진단이 실제 {@link SecurityConfig} 필터 체인을 포함한 디스패치에서도 동일하게 성립하는지
 * 확인한다(계획 리뷰 v9~v10 반영). handler 단위 검증은 {@code GlobalApiExceptionHandlerTest} 참조.
 */
@WebMvcTest(controllers = ApiErrorContractTestController.class)
@Import({
        SecurityConfig.class,
        RateLimitFilterConfig.class,
        ApiErrorContractIntegrationTest.MockConfig.class
})
@ActiveProfiles({"test", "webmvc-test"})
class ApiErrorContractIntegrationTest {

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
                    java.time.Clock.systemDefaultZone());
        }

        @Bean
        public LockingAuthenticationFailureHandler lockingAuthenticationFailureHandler(
                LoginFailureService loginFailureService) {
            return new LockingAuthenticationFailureHandler(loginFailureService);
        }
    }

    @Test
    @DisplayName("경로 변수 타입 불일치는 400 INVALID_REQUEST JSON (이전엔 500으로 오분류됨, 감사 M-03)")
    @WithMockUser(roles = "ADMIN")
    void pathVariableTypeMismatch_returns400Json() throws Exception {
        mockMvc.perform(get("/admin/api/error-contract-test/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("등록되지 않은 HTTP 메서드는 405이며 지원 메서드로 Allow 헤더를 구성한다")
    @WithMockUser(roles = "ADMIN")
    void unregisteredMethod_returns405WithAllowHeader() throws Exception {
        mockMvc.perform(delete("/admin/api/error-contract-test/1").with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(header().string("Allow", containsString("POST")));
    }

    @Test
    @DisplayName("지원하지 않는 요청 Content-Type은 415 UNSUPPORTED_MEDIA_TYPE JSON")
    @WithMockUser(roles = "ADMIN")
    void unsupportedRequestContentType_returns415Json() throws Exception {
        mockMvc.perform(post("/admin/api/error-contract-test/1")
                        .with(csrf())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("plain text body"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("서버가 응답 형식을 협상하지 못하면 406 NOT_ACCEPTABLE JSON (이전엔 500으로 오분류됨)")
    @WithMockUser(roles = "ADMIN")
    void unacceptableResponseFormat_returns406Json() throws Exception {
        mockMvc.perform(get("/admin/api/error-contract-test-strict")
                        .accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    @Test
    @DisplayName("Accept: text/html 요청도 API 오류를 HTML로 바꾸지 않는다 (JSON Content-Type 보장, 계획 리뷰 v9)")
    @WithMockUser(roles = "ADMIN")
    void acceptTextHtml_doesNotConvertApiErrorToHtml() throws Exception {
        mockMvc.perform(get("/admin/api/error-contract-test/abc")
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("예상 못한 서버 예외는 500 INTERNAL_ERROR JSON이며 예외 메시지를 그대로 노출하지 않는다")
    @WithMockUser(roles = "ADMIN")
    void unexpectedException_returns500Json_withoutLeakingExceptionMessage() throws Exception {
        mockMvc.perform(get("/admin/api/error-contract-test-boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(not(containsString("secret-exception-message"))));
    }

    @Test
    @DisplayName("미인증 요청은 여전히 401 JSON을 반환한다 (신규 handler 추가에 따른 Security 우선순위 회귀 없음)")
    void unauthenticated_stillReturns401Json() throws Exception {
        mockMvc.perform(get("/admin/api/error-contract-test/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}

// ==================== 슬라이스 테스트 전용 스텁 컨트롤러 ====================

@TestStubController
class ApiErrorContractTestController {

    @GetMapping("/admin/api/error-contract-test/{id}")
    ResponseEntity<String> get(@PathVariable Long id) {
        return ResponseEntity.ok("{\"id\":" + id + "}");
    }

    @PostMapping(value = "/admin/api/error-contract-test/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> post(@PathVariable Long id, @RequestBody String body) {
        return ResponseEntity.status(HttpStatus.CREATED).body("{}");
    }

    /**
     * {@code produces}를 JSON으로만 좁혀 406(NOT_ACCEPTABLE) 테스트 전용으로 둔다 — 위
     * {@code {id}} 경로는 produces 제약이 없어 Accept 협상이 핸들러 매핑 단계에서 먼저
     * 실패하지 않고 정상적으로 타입 변환 단계까지 도달한다(계획 리뷰 반영 — 406 회귀는
     * 이 별도 엔드포인트로 검증).
     */
    @GetMapping(value = "/admin/api/error-contract-test-strict", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> strict() {
        return ResponseEntity.ok("{}");
    }

    @GetMapping("/admin/api/error-contract-test-boom")
    ResponseEntity<String> boom() {
        throw new RuntimeException("secret-exception-message");
    }
}
