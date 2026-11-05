package com.cms.admin.member.password;

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
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 비밀번호를 정하는 HTTP 경로의 거부 계약을 실제 DB로 고정한다(PLAN-password-expiry-retirement-breach-check.md D4·D5) —
 * 거부해도 DB가 그대로이고 토큰이 살아 있으며, 기존 오류의 우선순위가 유지되고, 외부 판정 중에 트랜잭션·행 잠금을 쥐지 않는다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PasswordBreachRejectionIntegrationTest extends MariaDbContainerSupport {

    private static final String CURRENT = "current-password-value-1";
    private static final String NEW_VALUE = "new-password-value-22222";
    private static final String OTHER_VALUE = "other-password-value-333";
    private static final String TOKEN = "0123456789abcdef".repeat(4);
    private static final String BREACHED_MESSAGE = "유출된 적이 있는 비밀번호입니다. 다른 비밀번호를 입력해 주세요.";
    private static final String SAME_MESSAGE = "현재 비밀번호와 다른 비밀번호를 입력해 주세요.";
    private static final String INVALID_TOKEN_MESSAGE = "유효하지 않은 재설정 토큰입니다.";

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired ApplicationContext context;
    @Autowired PlatformTransactionManager transactionManager;

    @MockitoBean BreachedPasswordChecker breachedPasswordChecker;

    private final List<Long> createdMemberIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM member WHERE user_id = 'breach-created'");
        TestMembers.delete(jdbc, createdMemberIds);
    }

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private Member member(MemberStatus status, String tokenHash, LocalDateTime tokenExpiry) {
        LocalDateTime now = LocalDateTime.now(clock);
        String unique = "breach-" + System.nanoTime();
        Member saved = memberRepository.save(Member.builder()
                .userId(unique)
                .pwd(passwordEncoder.encode(CURRENT))
                .userName("유출검사")
                .email(unique + "@breach.test")
                .userType(Role.ROLE_ADMIN)
                .status(status)
                .createDate(now)
                .updateDate(now)
                .passwordChangedAt(now)
                .resetToken(tokenHash)
                .resetTokenExpiryAt(tokenExpiry)
                .build());
        createdMemberIds.add(saved.getId());
        return saved;
    }

    private Member memberWithToken() throws Exception {
        return member(MemberStatus.ACTIVE, sha256Hex(TOKEN), LocalDateTime.now(clock).plusMinutes(30));
    }

    private Member reload(Member member) {
        return memberRepository.findById(member.getId()).orElseThrow();
    }

    private org.springframework.test.web.servlet.ResultActions changePassword(RequestPostProcessor who, String current, String next) throws Exception {
        return mockMvc.perform(patch("/admin/api/members/me/password").with(who).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\",\"confirmPassword\":\"" + next + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions reset(String token, String next) throws Exception {
        return mockMvc.perform(post("/admin/api/password-resets").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + next + "\",\"confirmPassword\":\"" + next + "\"}"));
    }

    @Test
    @DisplayName("Spring의 CompromisedPasswordChecker 빈이 없다 — 있으면 로그인마다 자동 검사된다")
    void noCompromisedPasswordCheckerBean() {
        assertThat(context.getBeanNamesForType(CompromisedPasswordChecker.class)).isEmpty();
    }

    // ===== 내 비밀번호 변경 =====

    @Test
    @DisplayName("변경: 유출이면 400 PASSWORD_REJECTED, DB 그대로, 감사 FAIL 행은 상수 메시지만 남긴다")
    void change_breached_rejectedAndAudited() throws Exception {
        Member member = member(MemberStatus.ACTIVE, null, null);
        String before = member.getPwd();
        given(breachedPasswordChecker.isBreached(NEW_VALUE)).willReturn(true);

        changePassword(TestMembers.asMember(member), CURRENT, NEW_VALUE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_REJECTED"))
                .andExpect(jsonPath("$.message").value(BREACHED_MESSAGE));

        assertThat(reload(member).getPwd()).isEqualTo(before);
        String error = jdbc.queryForObject(
                "SELECT error_message FROM admin_action_log WHERE action_type = 'PASSWORD_CHANGE' AND action_user_id = ? AND action_result = 'FAIL' ORDER BY id DESC LIMIT 1",
                String.class, member.getUserId());
        assertThat(error).isEqualTo(BREACHED_MESSAGE).doesNotContain(NEW_VALUE);
    }

    @Test
    @DisplayName("변경: 현재 비밀번호가 틀리면 새 비밀번호가 유출이어도 기존 오류다")
    void change_wrongCurrentWinsOverBreached() throws Exception {
        Member member = member(MemberStatus.ACTIVE, null, null);
        given(breachedPasswordChecker.isBreached(NEW_VALUE)).willReturn(true);

        changePassword(TestMembers.asMember(member), "wrong-current-password-x", NEW_VALUE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("현재 비밀번호가 올바르지 않습니다."));
    }

    @Test
    @DisplayName("변경: 현재 값과 같으면 거부한다 — 외부 판정이 통과여도 동작한다")
    void change_sameAsCurrent_rejected() throws Exception {
        Member member = member(MemberStatus.ACTIVE, null, null);
        LocalDateTime changedAt = reload(member).getPasswordChangedAt();

        changePassword(TestMembers.asMember(member), CURRENT, CURRENT)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_REJECTED"))
                .andExpect(jsonPath("$.message").value(SAME_MESSAGE));

        assertThat(reload(member).getPasswordChangedAt()).isEqualTo(changedAt);
    }

    @Test
    @DisplayName("변경: 판정 불가(false)면 통과해 새 비밀번호로 바뀐다")
    void change_unavailable_passes() throws Exception {
        Member member = member(MemberStatus.ACTIVE, null, null);

        changePassword(TestMembers.asMember(member), CURRENT, NEW_VALUE).andExpect(status().isOk());

        assertThat(passwordEncoder.matches(NEW_VALUE, reload(member).getPwd())).isTrue();
    }

    @Test
    @DisplayName("변경: 외부 판정 중에는 트랜잭션이 없고 회원 행이 잠겨 있지 않다")
    void change_externalCallOutsideTransactionAndLock() throws Exception {
        Member member = member(MemberStatus.ACTIVE, null, null);
        AtomicBoolean checked = assertOutsideTransactionWhenChecked(member);

        changePassword(TestMembers.asMember(member), CURRENT, NEW_VALUE).andExpect(status().isOk());

        assertThat(checked).isTrue();
    }

    /** 판정 호출 순간에 트랜잭션이 없고, 다른 연결이 대기 없이 그 회원 행을 잠글 수 있음을 단언한다. */
    private AtomicBoolean assertOutsideTransactionWhenChecked(Member member) {
        AtomicBoolean checked = new AtomicBoolean();
        given(breachedPasswordChecker.isBreached(any())).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    jdbc.queryForList("SELECT id FROM member WHERE id = ? FOR UPDATE NOWAIT", member.getId()));
            checked.set(true);
            return false;
        });
        return checked;
    }

    // ===== 비밀번호 재설정 =====

    @Test
    @DisplayName("재설정: 유출이면 거부되고 토큰이 살아 있어 다른 값으로 바로 성공한다")
    void reset_breached_tokenSurvivesAndRetrySucceeds() throws Exception {
        Member member = memberWithToken();
        given(breachedPasswordChecker.isBreached(NEW_VALUE)).willReturn(true);

        reset(TOKEN, NEW_VALUE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_REJECTED"))
                .andExpect(jsonPath("$.message").value(BREACHED_MESSAGE));

        assertThat(reload(member).getResetToken()).isEqualTo(sha256Hex(TOKEN));
        assertThat(passwordEncoder.matches(CURRENT, reload(member).getPwd())).isTrue();

        reset(TOKEN, OTHER_VALUE).andExpect(status().is2xxSuccessful());

        assertThat(passwordEncoder.matches(OTHER_VALUE, reload(member).getPwd())).isTrue();
        assertThat(reload(member).getResetToken()).isNull();
    }

    @Test
    @DisplayName("재설정: 현재 값과 같으면 거부되고 토큰이 살아 있다")
    void reset_sameAsCurrent_rejectedAndTokenSurvives() throws Exception {
        Member member = memberWithToken();

        reset(TOKEN, CURRENT)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_REJECTED"))
                .andExpect(jsonPath("$.message").value(SAME_MESSAGE));

        assertThat(reload(member).getResetToken()).isEqualTo(sha256Hex(TOKEN));
    }

    @Test
    @DisplayName("재설정: 없는 토큰으로는 외부 호출이 일어나지 않는다")
    void reset_unknownToken_noExternalCall() throws Exception {
        reset("f".repeat(64), NEW_VALUE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(INVALID_TOKEN_MESSAGE));

        verify(breachedPasswordChecker, never()).isBreached(any());
    }

    @Test
    @DisplayName("재설정: 만료된 토큰은 비밀번호가 유출이어도 기존 무효 토큰 오류다")
    void reset_expiredToken_invalidWinsOverBreached() throws Exception {
        member(MemberStatus.ACTIVE, sha256Hex(TOKEN), LocalDateTime.now(clock).minusMinutes(1));
        given(breachedPasswordChecker.isBreached(any())).willReturn(true);

        reset(TOKEN, NEW_VALUE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(INVALID_TOKEN_MESSAGE));
    }

    @Test
    @DisplayName("재설정: 자격을 잃은 계정의 토큰은 비밀번호가 유출이어도 기존 무효 토큰 오류다")
    void reset_ineligibleAccount_invalidWinsOverBreached() throws Exception {
        member(MemberStatus.DISABLED, sha256Hex(TOKEN), LocalDateTime.now(clock).plusMinutes(30));
        given(breachedPasswordChecker.isBreached(any())).willReturn(true);

        reset(TOKEN, NEW_VALUE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(INVALID_TOKEN_MESSAGE));
    }

    @Test
    @DisplayName("재설정: 외부 판정 중 토큰이 소비되면 기존 무효 토큰 오류다")
    void reset_tokenConsumedDuringCheck_invalid() throws Exception {
        Member member = memberWithToken();
        given(breachedPasswordChecker.isBreached(any())).willAnswer(invocation -> {
            jdbc.update("UPDATE member SET reset_token = NULL, reset_token_expiry_at = NULL WHERE id = ?", member.getId());
            return true;
        });

        reset(TOKEN, NEW_VALUE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(INVALID_TOKEN_MESSAGE));
    }

    @Test
    @DisplayName("재설정: 외부 판정 중에는 트랜잭션이 없고 회원 행이 잠겨 있지 않다")
    void reset_externalCallOutsideTransactionAndLock() throws Exception {
        Member member = memberWithToken();
        AtomicBoolean checked = assertOutsideTransactionWhenChecked(member);

        reset(TOKEN, NEW_VALUE).andExpect(status().is2xxSuccessful());

        assertThat(checked).isTrue();
    }

    // ===== 관리자 계정 생성 =====

    private org.springframework.test.web.servlet.ResultActions createAdmin(RequestPostProcessor who, String userId, String email) throws Exception {
        return mockMvc.perform(post("/admin/api/members").with(who).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"pwd\":\"" + NEW_VALUE + "\",\"userName\":\"생성\",\"email\":\"" + email
                        + "\",\"userType\":\"ROLE_MANAGER\"}"));
    }

    @Test
    @DisplayName("생성: 유출이면 400 PASSWORD_REJECTED이고 행이 생기지 않는다")
    void create_breached_rejected() throws Exception {
        Member admin = member(MemberStatus.ACTIVE, null, null);
        given(breachedPasswordChecker.isBreached(NEW_VALUE)).willReturn(true);

        createAdmin(TestMembers.asMember(admin), "breach-created", "breach-created@breach.test")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_REJECTED"));

        assertThat(memberRepository.existsByUserId("breach-created")).isFalse();
    }

    @Test
    @DisplayName("생성: 아이디가 중복이면 비밀번호가 유출이어도 기존 409다")
    void create_duplicateWinsOverBreached() throws Exception {
        Member admin = member(MemberStatus.ACTIVE, null, null);
        given(breachedPasswordChecker.isBreached(NEW_VALUE)).willReturn(true);

        createAdmin(TestMembers.asMember(admin), admin.getUserId(), "breach-other@breach.test")
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("생성: 외부 판정 중에는 트랜잭션이 없다, 통과하면 계정이 생긴다")
    void create_outsideTransactionAndCreated() throws Exception {
        Member admin = member(MemberStatus.ACTIVE, null, null);
        AtomicBoolean checked = assertOutsideTransactionWhenChecked(admin);

        createAdmin(TestMembers.asMember(admin), "breach-created", "breach-created@breach.test")
                .andExpect(status().isCreated());

        assertThat(checked).isTrue();
        assertThat(memberRepository.existsByUserId("breach-created")).isTrue();
    }
}
