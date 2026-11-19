package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.passkey.PasskeyRelyingParty;
import com.cms.admin.member.passkey.PasskeySignatureCountException;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.repository.MemberTotpRecoveryCodeRepository;
import com.cms.admin.member.repository.MemberTotpRepository;
import com.cms.admin.member.totp.TotpSecretCipher;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.PasskeyTestSupport;
import com.cms.support.TestMembers;
import com.cms.support.TotpTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 패스키 단독 로그인(PLAN-passkey-login.md, 이슈 #280) — 실제 보안 체인·실 DB, 서명 검증(라이브러리)만 목.
 * 실제 서명·UV 검증은 {@code PasskeyBrowserTest}가 가상 인증기로 고정한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PasskeyLoginIntegrationTest extends MariaDbContainerSupport {

    private static final String PASSWORD = "passkey only login password";
    private static final String LOGIN = "/admin/login";
    private static final String PATH = "/admin/login/passkey";
    private static final String ERROR = LOGIN + "?passkeyError";
    private static final String EXPIRED = LOGIN + "?passkeyExpired";

    @MockitoBean PasskeyRelyingParty relyingParty;
    @MockitoSpyBean PasskeyLoginService passkeyLoginService;

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired MemberTotpRepository memberTotpRepository;
    @Autowired MemberTotpRecoveryCodeRepository recoveryCodeRepository;
    @Autowired TotpSecretCipher cipher;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired SessionRegistry sessionRegistry;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private final List<Long> created = new ArrayList<>();
    private Member member;
    private byte[] handle;
    private byte[] credentialId;

    @BeforeEach
    void setUp() {
        member = newMemberWithPasskey(Role.ROLE_MANAGER);
        handle = PasskeyTestSupport.ensureHandle(jdbc, member.getId());
        credentialId = jdbc.queryForObject("SELECT credential_id FROM member_passkey WHERE member_id = ?", byte[].class, member.getId());
        given(relyingParty.isEnabled()).willReturn(true);
        given(relyingParty.discoverableRequestOptions()).willAnswer(invocation -> PublicKeyCredentialRequestOptions.builder()
                .challenge(Bytes.random()).rpId("localhost").userVerification(UserVerificationRequirement.REQUIRED)
                .allowCredentials(List.of()).build());
        authenticatorReturns(handle);
    }

    @AfterEach
    void cleanUp() {
        for (Member m : memberRepository.findAllById(created)) {
            jdbc.update("DELETE FROM visit_log WHERE visitor_user_id = ?", m.getUserId());
            jdbc.update("DELETE FROM admin_action_log WHERE target_id = ?", m.getId());
        }
        TestMembers.delete(jdbc, created);
    }

    private Member newMemberWithPasskey(Role role) {
        Member saved = TestMembers.save(memberRepository, "pk-login", role);
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), saved.getId());
        created.add(saved.getId());
        TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, saved.getId(), clock);
        PasskeyTestSupport.insert(jdbc, saved.getId(), "노트북", PasskeyTestSupport.totpEnabledAt(jdbc, saved.getId()).plusSeconds(1));
        return memberRepository.findById(saved.getId()).orElseThrow();
    }

    private void authenticatorReturns(byte[] userHandle) {
        given(relyingParty.authenticate(any(), any())).willReturn(ImmutablePublicKeyCredentialUserEntity.builder()
                .id(new Bytes(userHandle)).name("x").displayName("x").build());
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 형식만 맞는 어설션 JSON(서명 검증은 목) — discoverable 로그인이라 응답 userHandle이 있다. */
    private static String assertionJson(byte[] rawId, byte[] userHandle) {
        String handlePart = userHandle == null ? "" : ",\"userHandle\":\"" + b64(userHandle) + "\"";
        return """
                {"id":"%s","rawId":"%s","type":"public-key","clientExtensionResults":{},
                 "response":{"authenticatorData":"AAEC","clientDataJSON":"AAEC","signature":"AAEC"%s}}"""
                .formatted(b64(rawId), b64(rawId), handlePart);
    }

    private String json() {
        return assertionJson(credentialId, handle);
    }

    /** 로그인 화면 GET → 화면 모델의 loginId(세션에 챌린지가 생긴다) */
    private String openLoginPage(MockHttpSession session) throws Exception {
        return (String) mockMvc.perform(get(LOGIN).session(session))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("passkeyOptions"))
                .andReturn().getModelAndView().getModel().get("passkeyLoginId");
    }

    private ResultActions submit(MockHttpSession session, String loginId, String json) throws Exception {
        return mockMvc.perform(post(PATH).session(session).with(csrf()).param("loginId", loginId).param("passkeyCredential", json));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class, member.getId());
    }

    private int failedLoginCount() {
        return count("SELECT failed_login_count FROM member WHERE id = ?");
    }

    private int totpFailures() {
        return count("SELECT failed_attempts FROM member_totp WHERE member_id = ?");
    }

    private void assertNotLoggedIn(MockHttpSession session) throws Exception {
        mockMvc.perform(get("/admin/api/members/me").session(session)).andExpect(status().isUnauthorized());
    }

    // ==================== 골든 패스·세션 계약(F1) ====================

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Role.class, names = {"ROLE_MANAGER", "ROLE_ADMIN"})
    @DisplayName("F1: 로그인 화면 → 패스키 제출 → /admin. 세션 ID 교체·레지스트리 등록(그 회원)·방문 1건·실패 횟수 둘 다 0·완전 권한, 넘긴 옵션은 세션의 것")
    void passkeyLoginCompletes(Role role) throws Exception {
        if (role == Role.ROLE_ADMIN) {
            member = newMemberWithPasskey(Role.ROLE_ADMIN);
            handle = PasskeyTestSupport.ensureHandle(jdbc, member.getId());
            credentialId = jdbc.queryForObject("SELECT credential_id FROM member_passkey WHERE member_id = ?", byte[].class, member.getId());
            authenticatorReturns(handle);
        }
        jdbc.update("UPDATE member SET failed_login_count = 2 WHERE id = ?", member.getId());
        jdbc.update("UPDATE member_totp SET failed_attempts = 3 WHERE member_id = ?", member.getId());
        MockHttpSession session = new MockHttpSession();
        String loginId = openLoginPage(session);
        String before = session.getId();
        PasskeyLoginState state = (PasskeyLoginState) session.getAttribute(PasskeyLoginState.SESSION_ATTRIBUTE);

        MvcResult result = submit(session, loginId, json()).andExpect(redirectedUrl("/admin")).andReturn();

        String after = result.getRequest().getSession(false).getId();
        assertThat(after).isNotEqualTo(before);
        SessionInformation info = sessionRegistry.getSessionInformation(after);
        assertThat(info).isNotNull();
        assertThat(((CustomUserDetails) info.getPrincipal()).getId()).isEqualTo(member.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visit_log WHERE visitor_user_id = ?", Integer.class, member.getUserId())).isEqualTo(1);
        assertThat(failedLoginCount()).isZero();
        assertThat(totpFailures()).isZero();
        assertThat(session.getAttribute(PasskeyLoginState.SESSION_ATTRIBUTE)).isNull();
        mockMvc.perform(get("/admin/api/members/me").session(session)).andExpect(status().isOk());
        ArgumentCaptor<PublicKeyCredentialRequestOptions> options = ArgumentCaptor.forClass(PublicKeyCredentialRequestOptions.class);
        verify(relyingParty).authenticate(options.capture(), any());
        assertThat(options.getValue()).isSameAs(state.options());
    }

    // ==================== 챌린지(F3) ====================

    @Test
    @DisplayName("F3: 챌린지 없이(화면을 열지 않음) 제출 → passkeyExpired, 검증 없음")
    void noChallengeExpired() throws Exception {
        MockHttpSession session = new MockHttpSession();
        submit(session, "anything", json()).andExpect(redirectedUrl(EXPIRED));
        verify(relyingParty, never()).authenticate(any(), any());
        assertNotLoggedIn(session);
    }

    @Test
    @DisplayName("F3: 화면을 두 번 열면(다른 탭) 첫 loginId는 만료 — 검증 없음")
    void replacedChallengeExpired() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String first = openLoginPage(session);
        openLoginPage(session);

        submit(session, first, json()).andExpect(redirectedUrl(EXPIRED));
        verify(relyingParty, never()).authenticate(any(), any());
    }

    @Test
    @DisplayName("F3: 발급 5분 초과 → passkeyExpired, 검증 없음")
    void staleChallengeExpired() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String loginId = openLoginPage(session);
        PasskeyLoginState state = (PasskeyLoginState) session.getAttribute(PasskeyLoginState.SESSION_ATTRIBUTE);
        session.setAttribute(PasskeyLoginState.SESSION_ATTRIBUTE,
                new PasskeyLoginState(loginId, state.options(), LocalDateTime.now(clock).minusMinutes(5).minusSeconds(1)));

        submit(session, loginId, json()).andExpect(redirectedUrl(EXPIRED));
        verify(relyingParty, never()).authenticate(any(), any());
    }

    @Test
    @DisplayName("F3: 챌린지는 1회용 — 실패한 제출 뒤 같은 loginId 재제출은 passkeyExpired")
    void challengeSingleUse() throws Exception {
        given(relyingParty.authenticate(any(), any())).willThrow(new IllegalArgumentException("서명 불일치"));
        MockHttpSession session = new MockHttpSession();
        String loginId = openLoginPage(session);
        submit(session, loginId, json()).andExpect(redirectedUrl(ERROR));
        reset(relyingParty);
        given(relyingParty.isEnabled()).willReturn(true);
        authenticatorReturns(handle);

        submit(session, loginId, json()).andExpect(redirectedUrl(EXPIRED));
        verify(relyingParty, never()).authenticate(any(), any());
    }

    // ==================== 로그인할 수 없는 회원·자격(F2) ====================

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LOCKED", "DISABLED", "PASSWORD_EXPIRED"})
    @DisplayName("F2: 잠김(만료 전)·비활성·변경 강제 → passkeyError, 인증 없음, 실패 횟수 불변")
    void unusableMemberRejected(String memberStatus) throws Exception {
        jdbc.update("UPDATE member SET status = ?, locked_at = ?, failed_login_count = 1 WHERE id = ?",
                memberStatus, memberStatus.equals("LOCKED") ? LocalDateTime.now(clock) : null, member.getId());
        MockHttpSession session = new MockHttpSession();

        submit(session, openLoginPage(session), json()).andExpect(redirectedUrl(ERROR));

        assertNotLoggedIn(session);
        assertThat(failedLoginCount()).isEqualTo(1);
        assertThat(totpFailures()).isZero();
    }

    @Test
    @DisplayName("F2: 30분 지난 자동 잠금은 폼 로그인과 같이 자동 해제되고 로그인된다")
    void expiredAutoLockUnlocked() throws Exception {
        jdbc.update("UPDATE member SET status = 'LOCKED', failed_login_count = 5, locked_at = ? WHERE id = ?",
                LocalDateTime.now(clock).minusMinutes(31), member.getId());
        MockHttpSession session = new MockHttpSession();

        submit(session, openLoginPage(session), json()).andExpect(redirectedUrl("/admin"));

        assertThat(jdbc.queryForObject("SELECT status FROM member WHERE id = ?", String.class, member.getId())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("F2: TOTP가 없거나 패스키보다 늦게 다시 등록됐으면(옛 패스키) 거부")
    void stalePasskeyRejected() throws Exception {
        jdbc.update("UPDATE member_totp SET enabled_at = ? WHERE member_id = ?", LocalDateTime.now(clock).plusMinutes(1), member.getId());
        MockHttpSession session = new MockHttpSession();
        submit(session, openLoginPage(session), json()).andExpect(redirectedUrl(ERROR));
        assertNotLoggedIn(session);

        jdbc.update("DELETE FROM member_totp WHERE member_id = ?", member.getId());
        submit(session, openLoginPage(session), json()).andExpect(redirectedUrl(ERROR));
        assertNotLoggedIn(session);
    }

    @Test
    @DisplayName("F2: 응답 userHandle이 없거나 다른 회원 것이면 검증기를 부르지 않고 거부")
    void userHandleMissingOrForeignRejected() throws Exception {
        Member other = newMemberWithPasskey(Role.ROLE_MANAGER);
        byte[] otherHandle = PasskeyTestSupport.ensureHandle(jdbc, other.getId());
        MockHttpSession session = new MockHttpSession();

        submit(session, openLoginPage(session), assertionJson(credentialId, null)).andExpect(redirectedUrl(ERROR));
        submit(session, openLoginPage(session), assertionJson(credentialId, otherHandle)).andExpect(redirectedUrl(ERROR));

        verify(relyingParty, never()).authenticate(any(), any());
        assertNotLoggedIn(session);
    }

    @Test
    @DisplayName("F2: 검증기가 다른 회원의 핸들을 돌려주면 거부")
    void foreignEntityRejected() throws Exception {
        Member other = newMemberWithPasskey(Role.ROLE_MANAGER);
        authenticatorReturns(PasskeyTestSupport.ensureHandle(jdbc, other.getId()));
        MockHttpSession session = new MockHttpSession();

        submit(session, openLoginPage(session), json()).andExpect(redirectedUrl(ERROR));
        assertNotLoggedIn(session);
    }

    @Test
    @DisplayName("F2: 등록되지 않은 자격·형식 오류 JSON·필수 필드 누락 → 500이 아니라 passkeyError")
    void unknownOrMalformedRejected() throws Exception {
        MockHttpSession session = new MockHttpSession();
        submit(session, openLoginPage(session), assertionJson(new byte[]{1, 2, 3, 4}, handle)).andExpect(redirectedUrl(ERROR));
        submit(session, openLoginPage(session), "{not json").andExpect(redirectedUrl(ERROR));
        submit(session, openLoginPage(session), "{\"id\":\"AAEC\",\"type\":\"public-key\"}").andExpect(redirectedUrl(ERROR));
        submit(session, openLoginPage(session), "").andExpect(redirectedUrl(ERROR));
        assertNotLoggedIn(session);
    }

    @Test
    @DisplayName("결정 5: 검증 실패(서명 불일치)는 실패 횟수를 세지 않는다")
    void verificationFailureNotCounted() throws Exception {
        given(relyingParty.authenticate(any(), any())).willThrow(new IllegalArgumentException("서명 불일치"));
        MockHttpSession session = new MockHttpSession();

        submit(session, openLoginPage(session), json()).andExpect(redirectedUrl(ERROR));

        assertThat(failedLoginCount()).isZero();
        assertThat(totpFailures()).isZero();
        assertNotLoggedIn(session);
    }

    @Test
    @DisplayName("결정 8: 서명 횟수 역행 → 거부, 실패 횟수 불변")
    void counterRegressionRejected() throws Exception {
        given(relyingParty.authenticate(any(), any())).willThrow(new PasskeySignatureCountException("역행"));
        MockHttpSession session = new MockHttpSession();

        submit(session, openLoginPage(session), json()).andExpect(redirectedUrl(ERROR));

        assertThat(totpFailures()).isZero();
        assertNotLoggedIn(session);
    }

    @Test
    @DisplayName("리뷰 1-3: 검증 단계의 DB 오류도 500이 아니라 passkeyError")
    void infrastructureFailureNormalized() throws Exception {
        doThrow(new QueryTimeoutException("DB")).when(passkeyLoginService).verify(any(), any(), any());
        MockHttpSession session = new MockHttpSession();

        submit(session, openLoginPage(session), json()).andExpect(redirectedUrl(ERROR));
        assertNotLoggedIn(session);
    }

    @Test
    @DisplayName("F2(리뷰 1-1): 식별 뒤·검증 잠금 전에 다른 요청이 TOTP 사용 스텝을 올려 커밋해도 성공 처리가 그 값을 되돌리지 않는다")
    void verifyReadsFreshTotpAfterLock() throws Exception {
        jdbc.update("UPDATE member_totp SET failed_attempts = 3 WHERE member_id = ?", member.getId());
        doAnswer(invocation -> {
            Object identified = invocation.callRealMethod();
            // 다른 연결(다른 스레드)의 자동 커밋 — 스파이는 트랜잭션 프록시 안쪽이라 같은 스레드에서 쓰면 식별의 읽기 전용 트랜잭션에 묶인다
            Thread other = new Thread(() -> jdbc.update("UPDATE member_totp SET last_used_step = 999999999 WHERE member_id = ?", member.getId()));
            other.start();
            other.join();
            return identified;
        }).when(passkeyLoginService).identify(any());
        MockHttpSession session = new MockHttpSession();

        submit(session, openLoginPage(session), json()).andExpect(redirectedUrl("/admin"));

        assertThat(jdbc.queryForObject("SELECT last_used_step FROM member_totp WHERE member_id = ?", Long.class, member.getId()))
                .isEqualTo(999999999L);
        assertThat(totpFailures()).isZero();
    }

    // ==================== 이미 인증된 세션(F5, 리뷰 1-4) ====================

    @Test
    @DisplayName("F5: 완전 인증 세션에서 실패하면 기존 인증이 유지된다(폼 로그인 실패와 같음)")
    void failureKeepsFullAuthentication() throws Exception {
        Member plain = TestMembers.save(memberRepository, "pk-plain", Role.ROLE_MANAGER);
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), plain.getId());
        created.add(plain.getId());
        MockHttpSession session = (MockHttpSession) mockMvc.perform(formLogin(LOGIN).user(plain.getUserId()).password(PASSWORD))
                .andExpect(redirectedUrl("/admin")).andReturn().getRequest().getSession(false);
        given(relyingParty.authenticate(any(), any())).willThrow(new IllegalArgumentException("서명 불일치"));

        submit(session, openLoginPage(session), json()).andExpect(redirectedUrl(ERROR));

        mockMvc.perform(get("/admin/api/members/me").session(session)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("F5: 코드 대기(TOTP_PENDING) 세션 — 실패하면 대기 유지, 성공하면 세션 ID 교체·완전 권한·대기 표지 없음")
    void fromPendingSession() throws Exception {
        MockHttpSession session = (MockHttpSession) mockMvc.perform(formLogin(LOGIN).user(member.getUserId()).password(PASSWORD))
                .andExpect(redirectedUrl("/admin/login/totp")).andReturn().getRequest().getSession(false);
        given(relyingParty.authenticate(any(), any())).willThrow(new IllegalArgumentException("서명 불일치"));
        submit(session, openLoginPage(session), json()).andExpect(redirectedUrl(ERROR));
        mockMvc.perform(get("/admin/login/totp").session(session)).andExpect(status().isOk());

        reset(relyingParty);
        given(relyingParty.isEnabled()).willReturn(true);
        given(relyingParty.discoverableRequestOptions()).willAnswer(invocation -> PublicKeyCredentialRequestOptions.builder()
                .challenge(Bytes.random()).rpId("localhost").userVerification(UserVerificationRequirement.REQUIRED)
                .allowCredentials(List.of()).build());
        authenticatorReturns(handle);
        String before = session.getId();
        MvcResult result = submit(session, openLoginPage(session), json()).andExpect(redirectedUrl("/admin")).andReturn();

        assertThat(result.getRequest().getSession(false).getId()).isNotEqualTo(before);
        assertThat(session.getAttribute(TotpPendingState.SESSION_ATTRIBUTE)).isNull();
        mockMvc.perform(get("/admin/api/members/me").session(session)).andExpect(status().isOk());
    }

    // ==================== 기능 꺼짐 ====================

    @Test
    @DisplayName("기능 꺼짐: 로그인 화면에 패스키 옵션이 없고 POST는 인증되지 않는다(로그인 302)")
    void disabled() throws Exception {
        given(relyingParty.isEnabled()).willReturn(false);
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get(LOGIN).session(session)).andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("passkeyOptions"));
        submit(session, "x", json()).andExpect(redirectedUrl("/admin/login"));

        verify(relyingParty, never()).authenticate(any(), any());
        assertNotLoggedIn(session);
    }
}
