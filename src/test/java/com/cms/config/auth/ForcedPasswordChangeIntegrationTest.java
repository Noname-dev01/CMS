package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 시간 기반 만료 폐지와 관리자의 비밀번호 변경 강제를 실배선(SecurityConfig 필터 체인)으로 고정한다
 * (PLAN-password-expiry-retirement-breach-check.md D6·D7).
 *
 * <p>MockMvc 로그인은 실제 트랜잭션을 커밋하므로 {@code @Transactional}을 붙이지 않고 만든 행을 {@link #cleanUp()}에서 지운다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class ForcedPasswordChangeIntegrationTest extends MariaDbContainerSupport {

    private static final String RAW_PASSWORD = "forced-change-password-1";
    private static final String NEW_PASSWORD = "forced-change-password-2";
    private static final String TOKEN = "1234567890abcdef".repeat(4);

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<String> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String userId : userIds) {
            jdbc.update("DELETE FROM visit_log WHERE visitor_user_id = ?", userId);
            jdbc.update("DELETE FROM admin_action_log WHERE action_user_id = ?", userId);
        }
        TestMembers.delete(jdbc, memberIds);
    }

    private Member member(Role role, LocalDateTime passwordChangedAt) {
        LocalDateTime now = LocalDateTime.now(clock);
        String unique = "forced-" + System.nanoTime();
        Member saved = memberRepository.save(Member.builder()
                .userId(unique)
                .pwd(passwordEncoder.encode(RAW_PASSWORD))
                .userName("변경강제")
                .email(unique + "@forced.test")
                .userType(role)
                .status(MemberStatus.ACTIVE)
                .createDate(now)
                .updateDate(now)
                .passwordChangedAt(passwordChangedAt)
                .build());
        memberIds.add(saved.getId());
        userIds.add(saved.getUserId());
        return saved;
    }

    private Member member(Role role) {
        return member(role, LocalDateTime.now(clock));
    }

    private Member reload(Member member) {
        return memberRepository.findById(member.getId()).orElseThrow();
    }

    private void attemptLogin(Member member, String password, String expectedRedirect) throws Exception {
        mockMvc.perform(formLogin("/admin/login").user(member.getUserId()).password(password))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(expectedRedirect));
    }

    private ResultActions force(Member actor, Member target) throws Exception {
        return mockMvc.perform(patch("/admin/api/members/" + target.getId()).with(TestMembers.asMember(actor)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"PASSWORD_EXPIRED\"}"));
    }

    private ResultActions reset(String password) throws Exception {
        return mockMvc.perform(post("/admin/api/password-resets").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + TOKEN + "\",\"newPassword\":\"" + password + "\",\"confirmPassword\":\"" + password + "\"}"));
    }

    private void issueToken(Member member) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(TOKEN.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("UPDATE member SET reset_token = ?, reset_token_expiry_at = ? WHERE id = ?",
                hash, LocalDateTime.now(clock).plusMinutes(30), member.getId());
    }

    @Test
    @DisplayName("비밀번호를 1년 넘게 안 바꾼 계정도 정상 로그인되고 상태가 바뀌지 않는다 — 시간 기반 만료 없음")
    void oldPassword_loginSucceeds_noTransition() throws Exception {
        Member member = member(Role.ROLE_ADMIN, LocalDateTime.now(clock).minusDays(400));

        attemptLogin(member, RAW_PASSWORD, "/admin");

        assertThat(reload(member).getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("변경 강제 왕복: ADMIN 지정 → 올바른 비밀번호도 거부(실패 카운트 없음) → 같은 값 재설정 거부 → 새 값 재설정 → ACTIVE 복귀·로그인")
    void forcedChange_roundTrip() throws Exception {
        Member admin = member(Role.ROLE_ADMIN);
        Member target = member(Role.ROLE_MANAGER);

        force(admin, target).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PASSWORD_EXPIRED"));

        attemptLogin(target, RAW_PASSWORD, "/admin/login-error");
        assertThat(reload(target).getStatus()).isEqualTo(MemberStatus.PASSWORD_EXPIRED);
        assertThat(reload(target).getFailedLoginCount()).isZero();

        issueToken(target);
        reset(RAW_PASSWORD).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_REJECTED"));
        assertThat(reload(target).getStatus()).isEqualTo(MemberStatus.PASSWORD_EXPIRED);

        reset(NEW_PASSWORD).andExpect(status().is2xxSuccessful());
        assertThat(reload(target).getStatus()).isEqualTo(MemberStatus.ACTIVE);

        attemptLogin(target, RAW_PASSWORD, "/admin/login-error");
        attemptLogin(target, NEW_PASSWORD, "/admin");
    }

    @Test
    @DisplayName("MANAGER는 변경을 강제할 수 없다 — 403이고 대상 상태가 그대로다")
    void manager_cannotForce() throws Exception {
        Member manager = member(Role.ROLE_MANAGER);
        Member target = member(Role.ROLE_MANAGER);

        force(manager, target).andExpect(status().isForbidden());

        assertThat(reload(target).getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("변경 강제도 본인 계정에는 쓸 수 없다")
    void self_cannotForce() throws Exception {
        Member admin = member(Role.ROLE_ADMIN);

        force(admin, admin).andExpect(status().isBadRequest());

        assertThat(reload(admin).getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }
}
