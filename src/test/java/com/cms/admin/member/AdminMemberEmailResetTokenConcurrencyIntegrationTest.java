package com.cms.admin.member;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.AdminMyInfoUpdateRequest;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.service.AdminMemberService;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 감사 H-02(자기 정보 수정의 행 잠금 누락)의 수정을 실제 MariaDB로 검증한다.
 * 설계 결정 전문은 adversarial-review/plan/PLAN-member-self-update-row-lock.md 참조.
 *
 * <p>이 클래스는 정확히 좁은 사실 두 가지만 각각 독립적으로 증명한다 — 어느 쪽도 단독으로
 * "경합이 완전히 재현됐다"를 주장하지 않는다(계획 문서 v3→v4 개정 이력 참조, codex 리뷰가
 * 그런 과장된 단일 테스트 설계를 두 번 반려했다):
 * <ul>
 *   <li>{@link #findByIdForUpdate_blocksWhileFindByEmailForUpdateHoldsLock()} — PK 잠금
 *       (findByIdForUpdate)과 이메일 잠금(findByEmailForUpdate)이 같은 물리 행을 두고 실제로
 *       충돌한다는 사실만 증명한다.</li>
 *   <li>{@link #updateMyInfo_afterCommittedResetToken_clearsTokenOnEmailChange()} — 이미
 *       커밋된 재설정 토큰이 이메일 변경 시 실제 DB 왕복(Mockito가 아닌 진짜 Hibernate
 *       dirty-checking)으로 정확히 클리어되는지 순차 확인한다(경합 재현 아님 — B가 완전히
 *       커밋한 뒤 A를 호출하므로 수정 전 {@code findById} 코드도 이 테스트는 통과한다).</li>
 * </ul>
 * 회귀 판별(수정 전/후 구분)은 이 두 테스트에 더해 {@code AdminMemberServiceTest}의 호출 계약
 * {@code verify}, 그리고 Spring {@code @Transactional}·InnoDB locking read라는 문서화된
 * 표준 동작까지 합쳐진 조합이 담당한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class AdminMemberEmailResetTokenConcurrencyIntegrationTest extends MariaDbContainerSupport {

    private static final Logger log =
            LoggerFactory.getLogger(AdminMemberEmailResetTokenConcurrencyIntegrationTest.class);

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    AdminMemberService adminMemberService;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    Clock clock;

    @PersistenceContext
    EntityManager entityManager;

    private final List<Long> createdMemberIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdMemberIds) {
            try {
                memberRepository.deleteById(id);
            } catch (Exception ignored) {
            }
        }
        createdMemberIds.clear();
    }

    private Member createActiveAdmin(String email) {
        LocalDateTime now = LocalDateTime.now(clock);
        long unique = System.nanoTime();
        Member saved = memberRepository.save(Member.builder()
                .userId(("h02-" + unique).substring(0, Math.min(50, ("h02-" + unique).length())))
                .pwd("encoded")
                .userName("H02테스트")
                .email(email)
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .createDate(now)
                .updateDate(now)
                .passwordChangedAt(now)
                .build());
        createdMemberIds.add(saved.getId());
        return saved;
    }

    // ===================== 테스트 1: PK 잠금 ↔ 이메일 잠금 물리적 충돌 실증 =====================

    @Test
    @DisplayName("findByEmailForUpdate가 보유한 잠금은 같은 행의 findByIdForUpdate를 블로킹한다 (물리 락 충돌 실증)")
    void findByIdForUpdate_blocksWhileFindByEmailForUpdateHoldsLock() throws Exception {
        Member member = createActiveAdmin("h02-lock-" + System.nanoTime() + "@test.com");
        Long id = member.getId();
        String email = member.getEmail();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> holderFuture = null;

        try {
            holderFuture = executor.submit(() -> tx.execute(status -> {
                memberRepository.findByEmailForUpdate(email);
                lockHeld.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));

            assertTrue(lockHeld.await(10, TimeUnit.SECONDS), "findByEmailForUpdate 보유 트랜잭션이 락을 잡아야 한다");

            Throwable thrown = null;
            try {
                tx.executeWithoutResult(status -> {
                    Object original = entityManager
                            .createNativeQuery("SELECT @@innodb_lock_wait_timeout")
                            .getSingleResult();
                    try {
                        entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = 1").executeUpdate();
                        memberRepository.findByIdForUpdate(id);
                    } finally {
                        try {
                            entityManager.createNativeQuery(
                                    "SET SESSION innodb_lock_wait_timeout = " + original).executeUpdate();
                        } catch (RuntimeException restoreFailure) {
                            log.warn("innodb_lock_wait_timeout 세션 변수 복원 실패", restoreFailure);
                        }
                    }
                });
            } catch (Throwable t) {
                thrown = t;
            }

            assertNotNull(thrown,
                    "findByEmailForUpdate 보유 중인 행의 findByIdForUpdate 조회는 락 대기 타임아웃으로 실패해야 한다 "
                            + "(PK 잠금과 이메일 잠금이 서로 다른 행을 잠근다는 회귀 의심)");
            assertTrue(isLockTimeoutException(thrown),
                    "예외가 락 대기 타임아웃(PessimisticLockingFailureException 또는 MariaDB 오류 코드 1205)"
                            + "이어야 무관한 실패(세션 변수 오류 등)와 구분된다: " + thrown);
        } finally {
            // 위 단언 중 어느 것이 실패해도 락 보유 스레드는 반드시 풀려나야 한다(2·3라운드 리뷰 지적).
            release.countDown();
            if (holderFuture != null) {
                try {
                    holderFuture.get(15, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("락 보유 스레드 완료 대기 중 예외(원 실패를 가리지 않도록 로그만 남김)", e);
                }
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "실행기가 제한 시간 내에 종료되어야 한다");
        }
    }

    private boolean isLockTimeoutException(Throwable thrown) {
        Throwable cause = thrown;
        while (cause != null) {
            if (cause instanceof PessimisticLockingFailureException) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null && (message.contains("1205") || message.toLowerCase().contains("lock wait timeout"))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    // ===================== 테스트 2: 커밋된 토큰의 이메일 변경 시 무효화(순차 정합성) =====================

    @Test
    @DisplayName("이미 발급·커밋된 재설정 토큰은 실제 DB 왕복에서 이메일 변경 시 정확히 클리어된다 (경합 재현 아님 — 정합성 확인)")
    void updateMyInfo_afterCommittedResetToken_clearsTokenOnEmailChange() {
        String oldEmail = "h02-old-" + System.nanoTime() + "@test.com";
        String newEmail = "h02-new-" + System.nanoTime() + "@test.com";
        Member member = createActiveAdmin(oldEmail);
        Long id = member.getId();

        // B 역할 — PasswordResetService.issueToken과 동일한 DB 동작(행 잠금 + 토큰 설정)만 재현하고
        // 완전히 커밋한다. requestReset()을 쓰지 않는 이유는 계획 문서 쟁점 3 참조(비동기 메일 발송·
        // 락 실패 삼키기가 이 테스트의 결정성과 무관한 거짓양성 경로를 만든다).
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        String hashedToken = "b".repeat(64);
        LocalDateTime expiryAt = LocalDateTime.now(clock).plusMinutes(30);
        tx.executeWithoutResult(status -> {
            Member locked = memberRepository.findByEmailForUpdate(oldEmail).orElseThrow();
            locked.issueResetToken(hashedToken, expiryAt);
        });

        // 커밋된 토큰이 실제로 반영됐는지 선확인(테스트 자체의 전제 검증).
        Member beforeUpdate = memberRepository.findById(id).orElseThrow();
        assertEquals(hashedToken, beforeUpdate.getResetToken(), "선행 조건: 토큰 발급이 실제로 커밋되어 있어야 한다");

        // A — 실제 프로덕션 코드 경로(수정된 findByIdForUpdate 사용).
        AdminMyInfoUpdateRequest request = AdminMyInfoUpdateRequest.builder()
                .userName("H02정합성테스트")
                .email(newEmail)
                .build();
        adminMemberService.updateMyInfo(id, request);

        // 새 조회(새 영속성 컨텍스트)로 DB에 실제로 반영된 최종 상태를 확인한다.
        Member reloaded = memberRepository.findById(id).orElseThrow();
        assertEquals(newEmail, reloaded.getEmail());
        assertNull(reloaded.getResetToken(), "이메일 변경 시 이전 이메일로 발급된 토큰이 클리어되어야 한다");
        assertNull(reloaded.getResetTokenExpiryAt());
    }
}
