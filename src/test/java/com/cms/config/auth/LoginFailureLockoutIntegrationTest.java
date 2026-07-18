package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.CmsTestApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 실패 잠금의 실배선(SecurityConfig 필터 체인) 검증 —
 * 핸들러 단위 테스트는 실패/성공 핸들러가 실제로 연결됐는지 증명하지 못한다.
 *
 * <p>MockMvc 로그인은 실제 트랜잭션을 커밋하므로 {@code @Transactional}을 붙이지 않고,
 * 생성한 회원·감사 로그·방문 로그를 {@link #cleanUp()}에서 대상 한정 삭제한다
 * (공유 dev DB — deleteAll() 금지).
 *
 * <p>로컬 실행: DB(make dev-db) 기동 + DB_PASS/MAIL_USER/MAIL_PASS 환경변수 설정 필요.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class LoginFailureLockoutIntegrationTest {

    private static final String RAW_PASSWORD = "Lockout1234!";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    LoginFailureService loginFailureService;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    SessionRegistry sessionRegistry;

    @Autowired
    Clock clock;

    private final List<Long> createdMemberIds = new ArrayList<>();
    private final List<String> createdUserIds = new ArrayList<>();

    private Member createActiveAdmin() {
        String unique = "lockout-it-" + System.nanoTime();
        String userId = unique.substring(0, Math.min(50, unique.length()));
        Member saved = memberRepository.save(Member.builder()
                .userId(userId)
                .pwd(passwordEncoder.encode(RAW_PASSWORD))
                .userName("잠금통합테스트")
                .email(unique + "@lockout-it.test")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .passwordChangedAt(LocalDateTime.now())
                .build());
        createdMemberIds.add(saved.getId());
        createdUserIds.add(saved.getUserId());
        return saved;
    }

    @BeforeEach
    void ensureCleanState() {
        createdMemberIds.clear();
        createdUserIds.clear();
    }

    @AfterEach
    void cleanUp() {
        // 성공 로그인 세션 정리 — 레지스트리에 테스트 계정 세션이 남지 않게 한다 (best-effort)
        try {
            sessionRegistry.getAllPrincipals().stream()
                    .filter(p -> p instanceof CustomUserDetails details
                            && createdUserIds.contains(details.getUsername()))
                    .forEach(p -> sessionRegistry.getAllSessions(p, true)
                            .forEach(info -> sessionRegistry.removeSessionInformation(info.getSessionId())));
        } catch (Exception ignored) {
        }
        // 실커밋 부작용을 대상 한정 삭제 — 감사 로그·방문 로그·회원 순
        for (Long id : createdMemberIds) {
            try {
                jdbcTemplate.update(
                        "DELETE FROM admin_action_log WHERE action_type = 'ACCOUNT_AUTO_LOCK' AND target_id = ?", id);
            } catch (Exception ignored) {
            }
        }
        for (String userId : createdUserIds) {
            try {
                jdbcTemplate.update("DELETE FROM visit_log WHERE visitor_user_id = ?", userId);
            } catch (Exception ignored) {
            }
        }
        for (Long id : createdMemberIds) {
            try {
                memberRepository.deleteById(id);
            } catch (Exception ignored) {
            }
        }
    }

    private void attemptLogin(String userId, String password, String expectedRedirect) throws Exception {
        mockMvc.perform(formLogin("/admin/login").user(userId).password(password))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(expectedRedirect));
    }

    private Member reload(Long id) {
        return memberRepository.findById(id).orElseThrow();
    }

    private long autoLockAuditCount(Long memberId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM admin_action_log WHERE action_type = 'ACCOUNT_AUTO_LOCK' AND target_id = ?",
                Long.class, memberId);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("틀린 비밀번호 5회 → LOCKED 전이·감사 1건, 6번째 올바른 비밀번호도 거부된다")
    void fiveFailures_locksAccount_andRejectsCorrectPassword() throws Exception {
        Member member = createActiveAdmin();

        for (int i = 0; i < 5; i++) {
            attemptLogin(member.getUserId(), "wrong-password", "/admin/login-error");
        }

        Member locked = reload(member.getId());
        assertThat(locked.getStatus()).isEqualTo(MemberStatus.LOCKED);
        assertThat(locked.getFailedLoginCount()).isEqualTo(5);
        assertThat(locked.getLockedAt()).isNotNull();
        assertThat(autoLockAuditCount(member.getId())).isEqualTo(1); // 감사 정확히 1건 (실커밋)

        // 올바른 비밀번호로도 거부 — LOCKED 상태
        attemptLogin(member.getUserId(), RAW_PASSWORD, "/admin/login-error");
        assertThat(reload(member.getId()).getStatus()).isEqualTo(MemberStatus.LOCKED);
    }

    @Test
    @DisplayName("자동 잠금 30분 경과 후 올바른 비밀번호 로그인은 자동 해제되어 성공한다")
    void expiredAutoLock_correctPassword_unlocksAndLogsIn() throws Exception {
        Member member = createActiveAdmin();
        LocalDateTime expiredLockedAt = LocalDateTime.now(clock).minusMinutes(31);
        jdbcTemplate.update(
                "UPDATE member SET status = 'LOCKED', failed_login_count = 5, locked_at = ? WHERE id = ?",
                expiredLockedAt, member.getId());

        attemptLogin(member.getUserId(), RAW_PASSWORD, "/admin");

        Member found = reload(member.getId());
        assertThat(found.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(found.getFailedLoginCount()).isZero();
        assertThat(found.getLockedAt()).isNull();
    }

    @Test
    @DisplayName("수동 잠금(locked_at null)은 30분과 무관하게 올바른 비밀번호도 계속 거부되고 카운트가 불변이다")
    void manualLock_correctPassword_stillRejected_countUnchanged() throws Exception {
        Member member = createActiveAdmin();
        jdbcTemplate.update(
                "UPDATE member SET status = 'LOCKED', failed_login_count = 0, locked_at = NULL WHERE id = ?",
                member.getId());

        attemptLogin(member.getUserId(), RAW_PASSWORD, "/admin/login-error");

        Member found = reload(member.getId());
        assertThat(found.getStatus()).isEqualTo(MemberStatus.LOCKED);
        assertThat(found.getFailedLoginCount()).isZero(); // 상태 기반 거부는 카운트를 증가시키지 않는다
    }

    @Test
    @DisplayName("잠금 트랜잭션이 롤백되면 감사 로그가 생성되지 않는다 (AFTER_COMMIT 원자성)")
    void lockTransactionRollback_noAuditCreated() {
        Member member = createActiveAdmin();
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.executeWithoutResult(status -> {
            for (int i = 0; i < 5; i++) {
                loginFailureService.recordFailure(member.getUserId(), "127.0.0.1", "/admin/login");
            }
            status.setRollbackOnly(); // 잠금 전이까지 수행한 뒤 강제 롤백
        });

        Member found = reload(member.getId());
        assertThat(found.getStatus()).isEqualTo(MemberStatus.ACTIVE); // 잠금 롤백됨
        assertThat(found.getFailedLoginCount()).isZero();
        assertThat(autoLockAuditCount(member.getId())).isZero();      // 감사도 미생성
    }
}
