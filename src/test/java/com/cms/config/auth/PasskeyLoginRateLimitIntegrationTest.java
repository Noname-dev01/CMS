package com.cms.config.auth;

import com.cms.admin.member.passkey.PasskeyRelyingParty;
import com.cms.config.ratelimit.Ticker;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 패스키 단독 로그인 레이트리밋 {@code admin-passkey-login}(PLAN-passkey-login.md 결정 6, 리뷰 2-2·3-1). 시험 JVM은 레이트리밋이 꺼진 채 돌므로
 * (build.gradle {@code CMS_RATE_LIMIT_ENABLED=false}) 여기서 켠다. 버킷은 연속 리필이라 시계를 고정해야 결정적이다 — {@link Ticker} 빈을 가짜로 바꾼다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = "cms.rate-limit.enabled=true")
class PasskeyLoginRateLimitIntegrationTest extends MariaDbContainerSupport {

    private static final String PATH = "/admin/login/passkey";

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    @MockitoBean Ticker ticker;
    @MockitoBean PasskeyRelyingParty relyingParty;
    @MockitoSpyBean PasskeyLoginService passkeyLoginService;

    @Autowired MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        given(ticker.nanos()).willAnswer(invocation -> nanos.get());
        given(relyingParty.isEnabled()).willReturn(true);
        given(relyingParty.discoverableRequestOptions()).willAnswer(invocation -> PublicKeyCredentialRequestOptions.builder()
                .challenge(Bytes.random()).rpId("localhost").userVerification(UserVerificationRequirement.REQUIRED)
                .allowCredentials(List.of()).build());
    }

    private String openLoginPage(MockHttpSession session) throws Exception {
        return (String) mockMvc.perform(get("/admin/login").session(session)).andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("passkeyLoginId");
    }

    @Test
    @DisplayName("같은 IP의 패스키 로그인 POST는 버스트 30회 뒤 429 — 그 요청은 식별·검증까지 가지 않는다. 2초 뒤 1회 보충, 로그인 화면·비밀번호 로그인은 영향 없음")
    void burstThenRefill() throws Exception {
        MockHttpSession session = new MockHttpSession();
        for (int i = 0; i < 30; i++) {
            // 챌린지 없는 제출 — 검증 전에 만료로 끝나 DB에 가지 않는다(버킷만 소비)
            mockMvc.perform(post(PATH).session(session).with(csrf()).param("loginId", "x").param("passkeyCredential", "{}"))
                    .andExpect(redirectedUrl("/admin/login?passkeyExpired"));
        }

        String loginId = openLoginPage(session);
        mockMvc.perform(post(PATH).session(session).with(csrf()).param("loginId", loginId).param("passkeyCredential", "{}"))
                .andExpect(status().isTooManyRequests());
        verify(passkeyLoginService, never()).identify(any());

        mockMvc.perform(post("/admin/login").with(csrf()).param("username", "nobody").param("password", "wrong"))
                .andExpect(redirectedUrl("/admin/login-error"));

        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        loginId = openLoginPage(session);
        mockMvc.perform(post(PATH).session(session).with(csrf()).param("loginId", loginId).param("passkeyCredential", "{}"))
                .andExpect(redirectedUrl("/admin/login?passkeyError"));
        verify(passkeyLoginService, times(1)).identify(any());
        mockMvc.perform(post(PATH).session(session).with(csrf()).param("loginId", "x").param("passkeyCredential", "{}"))
                .andExpect(status().isTooManyRequests());
    }
}
