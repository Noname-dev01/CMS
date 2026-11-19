package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.passkey.PasskeyRelyingParty;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.repository.MemberTotpRecoveryCodeRepository;
import com.cms.admin.member.repository.MemberTotpRepository;
import com.cms.admin.member.service.TotpEnrollment;
import com.cms.admin.member.totp.TotpCodeVerifier;
import com.cms.admin.member.totp.TotpSecretCipher;
import com.cms.config.ratelimit.Ticker;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import com.cms.support.TotpTestSupport;
import com.cms.support.TotpTestSupport.Enrollment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 비밀번호 로그인 레이트리밋 {@code admin-password-login}(PLAN-password-login-rate-limit.md, 이슈 #284) — IP당 버스트 20 + 30초에 1회 보충.
 * 시험 JVM은 레이트리밋이 꺼진 채 돌므로(build.gradle {@code CMS_RATE_LIMIT_ENABLED=false}) 여기서 켠다. 버킷은 연속 보충이라 {@link Ticker}를
 * 가짜로 바꿔 시각을 고정한다. 컨텍스트(버킷 캐시)를 시험끼리 공유하므로 시험마다 다른 IP를 쓴다.
 * "인증 시도 없음"은 유일한 {@code AuthenticationProvider}(BCrypt 비교를 하는 곳)의 호출 횟수로 본다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = "cms.rate-limit.enabled=true")
class PasswordLoginRateLimitIntegrationTest extends MariaDbContainerSupport {

    private static final int BURST = 20;
    private static final String PASSWORD = "password-login-rate-limit";
    private static final String ENROLLMENT_PAGE = "/admin/login/totp-enrollment";

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final List<Long> memberIds = new ArrayList<>();
    private final List<String> userIds = new ArrayList<>();

    @MockitoBean Ticker ticker;
    @MockitoBean PasskeyRelyingParty relyingParty;
    @MockitoSpyBean TotpAwareAuthenticationProvider provider;

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired MemberTotpRepository memberTotpRepository;
    @Autowired MemberTotpRecoveryCodeRepository recoveryCodeRepository;
    @Autowired TotpSecretCipher cipher;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    @BeforeEach
    void setUp() {
        given(ticker.nanos()).willAnswer(invocation -> nanos.get());
        given(relyingParty.isEnabled()).willReturn(true);
        given(relyingParty.discoverableRequestOptions()).willAnswer(invocation -> PublicKeyCredentialRequestOptions.builder()
                .challenge(Bytes.random()).rpId("localhost").userVerification(UserVerificationRequirement.REQUIRED)
                .allowCredentials(List.of()).build());
    }

    @AfterEach
    void cleanUp() {
        for (String userId : userIds) {
            jdbc.update("DELETE FROM visit_log WHERE visitor_user_id = ?", userId);
        }
        TestMembers.delete(jdbc, memberIds);
    }

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private MockHttpServletRequestBuilder login(String ip, String username, String password) {
        return post("/admin/login").with(csrf()).with(fromIp(ip)).param("username", username).param("password", password);
    }

    /** 없는 아이디로 버킷을 비운다 — 계정 잠금이 일어나지 않는다. 20회 모두 인증 프로바이더까지 간다. */
    private void exhaust(String ip) throws Exception {
        for (int i = 0; i < BURST; i++) {
            mockMvc.perform(login(ip, "nobody-" + i, "wrong")).andExpect(redirectedUrl("/admin/login-error"));
        }
    }

