package com.cms.admin.member.controller;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.repository.MemberTotpRecoveryCodeRepository;
import com.cms.admin.member.repository.MemberTotpRepository;
import com.cms.admin.member.service.MemberTotpService;
import com.cms.admin.member.totp.Base32;
import com.cms.admin.member.totp.TotpCodeVerifier;
import com.cms.admin.member.totp.TotpSecretCipher;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.QrTestSupport;
import com.cms.support.TestMembers;
import com.cms.support.TotpTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2단계 인증 등록·해제 API(PLAN-totp-two-factor.md 결정 4). 실제 폼 로그인 세션(레지스트리 등록됨)을 써서 등록 후 본인 세션 만료까지 본다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class MemberTotpApiIntegrationTest extends MariaDbContainerSupport {

    private static final String PASSWORD = "totp-api-password-123";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired MemberTotpRepository memberTotpRepository;
    @Autowired MemberTotpRecoveryCodeRepository recoveryCodeRepository;
    @Autowired TotpSecretCipher cipher;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @MockitoSpyBean MemberTotpService memberTotpService;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<String> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String userId : userIds) {
            jdbc.update("DELETE FROM visit_log WHERE visitor_user_id = ?", userId);
        }
        for (Long id : memberIds) {
            jdbc.update("DELETE FROM admin_action_log WHERE target_id = ? AND target_type = 'MEMBER'", id);
        }
        TestMembers.delete(jdbc, memberIds);
    }

    private Member member(Role role) {
        Member saved = TestMembers.save(memberRepository, "totp-api", role);
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), saved.getId());
        memberIds.add(saved.getId());
        userIds.add(saved.getUserId());
        return memberRepository.findById(saved.getId()).orElseThrow();
    }

    private MockHttpSession login(Member member) throws Exception {
        if (member.getUserType() == Role.ROLE_ADMIN) {
            // ADMIN은 2단계가 필수다 — 미등록이면 강제 등록 상태라 등록해 두고 두 단계로 로그인한다(PLAN-totp-required-admin.md)
            byte[] secret = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock).secret();
            return TotpTestSupport.loginWithSecondFactor(mockMvc, jdbc, clock, member.getUserId(), PASSWORD, member.getId(), secret);
        }
        MvcResult result = mockMvc.perform(formLogin("/admin/login").user(member.getUserId()).password(PASSWORD))
                .andExpect(status().is3xxRedirection()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private JsonNode startEnrollment(MockHttpSession session) throws Exception {
        String body = mockMvc.perform(post("/admin/api/members/me/totp-enrollments").session(session).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    private int confirm(MockHttpSession session, String enrollmentId, String code) throws Exception {
        return mockMvc.perform(put("/admin/api/members/me/totp").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(java.util.Map.of("enrollmentId", enrollmentId, "code", code, "currentPassword", PASSWORD))))
                .andReturn().getResponse().getStatus();
    }

    private static String currentCode(String base32Secret, Clock clock) {
        return TotpCodeVerifier.codeAt(Base32.decode(base32Secret), new TotpCodeVerifier(clock).currentStep());
    }

    @Test
    @DisplayName("S6: 상태(미사용) → 대기 201(QR은 같은 otpauth URI로 디코드) → 확정 200(복구 코드 10개) → 본인 세션은 다음 요청부터 401 → 재로그인은 2단계 화면")
    void enrollmentRoundTrip() throws Exception {
        Member manager = member(Role.ROLE_MANAGER);
        MockHttpSession session = login(manager);
        mockMvc.perform(get("/admin/api/members/me/totp").session(session))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.enabled").value(false));

        JsonNode pending = startEnrollment(session);
        String uri = pending.get("otpauthUri").asText();
        assertThat(uri).startsWith("otpauth://totp/CMS:").contains("secret=" + pending.get("secret").asText());
        assertThat(QrTestSupport.decode(Base64.getDecoder().decode(pending.get("qrPngBase64").asText()))).isEqualTo(uri);

        String body = mockMvc.perform(put("/admin/api/members/me/totp").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(java.util.Map.of("enrollmentId", pending.get("enrollmentId").asText(),
                                "code", currentCode(pending.get("secret").asText(), clock), "currentPassword", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(body).get("recoveryCodes")).hasSize(10);

        mockMvc.perform(get("/admin/api/members/me/totp").session(session)).andExpect(status().isUnauthorized());
        mockMvc.perform(formLogin("/admin/login").user(manager.getUserId()).password(PASSWORD))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/admin/login/totp"));
    }

    @Test
    @DisplayName("다른 탭이 새 대기를 발급하면 옛 enrollmentId 확정은 409, 새 것은 200. 오답은 400")
    void staleEnrollmentConflicts() throws Exception {
        Member manager = member(Role.ROLE_MANAGER);
        MockHttpSession session = login(manager);
        JsonNode first = startEnrollment(session);
        JsonNode second = startEnrollment(session);

        assertThat(confirm(session, first.get("enrollmentId").asText(), currentCode(first.get("secret").asText(), clock))).isEqualTo(409);
        assertThat(confirm(session, second.get("enrollmentId").asText(),
                TotpTestSupport.wrongCode(Base32.decode(second.get("secret").asText()), clock))).isEqualTo(400);
        assertThat(confirm(session, second.get("enrollmentId").asText(), currentCode(second.get("secret").asText(), clock))).isEqualTo(200);
    }

    @Test
    @DisplayName("본인 해제 204, 사용 중 아니면 409. ADMIN 해제는 MANAGER 403·ADMIN 204, 대상 상태 조회도 ADMIN 전용")
    void disableAndAdminReset() throws Exception {
        Member manager = member(Role.ROLE_MANAGER);
        Member admin = member(Role.ROLE_ADMIN);
        TotpTestSupport.Enrollment enrollment = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, manager.getId(), clock);

        MockHttpSession adminSession = login(admin);
        mockMvc.perform(get("/admin/api/members/" + manager.getId() + "/totp").session(adminSession))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.enabled").value(true));
        mockMvc.perform(delete("/admin/api/members/" + manager.getId() + "/totp").session(adminSession).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(memberTotpRepository.existsById(manager.getId())).isFalse();

        TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, manager.getId(), clock);
        Member otherManager = member(Role.ROLE_MANAGER);
        MockHttpSession managerSession = login(otherManager);
        mockMvc.perform(delete("/admin/api/members/" + manager.getId() + "/totp").session(managerSession).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/api/members/" + manager.getId() + "/totp").session(managerSession))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/admin/api/members/me/totp").session(managerSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\",\"currentPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isConflict());
        assertThat(enrollment.recoveryCodes()).hasSize(10);
    }

    private org.springframework.test.web.servlet.ResultActions reissue(MockHttpSession session, String code) throws Exception {
        return mockMvc.perform(post("/admin/api/members/me/totp/recovery-codes").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(java.util.Map.of("code", code, "currentPassword", PASSWORD))));
    }

    @Test
    @DisplayName("재발급(PLAN-totp-recovery-code-reissue.md): 200 + 새 코드 10개·no-store, 세션 유지(다음 요청 200), 남은 개수 10. 같은 코드 재사용은 400 '이미 사용한 코드', 미사용은 409")
    void reissueRecoveryCodes() throws Exception {
        Member manager = member(Role.ROLE_MANAGER);
        MockHttpSession session = login(manager); // 등록 전에 로그인 — 세션이 2단계 중간 상태가 아니게
        TotpTestSupport.Enrollment enrollment = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, manager.getId(), clock);
        jdbc.update("UPDATE member_totp_recovery_code SET used_at = NOW(6) WHERE member_id = ? ORDER BY id LIMIT 3", manager.getId());
        String code = TotpTestSupport.currentCode(enrollment.secret(), clock);

        MvcResult result = reissue(session, code)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        JsonNode codes = JSON.readTree(result.getResponse().getContentAsString()).get("recoveryCodes");
        assertThat(codes).hasSize(10);
        assertThat(codes.get(0).asText()).doesNotMatch(".*" + enrollment.recoveryCodes().get(0) + ".*");

        mockMvc.perform(get("/admin/api/members/me/totp").session(session))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.remainingRecoveryCodes").value(10));

        reissue(session, code)
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("이미 사용한 코드")));

        Member other = member(Role.ROLE_MANAGER);
        reissue(login(other), "123456").andExpect(status().isConflict());
    }

    @Test
    @DisplayName("재발급 경계: 미인증 401, CSRF 없음 403, 빈 본문 400 VALIDATION_ERROR")
    void reissueBoundaries() throws Exception {
        mockMvc.perform(post("/admin/api/members/me/totp/recovery-codes").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\",\"currentPassword\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        Member manager = member(Role.ROLE_MANAGER);
        MockHttpSession session = login(manager);
        mockMvc.perform(post("/admin/api/members/me/totp/recovery-codes").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\",\"currentPassword\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/api/members/me/totp/recovery-codes").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("요청 본문 검증: 빈 코드·비밀번호는 400 VALIDATION_ERROR")
    void validation() throws Exception {
        Member manager = member(Role.ROLE_MANAGER);
        MockHttpSession session = login(manager);
        mockMvc.perform(put("/admin/api/members/me/totp").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enrollmentId\":\"x\",\"code\":\"\",\"currentPassword\":\"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/admin/api/members/me/totp").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("v4: 확정이 대기 검사를 지난 뒤 진행 중이면, 같은 세션의 새 대기 발급은 세션 뮤텍스에서 기다린다 — '새 QR 발급'과 '옛 비밀값 등록 성공'이 함께 일어나지 않는다")
    void enrollmentIsSerializedPerSession() throws Exception {
        Member manager = member(Role.ROLE_MANAGER);
        MockHttpSession session = login(manager);
        JsonNode first = startEnrollment(session);
        CountDownLatch inConfirm = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            inConfirm.countDown();
            if (!release.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("해제 신호 시간 초과");
            }
            return invocation.callRealMethod();
        }).when(memberTotpService).confirmEnrollment(any(), any(), any(), any(), any(), any(), any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> confirmA = executor.submit(() ->
                    confirm(session, first.get("enrollmentId").asText(), currentCode(first.get("secret").asText(), clock)));
            assertThat(inConfirm.await(15, TimeUnit.SECONDS)).isTrue();
            Future<Integer> startB = executor.submit(() -> mockMvc.perform(post("/admin/api/members/me/totp-enrollments")
                    .session(session).with(csrf())).andReturn().getResponse().getStatus());
            Thread.sleep(300); // 부정 단언 전 여유 — B가 뮤텍스 밖이면 이 사이에 끝난다
            assertThat(startB.isDone()).as("B는 A가 끝날 때까지 세션 뮤텍스에서 기다린다").isFalse();

            release.countDown();
            assertThat(confirmA.get(15, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(startB.get(15, TimeUnit.SECONDS)).as("A의 등록이 끝난 뒤라 B는 '이미 사용 중'").isEqualTo(409);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
