package com.cms.admin.member.service;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.repository.MemberTotpRecoveryCodeRepository;
import com.cms.admin.member.repository.MemberTotpRepository;
import com.cms.admin.member.service.TotpSecretRewrapService.RewrapReport;
import com.cms.admin.member.totp.TotpProperties;
import com.cms.admin.member.totp.TotpSecretCipher;
import com.cms.config.auth.CustomUserDetails;
import com.cms.config.auth.TotpLoginService;
import com.cms.support.CmsTestApplication;
import com.cms.support.LockWaitObserver;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import com.cms.support.TotpTestSupport;
import com.cms.support.TotpTestSupport.Enrollment;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2단계 인증 암호화 키 교체의 기동 재암호화(PLAN-totp-key-rotation.md 결정 3, FM1·FM2·FM4·FM5).
 * 서비스는 옛 키를 가진 암호기로 직접 만든다 — 컨텍스트의 암호기(dev 키 하나)를 현재 키로 두고 옛 키만 더한다.
 * 그래서 다시 쓴 행은 컨텍스트의 로그인 서비스로 그대로 검증된다. 새 컨텍스트를 만들지 않는다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class TotpSecretRewrapServiceIntegrationTest extends MariaDbContainerSupport {

    private static final String OLD_KEY = Base64.getEncoder().encodeToString(filled((byte) 11));
    private static final String STRANGER_KEY = Base64.getEncoder().encodeToString(filled((byte) 22));

    @Autowired MemberRepository memberRepository;
    @Autowired MemberTotpRepository memberTotpRepository;
    @Autowired MemberTotpRecoveryCodeRepository recoveryCodeRepository;
    @Autowired TotpSecretCipher contextCipher;
    @Autowired TotpProperties totpProperties;
    @Autowired TotpLoginService totpLoginService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcConnectionDetails connectionDetails;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager entityManager;

    private final List<Long> memberIds = new ArrayList<>();

    private static byte[] filled(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    @AfterEach
    void cleanUp() {
        TestMembers.delete(jdbc, memberIds);
        memberIds.clear();
    }

    private TotpSecretCipher rotatingCipher() {
        return new TotpSecretCipher(totpProperties.getEncryptionKey(), OLD_KEY);
    }

    private TotpSecretRewrapService service(TotpSecretCipher cipher, PlatformTransactionManager txManager) {
        return new TotpSecretRewrapService(cipher, memberRepository, memberTotpRepository, txManager);
    }

    private Long newMember(String userId) {
        Member member = TestMembers.save(memberRepository, userId, Role.ROLE_MANAGER);
        memberIds.add(member.getId());
        return member.getId();
    }

    private Enrollment enrollWith(String key, Long memberId) {
        return TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, new TotpSecretCipher(key), memberId, clock);
    }

    private String secretOf(Long memberId) {
        return jdbc.queryForObject("SELECT secret FROM member_totp WHERE member_id = ?", String.class, memberId);
    }

    private Map<String, Object> otherColumns(Long memberId) {
        return jdbc.queryForMap("SELECT enabled_at, last_used_step, failed_attempts FROM member_totp WHERE member_id = ?", memberId);
    }

    @Test
    @DisplayName("FM2·FM5·S2: 옛 키 v1 행만 현재 키 v1으로 다시 쓰고(현재 키 행은 같은 문자열), 다른 컬럼은 보존, 다시 쓴 회원은 인증 앱 코드로 로그인된다")
    void rewrapsOnlyOldKeyRows() {
        Long oldRow = newMember("rewrap-old");
        Long currentRow = newMember("rewrap-current");
        Enrollment oldEnrollment = enrollWith(OLD_KEY, oldRow);
        enrollWith(totpProperties.getEncryptionKey(), currentRow);
        jdbc.update("UPDATE member_totp SET failed_attempts = 3 WHERE member_id = ?", oldRow);
        String oldSecretBefore = secretOf(oldRow);
        String currentSecretBefore = secretOf(currentRow);
        Map<String, Object> oldColumnsBefore = otherColumns(oldRow);

        RewrapReport report = service(rotatingCipher(), transactionManager).rewrapAll();

        assertThat(secretOf(currentRow)).as("현재 키 행은 다시 쓰지 않는다(다른 IV로 매 기동 바뀌지 않게)").isEqualTo(currentSecretBefore);
        assertThat(secretOf(oldRow)).isNotEqualTo(oldSecretBefore).startsWith("v1:");
        assertThat(otherColumns(oldRow)).isEqualTo(oldColumnsBefore);
        assertThat(contextCipher.decryptWithCurrentKey(oldRow, secretOf(oldRow))).isEqualTo(oldEnrollment.secret());
        assertThat(report.rewrapped()).isEqualTo(1);
        assertThat(report.unreadable()).isZero();
        assertThat(report.errors()).isZero();
        assertThat(report.readableWithCurrentKey()).isEqualTo(report.total());
        assertThat(report.currentKeyId()).isEqualTo(contextCipher.currentKeyId());
        assertThat(report.previousKeyId()).isPresent();

        // 옛 키 설정을 지운 앱(컨텍스트 로그인 서비스 = 현재 키 하나)으로 인증 앱 코드가 통과한다
        CustomUserDetails principal = new CustomUserDetails(memberRepository.findById(oldRow).orElseThrow(), true);
        TotpLoginService.Result result = totpLoginService.verify(principal, TotpTestSupport.currentCode(oldEnrollment.secret(), clock),
                "127.0.0.1", "/admin/login/totp");
        assertThat(result.status()).isEqualTo(TotpLoginService.Status.SUCCESS);
    }

    @Test
    @DisplayName("FM4·S5: 어느 키로도 풀리지 않는 행은 그대로 두고 집계만 하며 나머지 행은 계속 다시 쓴다")
    void skipsUnreadableRows() {
        Long first = newMember("rewrap-a");
        Long second = newMember("rewrap-b");
        Long stranger = newMember("rewrap-stranger");
        enrollWith(OLD_KEY, first);
        enrollWith(OLD_KEY, second);
        enrollWith(STRANGER_KEY, stranger);
        String strangerBefore = secretOf(stranger);

        RewrapReport report = service(rotatingCipher(), transactionManager).rewrapAll();

        assertThat(report.rewrapped()).isEqualTo(2);
        assertThat(report.unreadable()).isEqualTo(1);
        assertThat(report.errors()).isZero();
        assertThat(report.readableWithCurrentKey()).isEqualTo(report.total() - 1);
        assertThat(report.unreadableWithCurrentKeyIds()).containsExactly(stranger);
        assertThat(secretOf(stranger)).isEqualTo(strangerBefore);
        assertThat(contextCipher.decryptWithCurrentKey(first, secretOf(first))).isNotEmpty();
        assertThat(contextCipher.decryptWithCurrentKey(second, secretOf(second))).isNotEmpty();
    }

    @Test
    @DisplayName("FM4·S5: 한 행의 커밋이 실패해도 다음 행으로 넘어가 오류로 집계하고 예외를 밖으로 내지 않는다")
    void continuesAfterCommitFailure() {
        Long first = newMember("rewrap-commit-a");
        Long second = newMember("rewrap-commit-b");
        enrollWith(OLD_KEY, first);
        enrollWith(OLD_KEY, second);

        // 첫 커밋만 실패시킨다 — 실제 트랜잭션은 롤백해 잠금·연결을 남기지 않고 커밋 실패 예외를 던진다
        AtomicBoolean failedOnce = new AtomicBoolean();
        PlatformTransactionManager failingFirstCommit = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return transactionManager.getTransaction(definition);
            }

            @Override
            public void commit(TransactionStatus status) {
                if (failedOnce.compareAndSet(false, true)) {
                    transactionManager.rollback(status);
                    throw new TransactionSystemException("주입한 커밋 실패");
                }
                transactionManager.commit(status);
            }

            @Override
            public void rollback(TransactionStatus status) {
                transactionManager.rollback(status);
            }
        };

        RewrapReport report = service(rotatingCipher(), failingFirstCommit).rewrapAll();

        assertThat(report.errors()).isEqualTo(1);
        assertThat(report.rewrapped()).isEqualTo(1);
        assertThat(report.readableWithCurrentKey()).isEqualTo(report.total() - 1);
    }

    @Test
    @DisplayName("FM1: 재암호화는 회원 행 잠금을 실제로 기다린 뒤 다시 읽어, 잠근 쪽이 바꾼 쓴 스텝·실패 횟수를 되돌리지 않는다")
    void waitsForMemberRowLockAndKeepsConcurrentChanges() throws Exception {
        Long memberId = newMember("rewrap-lock");
        enrollWith(OLD_KEY, memberId);
        long stepBefore = ((Number) otherColumns(memberId).get("last_used_step")).longValue();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        try {
            // 로그인 2단계처럼 회원 행을 잠그고 쓴 스텝·실패 횟수를 바꾼 채 커밋을 미룬다
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                memberRepository.findByIdForUpdate(memberId).orElseThrow();
                holderConnection.set(((Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult()).longValue());
                entityManager.createNativeQuery("UPDATE member_totp SET last_used_step = last_used_step + 1, failed_attempts = 4 WHERE member_id = ?")
                        .setParameter(1, memberId).executeUpdate();
                locked.countDown();
                await(release);
            }));
            await(locked);

            Future<RewrapReport> rewrap = executor.submit(() -> service(rotatingCipher(), transactionManager).rewrapAll());
            new LockWaitObserver(connectionDetails.getJdbcUrl(), connectionDetails.getPassword())
                    .awaitWaiterFor(holderConnection.get(), Duration.ofSeconds(8));
            assertThat(rewrap.isDone()).as("회원 행 잠금이 풀리기 전에는 재암호화가 끝나지 않는다").isFalse();

            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            assertThat(rewrap.get(15, TimeUnit.SECONDS).rewrapped()).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }

        Map<String, Object> after = otherColumns(memberId);
        assertThat(((Number) after.get("last_used_step")).longValue()).isEqualTo(stepBefore + 1);
        assertThat(((Number) after.get("failed_attempts")).intValue()).isEqualTo(4);
        assertThat(contextCipher.decryptWithCurrentKey(memberId, secretOf(memberId))).isNotEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
