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
import org.junit.jupiter.params.provider.ValueSource;
import com.webauthn4j.verifier.exception.MaliciousCounterValueException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialDescriptor;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 코드 화면의 패스키 분기(PLAN-passkey-second-factor.md 결정 4, F2·F3·F5)를 실제 보안 체인·세션·DB로 검증한다.
 * 공개키 암호 검증만 목({@link PasskeyRelyingParty})으로 바꾼다 — 실제 서명 검증은 브라우저 가상 인증기 시험이 고정한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class TotpPasskeyLoginIntegrationTest extends MariaDbContainerSupport {

    private static final String PASSWORD = "passkey login password";
    private static final String PATH = "/admin/login/totp";

    @MockitoBean PasskeyRelyingParty relyingParty;

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired MemberTotpRepository memberTotpRepository;
    @Autowired MemberTotpRecoveryCodeRepository recoveryCodeRepository;
    @Autowired TotpSecretCipher cipher;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private final List<Long> created = new ArrayList<>();
    private Member member;
    private byte[] handle;
    private byte[] credentialId;
    private byte[] secret;

    @BeforeEach
    void setUp() {
        member = newMember(Role.ROLE_MANAGER);
        secret = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock).secret();
        credentialId = PasskeyTestSupport.insert(jdbc, member.getId(), "노트북",
                PasskeyTestSupport.totpEnabledAt(jdbc, member.getId()).plusSeconds(1));
        handle = PasskeyTestSupport.ensureHandle(jdbc, member.getId());
        given(relyingParty.isEnabled()).willReturn(true);
        given(relyingParty.requestOptions(anyString())).willAnswer(invocation -> options(credentialId));
    }

    @AfterEach
    void cleanUp() {
        for (Long id : created) {
            jdbc.update("DELETE FROM admin_action_log WHERE target_id = ?", id);
        }
        for (Member m : memberRepository.findAllById(created)) {
            jdbc.update("DELETE FROM visit_log WHERE visitor_user_id = ?", m.getUserId());
        }
        TestMembers.delete(jdbc, created);
    }

    private Member newMember(Role role) {
        Member saved = TestMembers.save(memberRepository, "passkey-login", role);
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), saved.getId());
        created.add(saved.getId());
        return memberRepository.findById(saved.getId()).orElseThrow();
    }

    private static PublicKeyCredentialRequestOptions options(byte[] allowed) {
        return PublicKeyCredentialRequestOptions.builder()
                .challenge(Bytes.random())
                .rpId("localhost")
                .allowCredentials(List.of(PublicKeyCredentialDescriptor.builder().id(new Bytes(allowed)).build()))
                .build();
    }

    /** 형식만 맞는 어설션 JSON — 자격 ID만 의미가 있다(서명 검증은 목). */
    private static String assertionJson(byte[] rawId) {
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(rawId);
        return """
                {"id":"%s","rawId":"%s","type":"public-key","clientExtensionResults":{},
                 "response":{"authenticatorData":"AAEC","clientDataJSON":"AAEC","signature":"AAEC"}}""".formatted(id, id);
    }

    private void authenticatorReturns(byte[] userHandle) {
        given(relyingParty.authenticate(any(), any())).willReturn(ImmutablePublicKeyCredentialUserEntity.builder()
                .id(new Bytes(userHandle)).name(member.getUserId()).displayName(member.getUserId()).build());
    }

    private MockHttpSession pendingSession() throws Exception {
        return (MockHttpSession) mockMvc.perform(formLogin("/admin/login").user(member.getUserId()).password(PASSWORD))
                .andExpect(redirectedUrl(PATH))
                .andReturn().getRequest().getSession(false);
    }

    /** 코드 화면 GET → 화면 모델의 assertionId */
    private String openPage(MockHttpSession session) throws Exception {
        return (String) mockMvc.perform(get(PATH).session(session))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("passkeyOptions"))
                .andReturn().getModelAndView().getModel().get("passkeyAssertionId");
    }

    private org.springframework.test.web.servlet.ResultActions submitPasskey(MockHttpSession session, String assertionId, String json)
            throws Exception {
        return mockMvc.perform(post(PATH).session(session).with(csrf())
                .param("passkeyCredential", json).param("assertionId", assertionId));
    }

    private int totpFailures() {
        return jdbc.queryForObject("SELECT failed_attempts FROM member_totp WHERE member_id = ?", Integer.class, member.getId());
    }

    @Test
    @DisplayName("골든 패스: 비밀번호 → 코드 화면(패스키 옵션) → 패스키 제출 → /admin, 실패 횟수 둘 다 0·방문 기록 1건·완전 권한")
    void passkeyCompletesLogin() throws Exception {
        authenticatorReturns(handle);
        MockHttpSession session = pendingSession();
        jdbc.update("UPDATE member SET failed_login_count = 2 WHERE id = ?", member.getId());
        jdbc.update("UPDATE member_totp SET failed_attempts = 4 WHERE member_id = ?", member.getId());
        String assertionId = openPage(session);

        submitPasskey(session, assertionId, assertionJson(credentialId)).andExpect(redirectedUrl("/admin"));

        assertThat(totpFailures()).isZero();
        assertThat(jdbc.queryForObject("SELECT failed_login_count FROM member WHERE id = ?", Integer.class, member.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visit_log WHERE visitor_user_id = ?", Integer.class, member.getUserId())).isEqualTo(1);
        mockMvc.perform(get("/admin/api/members/me/totp").session(session)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("F2: 다른 회원의 핸들을 돌려받으면 통과하지 않고 실패로 센다")
    void otherHandleRejected() throws Exception {
        Member other = newMember(Role.ROLE_MANAGER);
        authenticatorReturns(PasskeyTestSupport.ensureHandle(jdbc, other.getId()));
        MockHttpSession session = pendingSession();

        submitPasskey(session, openPage(session), assertionJson(credentialId)).andExpect(redirectedUrl(PATH + "?passkeyError"));

        assertThat(totpFailures()).isEqualTo(1);
        mockMvc.perform(get("/admin/api/members/me/totp").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("F2(코드 리뷰 2라운드): 응답 userHandle이 다른 회원 것이면 검증기를 부르지 않고 실패로 센다(서명 대상이 아니라 바꿔치기 가능)")
    void responseUserHandleOfOtherMemberRejected() throws Exception {
        Member other = newMember(Role.ROLE_MANAGER);
        byte[] otherHandle = PasskeyTestSupport.ensureHandle(jdbc, other.getId());
        authenticatorReturns(handle);
        MockHttpSession session = pendingSession();
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(credentialId);
        String json = """
                {"id":"%s","rawId":"%s","type":"public-key","clientExtensionResults":{},
                 "response":{"authenticatorData":"AAEC","clientDataJSON":"AAEC","signature":"AAEC","userHandle":"%s"}}"""
                .formatted(id, id, Base64.getUrlEncoder().withoutPadding().encodeToString(otherHandle));

        submitPasskey(session, openPage(session), json).andExpect(redirectedUrl(PATH + "?passkeyError"));

        assertThat(totpFailures()).isEqualTo(1);
        verify(relyingParty, never()).authenticate(any(), any());
        mockMvc.perform(get("/admin/api/members/me/totp").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("F2: 응답 userHandle이 본인 것이면 통과한다(대조가 정상 응답을 막지 않음)")
    void responseUserHandleOfSelfAccepted() throws Exception {
        authenticatorReturns(handle);
        MockHttpSession session = pendingSession();
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(credentialId);
        String json = """
                {"id":"%s","rawId":"%s","type":"public-key","clientExtensionResults":{},
                 "response":{"authenticatorData":"AAEC","clientDataJSON":"AAEC","signature":"AAEC","userHandle":"%s"}}"""
                .formatted(id, id, Base64.getUrlEncoder().withoutPadding().encodeToString(handle));

        submitPasskey(session, openPage(session), json).andExpect(redirectedUrl("/admin"));
    }

    @Test
    @DisplayName("F2: 다른 회원의 자격 ID(같은 핸들을 돌려받아도)는 통과하지 않는다 / 옵션은 그 회원 자격만 담는다")
    void otherCredentialRejected() throws Exception {
        Member other = newMember(Role.ROLE_MANAGER);
        TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, other.getId(), clock);
        byte[] otherCredential = PasskeyTestSupport.insert(jdbc, other.getId(), "남의 것",
                PasskeyTestSupport.totpEnabledAt(jdbc, other.getId()).plusSeconds(1));
        authenticatorReturns(handle);
        MockHttpSession session = pendingSession();

        submitPasskey(session, openPage(session), assertionJson(otherCredential)).andExpect(redirectedUrl(PATH + "?passkeyError"));
        assertThat(totpFailures()).isEqualTo(1);
        verify(relyingParty, org.mockito.Mockito.atLeastOnce()).requestOptions(member.getUserId());
    }

    @Test
    @DisplayName("F3: 같은 어설션 대기로 두 번째 제출 → ?passkeyExpired(카운트 없음, 검증 호출 없음)")
    void assertionIsSingleUse() throws Exception {
        given(relyingParty.authenticate(any(), any())).willThrow(new IllegalArgumentException("bad signature"));
        MockHttpSession session = pendingSession();
        String assertionId = openPage(session);
        submitPasskey(session, assertionId, assertionJson(credentialId)).andExpect(redirectedUrl(PATH + "?passkeyError"));
        assertThat(totpFailures()).isEqualTo(1);

        submitPasskey(session, assertionId, assertionJson(credentialId)).andExpect(redirectedUrl(PATH + "?passkeyExpired"));
        assertThat(totpFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("F3(리뷰 2-1): GET 두 번 뒤 첫 assertionId로 제출 → ?passkeyExpired, failed_attempts 불변·검증 호출 없음")
    void replacedAssertionExpiresWithoutCount() throws Exception {
        authenticatorReturns(handle);
        MockHttpSession session = pendingSession();
        String first = openPage(session);
        String second = openPage(session);
        assertThat(second).isNotEqualTo(first);

        submitPasskey(session, first, assertionJson(credentialId)).andExpect(redirectedUrl(PATH + "?passkeyExpired"));
        assertThat(totpFailures()).isZero();
        verify(relyingParty, never()).authenticate(any(), any());
    }

    @Test
    @DisplayName("F3: 5분이 지난 어설션 대기 → ?passkeyExpired")
    void expiredAssertion() throws Exception {
        authenticatorReturns(handle);
        MockHttpSession session = pendingSession();
        String assertionId = openPage(session);
        PasskeyAssertionState state = (PasskeyAssertionState) session.getAttribute(PasskeyAssertionState.SESSION_ATTRIBUTE);
        session.setAttribute(PasskeyAssertionState.SESSION_ATTRIBUTE, new PasskeyAssertionState(state.assertionId(), state.memberId(),
                state.options(), LocalDateTime.now(clock).minus(PasskeyAssertionState.TTL).minusSeconds(1)));

        submitPasskey(session, assertionId, assertionJson(credentialId)).andExpect(redirectedUrl(PATH + "?passkeyExpired"));
        verify(relyingParty, never()).authenticate(any(), any());
    }

    @Test
    @DisplayName("F5: 검증 실패 5회 → 계정 잠금·세션 종료 / 형식 오류 JSON도 실패로 센다")
    void failuresLockAccount() throws Exception {
        given(relyingParty.authenticate(any(), any())).willThrow(new IllegalArgumentException("bad signature"));
        MockHttpSession session = pendingSession();
        submitPasskey(session, openPage(session), "{broken").andExpect(redirectedUrl(PATH + "?passkeyError"));
        for (int i = 2; i <= 4; i++) {
            submitPasskey(session, openPage(session), assertionJson(credentialId)).andExpect(redirectedUrl(PATH + "?passkeyError"));
            assertThat(totpFailures()).isEqualTo(i);
        }
        submitPasskey(session, openPage(session), assertionJson(credentialId)).andExpect(redirectedUrl("/admin/login-error"));
        assertThat(jdbc.queryForObject("SELECT status FROM member WHERE id = ?", String.class, member.getId())).isEqualTo("LOCKED");
    }

    @Test
    @DisplayName("F5: 기능 꺼짐이면 화면에 패스키 옵션이 없고, 제출은 판정·카운트 없이 만료 처리된다")
    void disabledFeature() throws Exception {
        given(relyingParty.isEnabled()).willReturn(false);
        MockHttpSession session = pendingSession();
        mockMvc.perform(get(PATH).session(session)).andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("passkeyOptions"));

        submitPasskey(session, "anything", assertionJson(credentialId)).andExpect(redirectedUrl(PATH + "?passkeyExpired"));
        assertThat(totpFailures()).isZero();
    }

    @Test
    @DisplayName("결정 1: 현재 TOTP 등록보다 먼저 만든 패스키만 있으면 옵션을 만들지 않는다(버튼 없음)")
    void stalePasskeyNotOffered() throws Exception {
        jdbc.update("UPDATE member_passkey SET created_at = ? WHERE member_id = ?",
                PasskeyTestSupport.totpEnabledAt(jdbc, member.getId()).minusSeconds(1), member.getId());
        MockHttpSession session = pendingSession();
        mockMvc.perform(get(PATH).session(session)).andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("passkeyOptions"));
    }

    @Test
    @DisplayName("기존 코드 입력은 그대로 동작한다(패스키 있는 회원 — 화면에 패스키 옵션이 있어도 코드로 /admin)")
    void codeStillWorks() throws Exception {
        MockHttpSession session = pendingSession();
        openPage(session);
        mockMvc.perform(post(PATH).session(session).with(csrf()).param("code", TotpTestSupport.currentCode(secret, clock)))
                .andExpect(redirectedUrl("/admin"));
        verify(relyingParty, never()).authenticate(any(), any());
    }
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"adapter", "library"})
    @DisplayName("결정 8(PLAN-passkey-login.md): 서명 횟수 역행은 거부하되 실패로 세지 않는다 — 정상 어설션의 도착 순서 역전으로도 생긴다")
    void signatureCountRegressionRejectedWithoutCounting(String source) throws Exception {
        RuntimeException regression = source.equals("adapter")
                ? new PasskeySignatureCountException("역행")
                : new MaliciousCounterValueException("역행", 5, 5);
        given(relyingParty.authenticate(any(), any())).willThrow(regression);
        MockHttpSession session = pendingSession();

        submitPasskey(session, openPage(session), assertionJson(credentialId)).andExpect(redirectedUrl(PATH + "?passkeyError"));

        assertThat(totpFailures()).isZero();
        mockMvc.perform(get("/admin/api/members/me/totp").session(session)).andExpect(status().isUnauthorized());
    }
}
