package com.cms.admin.notice.service;

import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.dto.request.NoticeUpdateRequest;
import com.cms.admin.notice.repository.NoticeRepository;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.support.CmsTestApplication;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * PATCH·DELETE 비관적 락(findByIdAndDeletedFalseForUpdate)을 실제 MariaDB로 검증하는
 * 통합 테스트.
 *
 * <p>두 가지를 각각 별도로 검증한다:
 * <ul>
 *   <li>(A) 락 존재의 결정적 증명 — 타이밍이 아니라 "락 보유 중 짧은 대기 타임아웃으로
 *       재조회하면 반드시 실패하는가"로 검증. {@code AdminMemberUpdateConcurrencyIntegrationTest
 *       .guardQuery_actuallyAcquiresRowLocks()}와 동일 기법(SQL 세션 레벨
 *       innodb_lock_wait_timeout)을 미러한다 — 이 프로젝트에서 이미 검증된 패턴이므로
 *       계획서가 제안한 {@code jakarta.persistence.lock.timeout} JPA 힌트 대신 채택했다
 *       (구현 중 결정 변경 — 목적은 동일, 검증된 기존 기법 재사용).</li>
 *   <li>(B) 실제 PATCH-DELETE 통합 동작 확인 — 락의 존재는 (A)가 증명하므로, (B)는
 *       "락이 존재하는 전제 하에 PATCH가 DELETE의 결과를 유실시키지 않는가"를 확인한다.</li>
 * </ul>
 *
 * <p>두 스레드가 독립 트랜잭션과 비관적 락을 획득해야 하므로 {@code @Transactional}을 붙이지
 * 않는다. hang 방지: 모든 latch/Future에 제한 시간을 두고, 해제 래치는 메인 스레드의
 * {@code finally}에서 반드시 카운트다운하며, 종료 시 {@code executor.shutdownNow()}로 정리한다.
 *
 * <p>로컬 실행: DB(dev-db) 기동 + DB_PASS/MAIL_USER/MAIL_PASS 환경변수 설정 필요.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class NoticeConcurrencyIntegrationTest {

    @Autowired
    NoticeRepository noticeRepository;

    @Autowired
    NoticeService noticeService;

    @Autowired
    PlatformTransactionManager transactionManager;

    @PersistenceContext
    EntityManager entityManager;

    private final List<Long> createdNoticeIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdNoticeIds) {
            try {
                noticeRepository.deleteById(id);
            } catch (Exception ignored) {
            }
        }
    }

    private Notice saveNotice(String titlePrefix) {
        LocalDateTime now = LocalDateTime.now();
        Notice saved = noticeRepository.save(Notice.builder()
                .title(titlePrefix + "-" + System.nanoTime())
                .content("동시성 테스트 본문")
                .useYn(true)
                .deleted(false)
                .authorId("admin01")
                .createDate(now)
                .updateDate(now)
                .build());
        createdNoticeIds.add(saved.getId());
        return saved;
    }

    // ===================== (A) 락 존재의 결정적 증명 =====================

    @Test
    @DisplayName("findByIdAndDeletedFalseForUpdate는 실제로 PESSIMISTIC_WRITE 행 잠금을 획득한다 (락 실증)")
    void lockQuery_actuallyAcquiresRowLock() throws Exception {
        Notice target = saveNotice("락실증");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> holderFuture = executor.submit(() -> tx.execute(status -> {
                noticeRepository.findByIdAndDeletedFalseForUpdate(target.getId());
                lockHeld.countDown();
                try {
                    // 제한 시간 내 해제 신호가 오지 않으면 트랜잭션이 무기한 열려 있지 않도록 짧게 대기.
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));

            assertTrue(lockHeld.await(10, TimeUnit.SECONDS), "트랜잭션 1이 락을 획득해야 한다");

            Throwable thrown = null;
            try {
                tx.executeWithoutResult(status -> {
                    // 같은 커넥션 세션에만 짧은 락 대기 타임아웃 적용 — 다른 세션에는 영향 없음.
                    // SESSION 변수는 트랜잭션 롤백으로 되돌아가지 않고, HikariCP도 반환 시 초기화하지
                    // 않으므로, 같은 물리 커넥션이 재사용되면 후속 테스트에 영향을 줄 수 있다.
                    // 원래 값을 저장했다가 finally에서 복원한다.
                    Object original = entityManager
                            .createNativeQuery("SELECT @@innodb_lock_wait_timeout")
                            .getSingleResult();
                    try {
                        entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = 1").executeUpdate();
                        noticeRepository.findByIdAndDeletedFalseForUpdate(target.getId());
                    } finally {
                        entityManager.createNativeQuery(
                                "SET SESSION innodb_lock_wait_timeout = " + original).executeUpdate();
                    }
                });
            } catch (Throwable t) {
                thrown = t;
            } finally {
                // assertion 실패로 회귀를 검출한 경우에도 트랜잭션 1을 무기한 대기시키지 않는다.
                release.countDown();
            }

            assertNotNull(thrown,
                    "락 보유 중인 행 재조회는 락 대기 타임아웃으로 실패해야 한다 " +
                            "(findByIdAndDeletedFalseForUpdate의 @Lock 선언 누락 회귀 의심)");

            holderFuture.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "실행기가 제한 시간 내에 종료되어야 한다");
        }
    }

    // ===================== (B) 실제 PATCH-DELETE 통합 동작 확인 =====================

    @Test
    @DisplayName("DELETE 커밋 후 대기 중이던 PATCH는 404로 종료되고 삭제 상태가 유지된다 (lost update 방지)")
    void patchAfterDeleteCommit_returns404_deletionPersists() throws Exception {
        Notice target = saveNotice("PATCH_DELETE_경합");
        Long noticeId = target.getId();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // 스레드 A: 락 획득 → softDelete() → 커밋 직전(해제 래치 대기)에 멈춘다.
            Future<?> deleteFuture = executor.submit(() -> tx.executeWithoutResult(status -> {
                Notice locked = noticeRepository.findByIdAndDeletedFalseForUpdate(noticeId)
                        .orElseThrow(() -> new IllegalStateException("테스트 대상 공지를 찾을 수 없습니다."));
                locked.softDelete();
                lockHeld.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // 메서드 종료 시 트랜잭션이 커밋되어 deleted=true가 확정된다.
            }));

            assertTrue(lockHeld.await(10, TimeUnit.SECONDS), "스레드 A가 락을 획득하고 softDelete를 수행해야 한다");

            // 스레드 B: A의 락에 막혀 A가 커밋할 때까지 반환되지 않는다.
            Future<Void> updateFuture = executor.submit(() -> {
                noticeService.updateNotice(noticeId, NoticeUpdateRequest.builder().title("PATCH로 되살리기 시도").build());
                return null;
            });

            release.countDown();

            Throwable updateOutcome = null;
            try {
                updateFuture.get(15, TimeUnit.SECONDS);
                fail("A가 이미 삭제한 공지에 대한 PATCH는 404(ResourceNotFoundException)로 종료되어야 한다");
            } catch (ExecutionException e) {
                updateOutcome = e.getCause();
            } catch (TimeoutException e) {
                // B가 끝내 반환되지 않아도 A는 이미 커밋 진행 중이므로 hang 없이 실패로 종료한다.
                fail("스레드 B(updateNotice)가 제한 시간 내에 종료되지 않았다");
            }

            assertInstanceOf(ResourceNotFoundException.class, updateOutcome,
                    "삭제된 공지에 대한 PATCH는 ResourceNotFoundException(404)이어야 한다");

            deleteFuture.get(15, TimeUnit.SECONDS);

            Notice finalState = noticeRepository.findById(noticeId).orElseThrow();
            assertTrue(finalState.getDeleted(), "DELETE로 확정된 삭제 상태가 PATCH로 되살아나지 않아야 한다");
            assertEquals(target.getTitle(), finalState.getTitle(), "제목도 PATCH로 변경되지 않아야 한다");
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "실행기가 제한 시간 내에 종료되어야 한다");
        }
    }
}
