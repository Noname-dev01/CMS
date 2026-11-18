package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FM1(PLAN-totp-required-admin.md 결정 1·3): 2단계를 쓰지 않는 ADMIN의 비밀번호 로그인은 역할 권한이 없는 강제 등록 상태만 얻고,
 * 강제 등록 화면 외의 관리 경로는 모두 막힌다(화면은 등록 화면으로 302, API는 401). 실제 보안 체인으로 확인한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class TotpEnrollmentRequiredAuthorizationIntegrationTest extends MariaDbContainerSupport {

    private static final String PASSWORD = "enrollment-required-password";
    private static final String ENROLLMENT_PAGE = "/admin/login/totp-enrollment";

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<String> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String userId : userIds) {
            jdbc.update("DELETE FROM visit_log WHERE visitor_user_id = ?", userId);
        }
        TestMembers.delete(jdbc, memberIds);
    }

    private Member member(Role role) {
        Member saved = TestMembers.save(memberRepository, "totp-required", role);
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), saved.getId());
        memberIds.add(saved.getId());
        userIds.add(saved.getUserId());
        return memberRepository.findById(saved.getId()).orElseThrow();
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
    @DisplayName("미등록 ADMIN: 권한은 {TOTP_ENROLLMENT_REQUIRED}뿐, 관리 화면·보안 설정·코드 화면은 등록 화면으로 302, API는 401, 등록 화면만 200 — 방문 기록 없음")
    void unenrolledAdminIsConfinedToEnrollmentPage() throws Exception {
        Member admin = member(Role.ROLE_ADMIN);

        MockHttpSession session = passwordLogin(admin, ENROLLMENT_PAGE);

        assertThat(authorities(session)).containsExactly(TotpAuthorities.ENROLLMENT_REQUIRED);
        mockMvc.perform(get("/admin").session(session)).andExpect(redirectedUrl(ENROLLMENT_PAGE));
        mockMvc.perform(get("/admin/member/settings").session(session)).andExpect(redirectedUrl(ENROLLMENT_PAGE));
        mockMvc.perform(get("/admin/permission/manage").session(session)).andExpect(redirectedUrl(ENROLLMENT_PAGE));
        mockMvc.perform(get("/admin/login/totp").session(session)).andExpect(redirectedUrl(ENROLLMENT_PAGE));
        mockMvc.perform(get("/admin/api/members/me").session(session)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/api/members/me/totp").session(session)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/api/members").session(session)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ENROLLMENT_PAGE).session(session)).andExpect(status().isOk());
        assertThat(visits(admin)).isZero();
    }

    @Test
    @DisplayName("미등록 MANAGER는 필수 대상이 아니다 — 바로 /admin, 등록 화면은 403")
    void unenrolledManagerLogsInDirectly() throws Exception {
        Member manager = member(Role.ROLE_MANAGER);

        MockHttpSession session = passwordLogin(manager, "/admin");

        assertThat(authorities(session)).contains("ROLE_MANAGER").doesNotContain(TotpAuthorities.ENROLLMENT_REQUIRED);
        mockMvc.perform(get(ENROLLMENT_PAGE).session(session)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("익명은 등록 화면에 오면 로그인으로 302")
    void anonymousIsSentToLogin() throws Exception {
        mockMvc.perform(get(ENROLLMENT_PAGE)).andExpect(redirectedUrl("/admin/login"));
    }
}
