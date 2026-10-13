package com.cms.publicweb.inquiry;

import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 문의 폼의 레이트리밋(429)과 "문의 기능 닫힘" 시험. 레이트리밋 규칙은 실제 {@code application.yml}의 {@code inquiry-submit}(POST 10회/3600초)을 쓴다 —
 * 원격 주소를 시험마다 다르게 줘 quota를 나눠 쓰지 않게 한다. 닫힘(enabled=false)은 별도 컨텍스트가 필요해 {@code PublicInquiryClosedIntegrationTest}가 맡는다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
// build.gradle이 테스트 JVM 전체에 CMS_RATE_LIMIT_ENABLED=false를 주입하므로(다른 시험의 quota 충돌 방지) 이 시험만 명시적으로 켠다.
@TestPropertySource(properties = "cms.rate-limit.enabled=true")
class PublicInquiryRateLimitIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CaptchaVerifier captchaVerifier;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM inquiry");
        when(captchaVerifier.verify(any(), any())).thenReturn(true);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM inquiry");
    }

    private static RequestPostProcessor from(String remoteAddr) {
        return request -> {
            request.setRemoteAddr(remoteAddr);
            return request;
        };
    }

    private MockHttpServletRequestBuilder invalidPost(String remoteAddr, boolean withCsrf) {
        MockHttpServletRequestBuilder request = post("/main/inquiry").with(from(remoteAddr))
                .param("name", "n").param("email", "bad").param("title", "t").param("body", "b")
                .param("consent", "true").param("cf-turnstile-response", "t");
        return withCsrf ? request.with(csrf()) : request;
    }

    @Test
    @DisplayName("POST /inquiry는 IP당 10회 버스트 뒤 429(Retry-After) — 입력 오류 재시도도 quota를 쓰고, CSRF 실패 요청은 quota를 쓰지 않는다")
    void submit_isRateLimited_andCsrfFailuresDoNotConsumeQuota() throws Exception {
        String ip = "198.51.100.41";

        for (int i = 0; i < 25; i++) {                                                     // CSRF 없는 요청은 필터 앞(CsrfFilter)에서 403 — quota 소비 없음
            mockMvc.perform(invalidPost(ip, false)).andExpect(status().isForbidden());
        }
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(invalidPost(ip, true)).andExpect(status().isBadRequest());   // 검증 실패도 quota를 쓴다
        }
        mockMvc.perform(invalidPost(ip, true))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        mockMvc.perform(invalidPost("198.51.100.42", true)).andExpect(status().isBadRequest());   // 다른 IP는 영향 없음
        mockMvc.perform(get("/main/inquiry").with(from(ip))).andExpect(status().isOk());              // 폼 조회는 별도 규칙(public-inquiry)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inquiry", Integer.class)).isZero();
    }
}
