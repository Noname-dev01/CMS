package com.cms.publicweb.inquiry;

import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** enabled=false(기본값과 같은 상태) — 문의 기능 닫힘: 캡차 없이 제출을 받는 경로가 없다 (PLAN-inquiry.md R1-3). */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {"cms.inquiry.turnstile.enabled=false", "cms.rate-limit.enabled=false"})
class PublicInquiryClosedIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CaptchaVerifier captchaVerifier;

    @Test
    @DisplayName("enabled=false: GET·POST /inquiry는 모두 503 안내 페이지, 저장도 캡차 호출도 없다 (CSRF 토큰이 맞아도)")
    void closed_rejectsEverything() throws Exception {
        jdbc.update("DELETE FROM inquiry");
        when(captchaVerifier.verify(any(), any())).thenReturn(true);

        mockMvc.perform(get("/inquiry")).andExpect(status().isServiceUnavailable());
        mockMvc.perform(post("/inquiry").with(csrf()).param("name", "홍길동").param("email", "a@b.co").param("title", "제목")
                        .param("body", "본문").param("consent", "true").param("cf-turnstile-response", "token"))
                .andExpect(status().isServiceUnavailable());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inquiry", Integer.class)).isZero();
        verify(captchaVerifier, never()).verify(any(), any());
    }
}
