package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.repository.MemberTotpRecoveryCodeRepository;
import com.cms.admin.member.repository.MemberTotpRepository;
import com.cms.admin.member.totp.TotpSecretCipher;
import com.cms.config.auth.TotpLoginService.Status;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import com.cms.support.TotpTestSupport;
import com.cms.support.TotpTestSupport.Enrollment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2단계 판정(PLAN-totp-two-factor.md 결정 2-3)을 실제 MariaDB로 검증한다 — 행 잠금 아래 판정·소비·실패 기록이 한 트랜잭션이고,
 * TOTP 실패 횟수는 비밀번호 쪽 리셋과 무관하며(FM2), 복호 실패는 카운터를 올리지 않는다(FM5).
 * 스레드가 각자 트랜잭션을 열어야 하므로 시험 트랜잭션을 쓰지 않고 만든 회원을 지운다(복구 코드·등록 행은 FK CASCADE).
 */
@SpringBootTest(classes = CmsTestApplication.class)
class TotpLoginServiceIntegrationTest extends MariaDbContainerSupport {

    private static final String IP = "127.0.0.1";
    private static final String URI = "/admin/login/totp";

    @Autowired TotpLoginService totpLoginService;
    @Autowired MemberRepository memberRepository;
    @Autowired MemberTotpRepository memberTotpRepository;
    @Autowired MemberTotpRecoveryCodeRepository recoveryCodeRepository;
    @Autowired TotpSecretCipher cipher;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> created = new ArrayList<>();
    private Member member;
    private Enrollment enrollment;