    private Member admin() {
        Member saved = TestMembers.save(memberRepository, "pw-rate", Role.ROLE_ADMIN);
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), saved.getId());
        memberIds.add(saved.getId());
        userIds.add(saved.getUserId());
        return memberRepository.findById(saved.getId()).orElseThrow();
    }

    private MockHttpSession passwordLogin(String ip, Member member, String expectedRedirect) throws Exception {
        MvcResult result = mockMvc.perform(login(ip, member.getUserId(), PASSWORD))
                .andExpect(redirectedUrl(expectedRedirect)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private int loginFailures(Member member) {
        return jdbc.queryForObject("SELECT failed_login_count FROM member WHERE id = ?", Integer.class, member.getId());
    }

    @Test
    @DisplayName("F1: 같은 IP의 비밀번호 로그인은 20회까지 인증까지 가고 21번째는 429 — 그 요청은 인증 프로바이더를 부르지 않는다. 30초 뒤 1건 보충")
    void burstThenRefill() throws Exception {
        String ip = "203.0.113.1";
        exhaust(ip);
        verify(provider, times(BURST)).authenticate(any());

        mockMvc.perform(login(ip, "nobody", "wrong")).andExpect(status().isTooManyRequests());
        verify(provider, times(BURST)).authenticate(any());

        nanos.addAndGet(Duration.ofSeconds(29).toNanos());
        mockMvc.perform(login(ip, "nobody", "wrong")).andExpect(status().isTooManyRequests());

        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        mockMvc.perform(login(ip, "nobody", "wrong")).andExpect(redirectedUrl("/admin/login-error"));
        verify(provider, times(BURST + 1)).authenticate(any());
        mockMvc.perform(login(ip, "nobody", "wrong")).andExpect(status().isTooManyRequests());
        verify(provider, times(BURST + 1)).authenticate(any());
    }

    @Test
    @DisplayName("F2: CSRF 토큰 없는 로그인 POST는 한도를 쓰지 않는다 — 교차 사이트 요청으로 관리자 IP의 한도를 비울 수 없다")
    void csrfRejectedRequestsDoNotConsume() throws Exception {
        String ip = "203.0.113.2";
        for (int i = 0; i < BURST + 5; i++) {
            mockMvc.perform(post("/admin/login").with(fromIp(ip)).param("username", "nobody").param("password", "wrong"))
                    .andExpect(status().isForbidden());
        }
        verify(provider, never()).authenticate(any());

        mockMvc.perform(login(ip, "nobody", "wrong")).andExpect(redirectedUrl("/admin/login-error"));
        verify(provider, times(1)).authenticate(any());
    }

    @Test
    @DisplayName("F3: 한도를 비운 IP에서도 로그인 화면·진행 중인 2단계(코드·강제 등록)·패스키 흐름은 그대로이고, 다른 IP의 비밀번호 로그인은 인증까지 간다")
    void otherLoginPathsUnaffected() throws Exception {
        String ip = "203.0.113.3";
        Member pendingAdmin = admin();
        Enrollment enrollment = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, pendingAdmin.getId(), clock);
        Member unenrolledAdmin = admin();
        // 버킷을 비우기 전에 중간 상태 세션 두 종을 만든다(각 1토큰)
        MockHttpSession pending = passwordLogin(ip, pendingAdmin, "/admin/login/totp");
        MockHttpSession enrolling = passwordLogin(ip, unenrolledAdmin, ENROLLMENT_PAGE);
        for (int i = 0; i < BURST - 2; i++) {
            mockMvc.perform(login(ip, "nobody-" + i, "wrong")).andExpect(redirectedUrl("/admin/login-error"));
        }
        mockMvc.perform(login(ip, "nobody", "wrong")).andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/admin/login").with(fromIp(ip))).andExpect(status().isOk()).andExpect(view().name("admin/login"));

        mockMvc.perform(post("/admin/login/totp").session(pending).with(csrf()).with(fromIp(ip))
                        .param("code", TotpTestSupport.wrongCode(enrollment.secret(), clock)))
                .andExpect(redirectedUrl("/admin/login/totp?error"));
        // 2단계 오답은 member_totp.failed_attempts에 센다(5회 → 잠금, TotpLoginService) — 서비스까지 도달했다는 증거
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM member_totp WHERE member_id = ?", Integer.class, pendingAdmin.getId()))
                .isEqualTo(1);

        mockMvc.perform(get(ENROLLMENT_PAGE).session(enrolling).with(fromIp(ip)))
                .andExpect(status().isOk()).andExpect(view().name("admin/login-totp-enrollment"));
        TotpEnrollment pendingEnrollment = (TotpEnrollment) enrolling.getAttribute(TotpEnrollment.SESSION_ATTRIBUTE);
        mockMvc.perform(post(ENROLLMENT_PAGE).session(enrolling).with(csrf()).with(fromIp(ip))
                        .param("enrollmentId", pendingEnrollment.enrollmentId())
                        .param("code", TotpCodeVerifier.codeAt(pendingEnrollment.secret(), new TotpCodeVerifier(clock).currentStep()))
                        .param("currentPassword", "wrong-password-value"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/login-totp-enrollment"))
                .andExpect(model().attributeExists("error"));
        assertThat(loginFailures(unenrolledAdmin)).isEqualTo(1);

        // 패스키 단독 로그인은 별도 버킷 — 챌린지 없는 제출은 기존대로 만료 안내
        mockMvc.perform(post("/admin/login/passkey").with(csrf()).with(fromIp(ip)).param("loginId", "x").param("passkeyCredential", "{}"))
                .andExpect(redirectedUrl("/admin/login?passkeyExpired"));

        mockMvc.perform(login("203.0.113.103", "nobody", "wrong")).andExpect(redirectedUrl("/admin/login-error"));
        verify(provider, times(BURST + 1)).authenticate(any());
    }

    @Test
    @DisplayName("F4: 한도를 비운 IP는 경로 변형으로도 인증까지 가지 못한다 — 쿼리는 같은 규칙(429), 끝 슬래시·대소문자는 로그인 처리 밖(302), 비정규 경로는 방화벽 400")
    void pathVariantsDoNotBypass() throws Exception {
        String ip = "203.0.113.4";
        exhaust(ip);

        mockMvc.perform(post("/admin/login?x=1").with(csrf()).with(fromIp(ip)).param("username", "nobody").param("password", "wrong"))
                .andExpect(status().isTooManyRequests());
        mockMvc.perform(post("/admin/login/").with(csrf()).with(fromIp(ip)).param("username", "nobody").param("password", "wrong"))
                .andExpect(redirectedUrl("/admin/login"));
        // URI 객체로 넘긴다 — 문자열이면 MockMvc가 %를 다시 인코딩하고(%252F) "//admin"을 호스트로 읽는다(측정으로 확인)
        for (String variant : List.of("/admin/login;jsessionid=x", "/admin%2Flogin", "/admin//login", "/admin/./login")) {
            mockMvc.perform(post(URI.create(variant)).with(csrf()).with(fromIp(ip)).param("username", "nobody").param("password", "wrong"))
                    .andExpect(status().isBadRequest());
        }
        // 대소문자가 다른 경로는 레이트리밋 규칙과 로그인 처리 매처 둘 다 일치하지 않는다(로그인 화면으로 302)
        mockMvc.perform(post("/ADMIN/login").with(csrf()).with(fromIp(ip)).param("username", "nobody").param("password", "wrong"))
                .andExpect(redirectedUrl("/admin/login"));
        verify(provider, times(BURST)).authenticate(any());
    }
}
