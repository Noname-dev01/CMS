package com.cms.admin.member.controller;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.repository.MemberTotpRecoveryCodeRepository;
import com.cms.admin.member.repository.MemberTotpRepository;
import com.cms.admin.member.service.TotpEnrollment;
import com.cms.admin.member.totp.TotpCodeVerifier;
import com.cms.admin.member.totp.TotpSecretCipher;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import com.cms.support.TotpTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 강제 등록 화면의 확정(PLAN-totp-required-admin.md 결정 2, FM3): 성공하면 복구 코드를 그 응답에만 보이고 본인 세션을 만료하며, 다시 로그인하면 코드 화면을
 * 거쳐 완전 권한이 된다. 비밀번호·코드 오답은 같은 대기로 화면을 다시 그리고(다시 스캔하지 않게), 낡은 대기는 새 QR로, 상태 변경은 세션 종료로 끝난다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class TotpEnrollmentLoginControllerIntegrationTest extends MariaDbContainerSupport {

    private static final String PASSWORD = "enrollment-login-password";
    private static final String PAGE = "/admin/login/totp-enrollment";

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired MemberTotpRepository memberTotpRepository;
    @Autowired MemberTotpRecoveryCodeRepository recoveryCodeRepository;
    @Autowired TotpSecretCipher cipher;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired SessionRegistry sessionRegistry;
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
            jdbc.update("DELETE FROM admin_action_log WHERE target_id = ?", id);
        }
        TestMembers.delete(jdbc, memberIds);
    }

    private Member admin() {
        Member saved = TestMembers.save(memberRepository, "totp-enroll", Role.ROLE_ADMIN);
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), saved.getId());
        memberIds.add(saved.getId());
        userIds.add(saved.getUserId());
        return memberRepository.findById(saved.getId()).orElseThrow();
    }

    private MockHttpSession passwordLogin(Member member, String expectedRedirect) throws Exception {
        MvcResult result = mockMvc.perform(formLogin("/admin/login").user(member.getUserId()).password(PASSWORD))
                .andExpect(redirectedUrl(expectedRedirect))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    /** 강제 등록 화면을 열어 대기를 발급받는다. */
    private TotpEnrollment openPage(MockHttpSession session) throws Exception {
        mockMvc.perform(get(PAGE).session(session)).andExpect(status().isOk()).andExpect(view().name("admin/login-totp-enrollment"));
        return (TotpEnrollment) session.getAttribute(TotpEnrollment.SESSION_ATTRIBUTE);
    }

    private String code(TotpEnrollment pending) {
        return TotpCodeVerifier.codeAt(pending.secret(), new TotpCodeVerifier(clock).currentStep());
    }

    private int loginFailures(Member member) {
        return jdbc.queryForObject("SELECT failed_login_count FROM member WHERE id = ?", Integer.class, member.getId());
    }

    @Test
    @DisplayName("확정 성공 → 복구 코드 10개 화면(그 응답), 등록 저장·본인 세션 만료 → 다시 로그인하면 코드 화면 → 코드 → /admin")
    void confirmEnrollsExpiresSessionAndRequiresCodeOnNextLogin() throws Exception {
        Member admin = admin();
        MockHttpSession session = passwordLogin(admin, PAGE);
        TotpEnrollment pending = openPage(session);

        MvcResult result = mockMvc.perform(post(PAGE).session(session).with(csrf())
                        .param("enrollmentId", pending.enrollmentId())
                        .param("code", code(pending))
                        .param("currentPassword", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/login-totp-enrollment-done"))
                .andReturn();

        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) result.getModelAndView().getModel().get("recoveryCodes");
        assertThat(codes).hasSize(10);
        assertThat(memberTotpRepository.existsById(admin.getId())).isTrue();
        SessionInformation info = sessionRegistry.getSessionInformation(session.getId());
        assertThat(info == null || info.isExpired()).as("본인 세션 만료").isTrue();
        // 만료 표시만 남은 세션 쿠키로는 완료 화면의 CSS 요청까지 로그인으로 가므로 응답 전에 세션을 무효화한다(실기 검증에서 발견)
        assertThat(session.isInvalid()).as("완료 응답 전에 현재 세션 무효화").isTrue();

        // 확정이 이번 스텝을 썼으므로 같은 30초 안의 다음 로그인 코드는 REUSED가 된다 — 시험에서는 쓴 스텝을 되돌린다
        jdbc.update("UPDATE member_totp SET last_used_step = ? WHERE member_id = ?",
                new TotpCodeVerifier(clock).currentStep() - 2, admin.getId());
        MockHttpSession next = passwordLogin(admin, "/admin/login/totp");
        mockMvc.perform(post("/admin/login/totp").session(next).with(csrf()).param("code", code(pending)))
                .andExpect(redirectedUrl("/admin"));
        mockMvc.perform(get("/admin").session(next)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FM3: 비밀번호 오답 → 같은 화면을 같은 대기로 다시(새 QR 없음)·오류 문구, 로그인 실패 +1, 등록 없음")
    void wrongPasswordRerendersSameEnrollment() throws Exception {
        Member admin = admin();
        MockHttpSession session = passwordLogin(admin, PAGE);
        TotpEnrollment pending = openPage(session);

        mockMvc.perform(post(PAGE).session(session).with(csrf())
                        .param("enrollmentId", pending.enrollmentId())
                        .param("code", code(pending))
                        .param("currentPassword", "wrong-password-value"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/login-totp-enrollment"))
                .andExpect(model().attribute("enrollmentId", pending.enrollmentId()))
                .andExpect(model().attributeExists("error"));

        assertThat(session.getAttribute(TotpEnrollment.SESSION_ATTRIBUTE)).isEqualTo(pending);
        assertThat(loginFailures(admin)).isEqualTo(1);
        assertThat(memberTotpRepository.existsById(admin.getId())).isFalse();
    }

    @Test
    @DisplayName("FM3: 화면의 enrollmentId가 세션 대기와 다르면(다른 탭이 새로 발급) → ?expired로 새 QR, 카운트 없음")
    void staleEnrollmentRedirectsToFreshQr() throws Exception {
        Member admin = admin();
        MockHttpSession session = passwordLogin(admin, PAGE);
        TotpEnrollment pending = openPage(session);

        mockMvc.perform(post(PAGE).session(session).with(csrf())
                        .param("enrollmentId", "not-" + pending.enrollmentId())
                        .param("code", code(pending))
                        .param("currentPassword", PASSWORD))
                .andExpect(redirectedUrl(PAGE + "?expired"));

        assertThat(loginFailures(admin)).isZero();
        assertThat(memberTotpRepository.existsById(admin.getId())).isFalse();
    }

    @Test
    @DisplayName("FM3: 그 사이 다른 경로로 등록됨(409) → 세션 종료·로그인 오류 화면")
    void conflictEndsSession() throws Exception {
        Member admin = admin();
        MockHttpSession session = passwordLogin(admin, PAGE);
        TotpEnrollment pending = openPage(session);
        TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, admin.getId(), clock);

        mockMvc.perform(post(PAGE).session(session).with(csrf())
                        .param("enrollmentId", pending.enrollmentId())
                        .param("code", code(pending))
                        .param("currentPassword", PASSWORD))
                .andExpect(redirectedUrl("/admin/login-error"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("FM6: 레지스트리의 세션 회원이 요청 회원과 다르면(같은 세션의 다른 계정 재로그인 뒤 낡은 요청) 확정하지 않고 세션 종료")
    void registryMemberMismatchEndsSession() throws Exception {
        Member admin = admin();
        MockHttpSession session = passwordLogin(admin, PAGE);
        TotpEnrollment pending = openPage(session);
        // 레지스트리 항목을 다른 회원으로 바꿔 끼운다(재로그인이 같은 세션 ID로 다른 회원을 등록한 상태)
        Member other = admin();
        sessionRegistry.removeSessionInformation(session.getId());
        sessionRegistry.registerNewSession(session.getId(), new com.cms.config.auth.CustomUserDetails(other));

        mockMvc.perform(post(PAGE).session(session).with(csrf())
                        .param("enrollmentId", pending.enrollmentId())
                        .param("code", code(pending))
                        .param("currentPassword", PASSWORD))
                .andExpect(redirectedUrl("/admin/login"));

        assertThat(memberTotpRepository.existsById(admin.getId())).isFalse();
        assertThat(session.isInvalid()).isTrue();
    }
}
