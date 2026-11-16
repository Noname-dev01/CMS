package com.cms.admin.member.service;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberTotpRecoveryCode;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.repository.MemberTotpRecoveryCodeRepository;
import com.cms.admin.member.repository.MemberTotpRepository;
import com.cms.admin.member.totp.TotpSecretCipher;
import com.cms.config.auth.CustomUserDetails;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * FM6(PLAN-totp-recovery-code-reissue.md): 옛 복구 코드를 지운 뒤 새 코드 저장이 도중에 실패하면 재발급 전체가 롤백돼야 한다 —
 * 코드가 하나도 없거나 일부만 남으면 사용자는 옛 코드도 새 코드도 쓸 수 없다. 스파이 빈이 있어 다른 시험과 컨텍스트를 나눈다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class MemberTotpReissueRollbackIntegrationTest extends MariaDbContainerSupport {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired MemberTotpService service;
    @Autowired MemberRepository memberRepository;
    @Autowired MemberTotpRepository memberTotpRepository;
    @MockitoSpyBean MemberTotpRecoveryCodeRepository recoveryCodeRepository;
    @Autowired TotpSecretCipher cipher;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;

    private Long memberId;

    @AfterEach
    void cleanUp() {
        if (memberId != null) {
            jdbc.update("DELETE FROM admin_action_log WHERE target_id = ? AND target_type = 'MEMBER'", memberId);
            TestMembers.delete(jdbc, List.of(memberId));
        }
    }

    @Test
    @DisplayName("FM6: 새 코드 5번째 저장이 실패하면 예외가 전파되고 옛 코드 10행·사용 기록·쓴 스텝·실패 횟수가 그대로, 재발급 감사 없음")
    void failureWhileSavingNewCodesRollsBackEverything() {
        Member member = TestMembers.save(memberRepository, "totp-rollback", Role.ROLE_MANAGER);
        memberId = member.getId();
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), memberId);
        Enrollment enrollment = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, memberId, clock);
        jdbc.update("UPDATE member_totp_recovery_code SET used_at = NOW(6) WHERE member_id = ? ORDER BY id LIMIT 1", memberId);
        jdbc.update("UPDATE member_totp SET failed_attempts = 2 WHERE member_id = ?", memberId);
        List<Map<String, Object>> codesBefore = codeRows();
        Map<String, Object> totpBefore = totpRow();

        AtomicInteger saves = new AtomicInteger();
        // 리포지토리는 JDK 프록시라 callRealMethod()를 쓸 수 없다 — 스파이의 기본 응답(실제 빈에 위임)으로 넘긴다
        Answer<?> delegate = Mockito.mockingDetails(recoveryCodeRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            if (saves.incrementAndGet() == 5) {
                throw new IllegalStateException("주입한 저장 실패");
            }
            return delegate.answer(invocation);
        }).when(recoveryCodeRepository).save(any(MemberTotpRecoveryCode.class));

        CustomUserDetails principal = new CustomUserDetails(memberRepository.findById(memberId).orElseThrow());
        assertThatThrownBy(() -> service.reissueRecoveryCodes(principal, TotpTestSupport.currentCode(enrollment.secret(), clock),
                PASSWORD, "127.0.0.1", "/admin/api/members/me/totp/recovery-codes"))
                .hasMessageContaining("주입한 저장 실패");

        assertThat(saves.get()).as("삭제 뒤 저장 도중에 실패했다").isEqualTo(5);
        assertThat(codeRows()).isEqualTo(codesBefore);
        assertThat(totpRow()).isEqualTo(totpBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log WHERE action_type = 'TOTP_RECOVERY_CODES_REISSUE' AND target_id = ?",
                Integer.class, memberId)).isZero();
    }

    private List<Map<String, Object>> codeRows() {
        return jdbc.queryForList("SELECT id, code_hash, used_at FROM member_totp_recovery_code WHERE member_id = ? ORDER BY id", memberId);
    }

    private Map<String, Object> totpRow() {
        return jdbc.queryForMap("SELECT last_used_step, failed_attempts FROM member_totp WHERE member_id = ?", memberId);
    }
}
