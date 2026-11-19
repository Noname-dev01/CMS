package com.cms.admin.member.controller;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 패스키 API(PLAN-passkey-second-factor.md 결정 5)의 상태 코드·응답 형식·세션 대기 처리. 실제 공개키 등록 성공은 브라우저 가상 인증기 시험이 고정한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class MemberPasskeyApiIntegrationTest extends MariaDbContainerSupport {

    private static final String PASSWORD = "passkey api password";
    private static final String BASE = "/admin/api/members/me";

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired MemberTotpRepository memberTotpRepository;
    @Autowired MemberTotpRecoveryCodeRepository recoveryCodeRepository;
    @Autowired TotpSecretCipher cipher;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired JsonMapper jsonMapper;

    private final List<Long> created = new ArrayList<>();
    private Member member;

    @BeforeEach
    void setUp() {
        member = newMember(Role.ROLE_MANAGER);
        TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : created) {
            jdbc.update("DELETE FROM admin_action_log WHERE target_id = ?", id);
        }
        TestMembers.delete(jdbc, created);
    }

    private Member newMember(Role role) {
        Member saved = TestMembers.save(memberRepository, "passkey-api", role);
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), saved.getId());
        created.add(saved.getId());
        return memberRepository.findById(saved.getId()).orElseThrow();
    }

    private String registerBody(String registrationId, String password) {
        return """
                {"registrationId":"%s","label":"노트북","currentPassword":"%s",
                 "credential":{"id":"AAEC","rawId":"AAEC","type":"public-key","clientExtensionResults":{},
                   "response":{"attestationObject":"AAEC","clientDataJSON":"AAEC","transports":["internal"]}}}"""
                .formatted(registrationId, password);
    }

    @Test
    @DisplayName("등록 옵션 201: WebAuthn JSON(challenge·rp=localhost·user.id=저장된 핸들·ES256 포함·기존 유효 자격 excludeCredentials)")
    void registrationOptionsShape() throws Exception {
        byte[] existing = PasskeyTestSupport.insert(jdbc, member.getId(), "기존",
                PasskeyTestSupport.totpEnabledAt(jdbc, member.getId()).plusSeconds(1));
        String body = mockMvc.perform(post(BASE + "/passkey-registrations").with(TestMembers.asMember(member)).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.registrationId").isNotEmpty())
                .andExpect(jsonPath("$.options.rp.id").value("localhost"))
                .andExpect(jsonPath("$.options.challenge").isNotEmpty())
                .andExpect(jsonPath("$.options.authenticatorSelection.residentKey").value("required"))
                .andExpect(jsonPath("$.options.pubKeyCredParams[?(@.alg == -7)]").exists())
                .andExpect(jsonPath("$.options.excludeCredentials", hasSize(1)))
                .andReturn().getResponse().getContentAsString();
        JsonNode options = jsonMapper.readTree(body).get("options");
        byte[] handle = jdbc.queryForObject("SELECT user_handle FROM member_passkey_user WHERE member_id = ?", byte[].class, member.getId());
        assertThat(Base64.getUrlDecoder().decode(options.get("user").get("id").asString())).isEqualTo(handle);
        assertThat(Base64.getUrlDecoder().decode(options.get("excludeCredentials").get(0).get("id").asString())).isEqualTo(existing);
    }

    @Test
    @DisplayName("등록 확정: 대기 없이 409, 대기 뒤 검증 실패 400이면 대기가 소비돼 같은 ID 재시도는 409")
    void registerConsumesPending() throws Exception {
        mockMvc.perform(post(BASE + "/passkeys").with(TestMembers.asMember(member)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody("none", PASSWORD)))
                .andExpect(status().isConflict());

        MockHttpSession session = new MockHttpSession();
        String body = mockMvc.perform(post(BASE + "/passkey-registrations").session(session).with(TestMembers.asMember(member)).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String registrationId = jsonMapper.readTree(body).get("registrationId").asString();

        mockMvc.perform(post(BASE + "/passkeys").session(session).with(TestMembers.asMember(member)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody(registrationId, PASSWORD)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE + "/passkeys").session(session).with(TestMembers.asMember(member)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody(registrationId, PASSWORD)))
                .andExpect(status().isConflict());
        assertThat(PasskeyTestSupport.count(jdbc, member.getId())).isZero();
    }

    @Test
    @DisplayName("등록 확정 400: 필수 필드 누락(VALIDATION_ERROR)")
    void registerValidation() throws Exception {
        mockMvc.perform(post(BASE + "/passkeys").with(TestMembers.asMember(member)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"registrationId\":\"x\",\"label\":\"a\",\"currentPassword\":\"p\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("CBOR 본문은 받지 않는다(415) — 전이 의존성이 더한 변환기를 뺐다")
    void cborBodyRejected() throws Exception {
        mockMvc.perform(post(BASE + "/passkeys").with(TestMembers.asMember(member)).with(csrf())
                        .contentType(MediaType.parseMediaType("application/cbor")).content(new byte[]{(byte) 0xa0}))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("목록·삭제: 유효한 것만 목록에, 삭제 204 뒤 다시 404 / TOTP 미사용 회원은 totpEnabled=false·등록 옵션 409")
    void listAndDelete() throws Exception {
        PasskeyTestSupport.insert(jdbc, member.getId(), "노트북", PasskeyTestSupport.totpEnabledAt(jdbc, member.getId()).plusSeconds(1));
        PasskeyTestSupport.insert(jdbc, member.getId(), "옛것", PasskeyTestSupport.totpEnabledAt(jdbc, member.getId()).minusSeconds(1));
        mockMvc.perform(get(BASE + "/passkeys").with(TestMembers.asMember(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.totpEnabled").value(true))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].label").value("노트북"));
        Long id = jdbc.queryForObject("SELECT id FROM member_passkey WHERE member_id = ? AND label = '노트북'", Long.class, member.getId());

        mockMvc.perform(delete(BASE + "/passkeys/" + id).with(TestMembers.asMember(member)).with(csrf())).andExpect(status().isNoContent());
        mockMvc.perform(delete(BASE + "/passkeys/" + id).with(TestMembers.asMember(member)).with(csrf())).andExpect(status().isNotFound());

        Member noTotp = newMember(Role.ROLE_MANAGER);
        mockMvc.perform(get(BASE + "/passkeys").with(TestMembers.asMember(noTotp)))
                .andExpect(jsonPath("$.totpEnabled").value(false));
        mockMvc.perform(post(BASE + "/passkey-registrations").with(TestMembers.asMember(noTotp)).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("인가: 미인증 401, CSRF 없는 POST 403, ADMIN의 타인 TOTP 상태에 passkeyCount")
    void authorization() throws Exception {
        mockMvc.perform(get(BASE + "/passkeys")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/passkey-registrations").with(TestMembers.asMember(member))).andExpect(status().isForbidden());

        PasskeyTestSupport.insert(jdbc, member.getId(), "노트북", PasskeyTestSupport.totpEnabledAt(jdbc, member.getId()).plusSeconds(1));
        Member admin = newMember(Role.ROLE_ADMIN);
        mockMvc.perform(get("/admin/api/members/" + member.getId() + "/totp").with(TestMembers.asMember(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passkeyCount").value(1));
    }
}
