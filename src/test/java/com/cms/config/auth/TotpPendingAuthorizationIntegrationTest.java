package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.repository.MemberTotpRecoveryCodeRepository;
import com.cms.admin.member.repository.MemberTotpRepository;
import com.cms.admin.member.totp.TotpSecretCipher;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import com.cms.support.TotpTestSupport;
import com.cms.support.TotpTestSupport.Enrollment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FM1(PLAN-totp-two-factor.md 결정 1·2): 실제 보안 체인에서 2단계를 켠 회원의 비밀번호 로그인은 역할 권한이 없는 중간 상태만 얻고,
 * 코드 화면 외의 관리 경로는 모두 막히며, 올바른 코드를 낸 뒤에만 완전 권한이 된다. 미등록 회원은 기존 흐름 그대로다.
 * 전역 인증 관리자가 {@code TotpAwareAuthenticationProvider}를 실제로 쓰는지도 여기서 드러난다(AuthenticationProvider 빈이 둘이 되면 실패).
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class TotpPendingAuthorizationIntegrationTest extends MariaDbContainerSupport {

    private static final String PASSWORD = "pending-authorization-password";

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired MemberTotpRepository memberTotpRepository;
    @Autowired MemberTotpRecoveryCodeRepository recoveryCodeRepository;
    @Autowired TotpSecretCipher cipher;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<String> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String userId : userIds) {
            jdbc.update("DELETE FROM visit_log WHERE visitor_user_id = ?", userId);
        }
        for (Long id : memberIds) {
            jdbc.update("DELETE FROM admin_action_log WHERE target_id = ? AND action_type = 'ACCOUNT_AUTO_LOCK'", id);
        }
        TestMembers.delete(jdbc, memberIds);
    }

    private Member member(Role role) {
        Member saved = TestMembers.save(memberRepository, "totp-pending", role);
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), saved.getId());
        memberIds.add(saved.getId());
        userIds.add(saved.getUserId());
        return memberRepository.findById(saved.getId()).orElseThrow();
    }

    private Enrollment enroll(Member member) {
        return TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock);
    }

    private MockHttpSession passwordLogin(Member member, String expectedRedirect) throws Exception {
        MvcResult result = mockMvc.perform(formLogin("/admin/login").user(member.getUserId()).password(PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(expectedRedirect))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static List<String> authorities(MockHttpSession session) {
        SecurityContext context = (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return context.getAuthentication().getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    private int visits(Member member) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM visit_log WHERE visitor_user_id = ?", Integer.class, member.getUserId());
    }

    @Test
    @DisplayName("비밀번호만 통과한 세션은 권한이 {TOTP_PENDING}뿐이고, 관리 화면은 코드 화면으로 302·API는 401, 코드 화면만 200 — 방문 기록 없음")
    void pendingSessionIsConfinedToCodePage() throws Exception {
        Member admin = member(Role.ROLE_ADMIN);
        enroll(admin);

        MockHttpSession session = passwordLogin(admin, "/admin/login/totp");

        assertThat(authorities(session)).containsExactly(TotpAuthorities.PENDING);
        mockMvc.perform(get("/admin").session(session)).andExpect(redirectedUrl("/admin/login/totp"));
        mockMvc.perform(get("/admin/permission/manage").session(session)).andExpect(redirectedUrl("/admin/login/totp"));
        mockMvc.perform(get("/admin/api/members/me").session(session)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/api/members").session(session)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/api/banners").session(session)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/login/totp").session(session)).andExpect(status().isOk());
        assertThat(visits(admin)).isZero();
    }

    @Test
    @DisplayName("올바른 코드 → /admin 302, 세션 권한은 원래 역할, 관리 화면 200, 방문 1건. 같은 세션의 코드 화면은 이제 403")
    void correctCodePromotesSession() throws Exception {
        Member admin = member(Role.ROLE_ADMIN);
        Enrollment enrollment = enroll(admin);
        MockHttpSession session = passwordLogin(admin, "/admin/login/totp");
        String sessionId = session.getId();

        mockMvc.perform(post("/admin/login/totp").session(session).with(csrf())
                        .param("code", TotpTestSupport.currentCode(enrollment.secret(), clock)))
                .andExpect(redirectedUrl("/admin"));

        assertThat(session.getId()).as("2단계에서는 세션 ID를 바꾸지 않는다").isEqualTo(sessionId);
        assertThat(authorities(session)).containsExactly("ROLE_ADMIN");
        mockMvc.perform(get("/admin").session(session)).andExpect(status().isOk());
        assertThat(visits(admin)).isEqualTo(1);
        mockMvc.perform(get("/admin/login/totp").session(session)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("틀린 코드 → 코드 화면으로 다시(?error), 여전히 중간 상태")
    void wrongCodeStaysPending() throws Exception {
        Member manager = member(Role.ROLE_MANAGER);
        Enrollment enrollment = enroll(manager);
        MockHttpSession session = passwordLogin(manager, "/admin/login/totp");

        mockMvc.perform(post("/admin/login/totp").session(session).with(csrf())
                        .param("code", TotpTestSupport.wrongCode(enrollment.secret(), clock)))
                .andExpect(redirectedUrl("/admin/login/totp?error"));
        assertThat(authorities(session)).containsExactly(TotpAuthorities.PENDING);
    }

    @Test
    @DisplayName("이미 쓴 코드(등록 직후 같은 30초 재로그인 등) → 코드 화면으로 다시(?reused), 실패로 세지 않음")
    void reusedCodeIsNotCounted() throws Exception {
        Member manager = member(Role.ROLE_MANAGER);
        Enrollment enrollment = enroll(manager);
        String code = TotpTestSupport.currentCode(enrollment.secret(), clock);
        jdbc.update("UPDATE member_totp SET last_used_step = ? WHERE member_id = ?",
                new com.cms.admin.member.totp.TotpCodeVerifier(clock).currentStep(), manager.getId());
        MockHttpSession session = passwordLogin(manager, "/admin/login/totp");

        mockMvc.perform(post("/admin/login/totp").session(session).with(csrf()).param("code", code))
                .andExpect(redirectedUrl("/admin/login/totp?reused"));
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM member_totp WHERE member_id = ?", Integer.class, manager.getId())).isZero();
        mockMvc.perform(get("/admin/login/totp").session(session).param("reused", ""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                        org.hamcrest.Matchers.containsString("이미 사용한 코드")));
    }

    @Test
    @DisplayName("5회째 오답으로 잠기면 로그인 오류 화면으로 가고 세션은 무효다")
    void lockOnFifthFailure() throws Exception {
        Member manager = member(Role.ROLE_MANAGER);
        Enrollment enrollment = enroll(manager);
        MockHttpSession session = passwordLogin(manager, "/admin/login/totp");
        String wrong = TotpTestSupport.wrongCode(enrollment.secret(), clock);
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/admin/login/totp").session(session).with(csrf()).param("code", wrong))
                    .andExpect(redirectedUrl("/admin/login/totp?error"));
        }
        mockMvc.perform(post("/admin/login/totp").session(session).with(csrf()).param("code", wrong))
                .andExpect(redirectedUrl("/admin/login-error"));
        assertThat(session.isInvalid()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM member WHERE id = ?", String.class, manager.getId())).isEqualTo("LOCKED");
    }

    @Test
    @DisplayName("비밀번호 통과 후 5분이 지난 중간 세션은 코드가 맞아도 로그인 화면으로(?totpExpired), 세션 무효")
    void pendingExpiresAfterFiveMinutes() throws Exception {
        Member admin = member(Role.ROLE_ADMIN);
        Enrollment enrollment = enroll(admin);
        MockHttpSession session = passwordLogin(admin, "/admin/login/totp");
        TotpPendingState state = (TotpPendingState) session.getAttribute(TotpPendingState.SESSION_ATTRIBUTE);
        session.setAttribute(TotpPendingState.SESSION_ATTRIBUTE,
                new TotpPendingState(state.memberId(), LocalDateTime.now(clock).minusMinutes(5).minusSeconds(1)));

        mockMvc.perform(post("/admin/login/totp").session(session).with(csrf())
                        .param("code", TotpTestSupport.currentCode(enrollment.secret(), clock)))
                .andExpect(redirectedUrl("/admin/login?totpExpired"));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("미등록 MANAGER(필수 대상 아님)는 기존 흐름 그대로 /admin, 권한은 역할. 익명은 코드 화면에 오면 로그인으로 302")
    void unenrolledLoginIsUnchanged() throws Exception {
        // 미등록 ADMIN은 강제 등록 상태다 — TotpEnrollmentRequiredAuthorizationIntegrationTest(PLAN-totp-required-admin.md 결정 1)
        Member manager = member(Role.ROLE_MANAGER);
        MockHttpSession session = passwordLogin(manager, "/admin");
        assertThat(authorities(session)).contains("ROLE_MANAGER").doesNotContain(TotpAuthorities.PENDING);
        mockMvc.perform(get("/admin/login/totp")).andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    @DisplayName("S9: 비밀번호 재설정 메일로 비밀번호를 바꿔도 2단계는 해제되지 않는다 — 새 비밀번호 로그인도 코드 화면으로 가고 등록·실패 횟수가 그대로다")
    void passwordResetKeepsSecondFactor() throws Exception {
        Member admin = member(Role.ROLE_ADMIN);
        enroll(admin);
        jdbc.update("UPDATE member_totp SET failed_attempts = 2 WHERE member_id = ?", admin.getId());
        String token = "0123456789abcdef".repeat(4);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("UPDATE member SET reset_token = ?, reset_token_expiry_at = ? WHERE id = ?",
                hash, LocalDateTime.now(clock).plusMinutes(30), admin.getId());
        String newPassword = "reset-keeps-totp-password-2";

        mockMvc.perform(post("/admin/api/password-resets").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\",\"confirmPassword\":\"" + newPassword + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(memberTotpRepository.existsById(admin.getId())).as("재설정은 2단계 등록을 지우지 않는다").isTrue();
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM member_totp WHERE member_id = ?", Integer.class, admin.getId()))
                .as("재설정은 TOTP 실패 횟수를 초기화하지 않는다").isEqualTo(2);
        mockMvc.perform(formLogin("/admin/login").user(admin.getUserId()).password(newPassword))
                .andExpect(redirectedUrl("/admin/login/totp"));
    }

    @Test
    @DisplayName("FM2: 중간 상태 재확인은 비밀번호 실패 횟수를 리셋하지 않는다 — 리셋은 2단계까지 통과했을 때")
    void pendingLoginDoesNotResetPasswordFailures() throws Exception {
        Member admin = member(Role.ROLE_ADMIN);
        Enrollment enrollment = enroll(admin);
        jdbc.update("UPDATE member SET failed_login_count = 3 WHERE id = ?", admin.getId());

        MockHttpSession session = passwordLogin(admin, "/admin/login/totp");
        assertThat(jdbc.queryForObject("SELECT failed_login_count FROM member WHERE id = ?", Integer.class, admin.getId())).isEqualTo(3);

        mockMvc.perform(post("/admin/login/totp").session(session).with(csrf())
                .param("code", TotpTestSupport.currentCode(enrollment.secret(), clock))).andExpect(redirectedUrl("/admin"));
        assertThat(jdbc.queryForObject("SELECT failed_login_count FROM member WHERE id = ?", Integer.class, admin.getId())).isZero();
    }
}