    @BeforeEach
    void setUp() {
        member = TestMembers.save(memberRepository, "totp-login", Role.ROLE_ADMIN);
        created.add(member.getId());
        enrollment = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : created) {
            jdbc.update("DELETE FROM admin_action_log WHERE target_id = ? AND action_type = 'ACCOUNT_AUTO_LOCK'", id);
        }
        TestMembers.delete(jdbc, created);
    }

    private CustomUserDetails principal() {
        return new CustomUserDetails(member, true);
    }

    private TotpLoginService.Result submit(String code) {
        return totpLoginService.verify(principal(), code, IP, URI);
    }

    private int totpFailures() {
        return jdbc.queryForObject("SELECT failed_attempts FROM member_totp WHERE member_id = ?", Integer.class, member.getId());
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM member WHERE id = ?", String.class, member.getId());
    }

    @Test
    @DisplayName("올바른 코드 → SUCCESS, 쓴 스텝 기록·TOTP 실패와 로그인 실패 횟수 0. 같은 코드 재제출은 WRONG_CODE(재사용 차단)")
    void correctCodeSucceedsOnce() {
        jdbc.update("UPDATE member SET failed_login_count = 3 WHERE id = ?", member.getId());
        jdbc.update("UPDATE member_totp SET failed_attempts = 2 WHERE member_id = ?", member.getId());
        String code = TotpTestSupport.currentCode(enrollment.secret(), clock);

        TotpLoginService.Result result = submit(code);

        assertThat(result.status()).isEqualTo(Status.SUCCESS);
        assertThat(result.usedRecoveryCode()).isFalse();
        assertThat(totpFailures()).isZero();
        assertThat(jdbc.queryForObject("SELECT failed_login_count FROM member WHERE id = ?", Integer.class, member.getId())).isZero();
        assertThat(submit(code).status()).as("같은 스텝 재사용").isEqualTo(Status.REUSED_CODE);
        assertThat(totpFailures()).as("재사용은 실패로 세지 않는다(등록 직후 같은 코드로 재로그인하는 경우)").isZero();
    }

    @Test
    @DisplayName("FM2: 오답 4회 → 비밀번호 쪽 실패 횟수가 리셋돼도(재로그인·재설정) → 오답 1회에 LOCKED, 잠금 시각·감사 1행, TOTP 실패 횟수는 0으로")
    void totpFailuresSurvivePasswordResetsAndLock() {
        String wrong = TotpTestSupport.wrongCode(enrollment.secret(), clock);
        for (int i = 0; i < 4; i++) {
            assertThat(submit(wrong).status()).isEqualTo(Status.WRONG_CODE);
        }
        assertThat(totpFailures()).isEqualTo(4);
        // 비밀번호 재로그인(성공 리셋)·재설정(changePassword)이 건드리는 쪽 — TOTP 카운터는 그대로여야 한다
        jdbc.update("UPDATE member SET failed_login_count = 0 WHERE id = ?", member.getId());

        assertThat(submit(wrong).status()).isEqualTo(Status.LOCKED);

        assertThat(status()).isEqualTo("LOCKED");
        assertThat(jdbc.queryForObject("SELECT locked_at IS NOT NULL FROM member WHERE id = ?", Boolean.class, member.getId())).isTrue();
        assertThat(totpFailures()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log WHERE action_type = 'ACCOUNT_AUTO_LOCK' AND target_id = ?",
                Integer.class, member.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("복구 코드 → SUCCESS(복구 코드 사용, 남은 9개), 같은 코드 재사용은 WRONG_CODE. 하이픈·소문자 입력도 받는다")
    void recoveryCodeIsSingleUse() {
        String code = enrollment.recoveryCodes().get(0);

        TotpLoginService.Result result = submit(code.toLowerCase(java.util.Locale.ROOT).replace("-", ""));

        assertThat(result.status()).isEqualTo(Status.SUCCESS);
        assertThat(result.usedRecoveryCode()).isTrue();
        assertThat(result.remainingRecoveryCodes()).isEqualTo(9);
        assertThat(submit(code).status()).isEqualTo(Status.WRONG_CODE);
    }

    @Test
    @DisplayName("FM4: 상태·역할·비밀번호 해시가 principal과 다르거나 등록이 사라졌으면 판정 없이 REJECTED — 실패 횟수도 그대로")
    void rejectsWhenStateChanged() {
        String code = TotpTestSupport.currentCode(enrollment.secret(), clock);

        jdbc.update("UPDATE member SET status = 'LOCKED' WHERE id = ?", member.getId());
        assertThat(submit(code).status()).isEqualTo(Status.REJECTED);
        jdbc.update("UPDATE member SET status = 'ACTIVE', user_type = 'ROLE_MANAGER' WHERE id = ?", member.getId());
        assertThat(submit(code).status()).isEqualTo(Status.REJECTED);
        jdbc.update("UPDATE member SET user_type = 'ROLE_ADMIN', pwd = 'changed' WHERE id = ?", member.getId());
        assertThat(submit(code).status()).isEqualTo(Status.REJECTED);
        assertThat(totpFailures()).isZero();

        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", member.getPwd(), member.getId());
        jdbc.update("DELETE FROM member_totp WHERE member_id = ?", member.getId());
        assertThat(submit(code).status()).isEqualTo(Status.REJECTED);
    }

    @Test
    @DisplayName("FM5: 비밀값을 복호할 수 없으면(다른 회원 행의 암호문) REJECTED이고 실패 횟수를 올리지 않는다 — 복구 코드는 키와 무관하게 통과")
    void undecryptableSecretRejectsWithoutCounting() {
        jdbc.update("UPDATE member_totp SET secret = ? WHERE member_id = ?", cipher.encrypt(member.getId() + 1, enrollment.secret()), member.getId());

        assertThat(submit(TotpTestSupport.currentCode(enrollment.secret(), clock)).status()).isEqualTo(Status.REJECTED);
        assertThat(totpFailures()).isZero();
        assertThat(submit(enrollment.recoveryCodes().get(1)).status()).isEqualTo(Status.SUCCESS);
    }

    @Test
    @DisplayName("형식이 맞지 않는 입력(빈 값·5자리·임의 문자열)도 오답으로 센다")
    void malformedInputCountsAsFailure() {
        assertThat(submit("").status()).isEqualTo(Status.WRONG_CODE);
        assertThat(submit("12345").status()).isEqualTo(Status.WRONG_CODE);
        assertThat(submit("not-a-code").status()).isEqualTo(Status.WRONG_CODE);
        assertThat(totpFailures()).isEqualTo(3);
    }

    @Test
    @DisplayName("FM2 동시성: 서로 다른 세션의 동시 오답 4건은 실패 횟수를 정확히 4 올리고, 같은 정답 동시 제출 4건은 하나만 SUCCESS")
    void concurrentSubmissionsAreSerialized() throws Exception {
        String wrong = TotpTestSupport.wrongCode(enrollment.secret(), clock);
        assertThat(runConcurrently(4, () -> submit(wrong).status())).containsOnly(Status.WRONG_CODE);
        assertThat(totpFailures()).isEqualTo(4);

        jdbc.update("UPDATE member_totp SET failed_attempts = 0 WHERE member_id = ?", member.getId());
        String code = TotpTestSupport.currentCode(enrollment.secret(), clock);
        List<Status> results = runConcurrently(4, () -> submit(code).status());
        assertThat(results).filteredOn(s -> s == Status.SUCCESS).hasSize(1);
        assertThat(results).filteredOn(s -> s == Status.REUSED_CODE).hasSize(3);
    }

    private static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("MANAGER도 같은 규칙으로 판정된다")
    void managerIsSupported() {
        Member manager = TestMembers.save(memberRepository, "totp-login-mgr", Role.ROLE_MANAGER);
        created.add(manager.getId());
        Enrollment managerEnrollment = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, manager.getId(), clock);
        assertThat(totpLoginService.verify(new CustomUserDetails(manager, true),
                TotpTestSupport.currentCode(managerEnrollment.secret(), clock), IP, URI).status()).isEqualTo(Status.SUCCESS);
        assertThat(manager.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }
}
