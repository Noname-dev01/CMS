package com.cms.admin.menu.service;

import com.cms.admin.menu.Menu;
import com.cms.admin.menu.MenuRepository;
import com.cms.admin.menu.dto.request.MenuUpdateRequest;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 비관적 락(PESSIMISTIC_WRITE)이 실제 트랜잭션에서 대상/부모 row의 쓰기를 직렬화하는지
 * 검증하는 통합 테스트. Mockito 기반 서비스 테스트는 findByIdForUpdate 호출 여부만 확인할 뿐
 * 실제 직렬화를 증명하지 못하므로 실제 MariaDB로 검증한다.
 *
 * 두 스레드가 각자 독립 트랜잭션과 비관적 락을 획득해야 하므로 이 테스트 클래스/메서드에는
 * {@code @Transactional}을 붙이지 않는다. 롤백이 없으므로 생성한 row는 {@link #cleanUp()}에서
 * 수동 삭제한다.
 *
 * Testcontainers가 띄우는 일회용 MariaDB로 실행된다 — 로컬 DB 기동·환경변수 주입 불필요,
 * Docker만 있으면 된다({@link MariaDbContainerSupport}).
 */
@SpringBootTest(classes = CmsTestApplication.class)
class MenuConcurrencyIntegrationTest extends MariaDbContainerSupport {

    @Autowired
    MenuService menuService;

    @Autowired
    MenuRepository menuRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcConnectionDetails connectionDetails;

    @PersistenceContext
    EntityManager entityManager;

    private Long parentMenuNo;
    private Long childMenuNo;
    private final List<Long> extraMenuIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        if (childMenuNo != null) {
            menuRepository.deleteById(childMenuNo);
        }
        if (parentMenuNo != null) {
            menuRepository.deleteById(parentMenuNo);
        }
        for (Long menuNo : extraMenuIds) {
            menuRepository.deleteById(menuNo);
        }
    }

    private Menu createMenu(String name) {
        LocalDateTime now = LocalDateTime.now();
        Menu saved = menuRepository.save(Menu.builder()
                .menuName(name)
                .useYn(true)
                .ord(0)
                .createDate(now)
                .updateDate(now)
                .build());
        extraMenuIds.add(saved.getMenuNo());
        return saved;
    }

    @Test
    void concurrentParentDeactivateAndChildReactivate_neverLeavesActiveChildUnderInactiveParent() throws Exception {
        LocalDateTime now = LocalDateTime.now();

        Menu parent = menuRepository.save(Menu.builder()
                .menuName("동시성 테스트 부모")
                .useYn(true)
                .ord(0)
                .createDate(now)
                .updateDate(now)
                .build());
        parentMenuNo = parent.getMenuNo();

        Menu child = menuRepository.save(Menu.builder()
                .menuName("동시성 테스트 자식")
                .useYn(false)
                .ord(0)
                .upMenuNo(parentMenuNo)
                .createDate(now)
                .updateDate(now)
                .build());
        childMenuNo = child.getMenuNo();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<?>> workers = new ArrayList<>();
        try {
            workers.add(executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    menuService.deactivateMenu(parentMenuNo);
                } catch (ConflictException expected) {
                    // 자식이 먼저 활성화됐다면 정상적인 거절이다. DB 예외는 흡수하지 않는다.
                }
                return null;
            }));
            workers.add(executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    menuService.updateMenu(childMenuNo, MenuUpdateRequest.builder().useYn(true).build());
                } catch (InvalidRequestException expected) {
                    // 부모가 먼저 비활성화됐다면 정상적인 거절이다.
                }
                return null;
            }));
            for (Future<?> worker : workers) {
                worker.get(10, TimeUnit.SECONDS);
            }
        } finally {
            finishWorkers(executor, workers);
        }

        Menu finalParent = menuRepository.findById(parentMenuNo).orElseThrow();
        Menu finalChild = menuRepository.findById(childMenuNo).orElseThrow();

        boolean invariantViolated = !finalParent.getUseYn() && finalChild.getUseYn();
        assertFalse(invariantViolated,
                "비활성 부모 아래 활성 자식이 존재해서는 안 된다. parent.useYn=" + finalParent.getUseYn()
                        + ", child.useYn=" + finalChild.getUseYn());
    }

    /**
     * 감사 M-04(같은 행 쓰기의 비관적 잠금 일관화) 보조 검증 — 잠금 프리미티브 실증.
     * 수정 후 일반 수정(useYn 미포함)이 실제로 사용하는 잠금 조회({@code findByIdForUpdate})를
     * 트랜잭션 1에서 직접 호출해, 이 잠금이 실제로 동시 {@code deactivateMenu()}를 락 대기
     * 타임아웃으로 막는지 확인한다. {@code deactivateMenu()}는 수정 전부터 이미 같은 잠금
     * 조회를 썼으므로 이 테스트 자체는 수정 전/후 모두 통과한다 — "일반 수정이 이제 이 잠금을
     * 획득한다"는 사실은 {@link com.cms.admin.menu.service.MenuServiceTest}의 stub 검증이
     * 담당하고, "잠금이 실제로 경합을 막는다"는 이 테스트가 담당한다. lost update 자체의
     * 회귀 재현·수정 증명은 아래 A-first/B-first 서비스 경합 시험이 담당한다.
     *
     * <p>{@code AdminMemberUpdateConcurrencyIntegrationTest}의 {@code guardQuery_actuallyAcquiresRowLocks}와
     * 동일한 락 실증 기법(세션별 {@code innodb_lock_wait_timeout} 단축)을 사용한다.
     */
    @Test
    @DisplayName("동시성 방어(락 실증): 일반 수정이 쓰는 잠금 조회는 실제로 동시 비활성화를 락 대기 타임아웃으로 막는다")
    void generalEditHoldsLock_blocksConcurrentDeactivate() throws Exception {
        Menu menu = createMenu("잠금 실증 대상");
        Long menuNo = menu.getMenuNo();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        List<Future<?>> workers = new ArrayList<>();
        try {
            workers.add(executor.submit(() -> tx.execute(status -> {
                menuRepository.findByIdForUpdate(menuNo).orElseThrow();
                lockHeld.countDown();
                await(release, "잠금 실증 holder 해제");
                return null;
            })));
            await(lockHeld, "일반 수정 트랜잭션 잠금 획득");

            PessimisticLockingFailureException thrown = assertThrows(PessimisticLockingFailureException.class,
                    () -> tx.executeWithoutResult(status -> {
                        // 같은 커넥션 세션에만 짧은 락 대기 타임아웃을 적용한다.
                        // HikariCP는 세션 변수를 초기화하지 않으므로 풀 반환 전에 복원한다.
                        Number original = (Number) entityManager
                                .createNativeQuery("SELECT @@session.innodb_lock_wait_timeout").getSingleResult();
                        try {
                            entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = 1").executeUpdate();
                            menuService.deactivateMenu(menuNo);
                        } finally {
                            entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = " + original)
                                    .executeUpdate();
                        }
                    }));
            Throwable rootCause = thrown;
            while (rootCause.getCause() != null) {
                rootCause = rootCause.getCause();
            }
            // deadlock(1213), 잘못된 SQL, 세션 복원 실패 등을 timeout 성공으로 오인하지 않는다.
            assertEquals(1205, assertInstanceOf(SQLException.class, rootCause).getErrorCode());
        } finally {
            release.countDown();
            finishWorkers(executor, workers);
        }
        assertTrue(menuRepository.findById(menuNo).orElseThrow().getUseYn(),
                "실패한 비활성화 transaction은 상태를 변경하지 않아야 한다");
    }

    /**
     * 감사 M-04 / PR4-T1: 실제 서비스의 첫 잠금 조회 직후에만 test advice로 대기한다.
     * 제품 코드에 latch를 넣거나 entity를 먼저 읽지 않는다. 두 순서를 별개로 강제하고
     * INNODB_LOCK_WAITS에서 두 connection 사이의 FOR UPDATE 대기를 관측한 뒤 해제한다.
     */
    @Test
    void generalEditFirst_deactivateWaits_preservesBothChanges() throws Exception {
        verifyOrderedWrites(true);
    }

    @Test
    void deactivateFirst_generalEditWaits_readsCommittedInactiveState() throws Exception {
        verifyOrderedWrites(false);
    }

    private void verifyOrderedWrites(boolean editFirst) throws Exception {
        Menu menu = createMenu("동시성 테스트 대상");
        Long menuNo = menu.getMenuNo();
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch secondQueryEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Thread> firstThread = new AtomicReference<>();
        AtomicLong firstConnection = new AtomicLong();
        AtomicLong secondConnection = new AtomicLong();
        Advised repositoryProxy = (Advised) menuRepository;
        MethodInterceptor probe = invocation -> {
            if (!invocation.getMethod().getName().equals("findByIdForUpdate")
                    || !menuNo.equals(invocation.getArguments()[0])) {
                return invocation.proceed();
            }
            boolean first = Thread.currentThread() == firstThread.get();
            long connectionId = ((Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()")
                    .getSingleResult()).longValue();
            if (first) {
                firstConnection.set(connectionId);
            } else {
                secondConnection.set(connectionId);
                secondQueryEntered.countDown();
            }
            Object result = invocation.proceed();
            if (first) {
                firstLocked.countDown();
                await(releaseFirst, "선행 서비스 잠금 해제");
            } else {
                Menu latest = (Menu) ((Optional<?>) result).orElseThrow();
                // 후행 조회가 선행 commit 이후의 상태를 읽었는지 확인한다.
                assertEquals(editFirst ? "변경된 이름" : menu.getMenuName(), latest.getMenuName());
                assertEquals(editFirst, latest.getUseYn());
            }
            return result;
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<?>> workers = new ArrayList<>();
        repositoryProxy.addAdvice(0, probe);
        try {
            workers.add(executor.submit(() -> {
                firstThread.set(Thread.currentThread());
                writeMenu(menuNo, editFirst);
            }));
            await(firstLocked, "선행 서비스의 최초 대상 조회/행 잠금");
            workers.add(executor.submit(() -> writeMenu(menuNo, !editFirst)));
            await(secondQueryEntered, "후행 잠금 query 진입");
            assertDatabaseLockWait(firstConnection.get(), secondConnection.get());
            releaseFirst.countDown();
            for (Future<?> worker : workers) {
                worker.get(10, TimeUnit.SECONDS); // 성공/commit 확인. 예외는 흡수하지 않는다.
            }
        } finally {
            releaseFirst.countDown();
            try {
                finishWorkers(executor, workers);
            } finally {
                repositoryProxy.removeAdvice(probe); // 공유 Spring context에 test advice를 남기지 않는다.
            }
        }

        Menu finalMenu = menuRepository.findById(menuNo).orElseThrow();
        // 핵심 불변식: 비활성화 결과가 일반 수정의 낡은 useYn=true로 되살아나지 않고,
        // 일반 수정의 이름 변경도 조용히 유실되지 않는다 — 둘 다 성공해야 한다.
        assertFalse(finalMenu.getUseYn(),
                "행 잠금 직렬화로 비활성화 결과가 보존되어야 한다 (lost update 발생 의심). "
                        + "최종 useYn=" + finalMenu.getUseYn() + ", menuName=" + finalMenu.getMenuName());
        assertEquals("변경된 이름", finalMenu.getMenuName(),
                "일반 수정의 이름 변경도 함께 보존되어야 한다 (조용한 실패 의심). "
                        + "최종 useYn=" + finalMenu.getUseYn() + ", menuName=" + finalMenu.getMenuName());
    }

    private void writeMenu(Long menuNo, boolean edit) {
        if (edit) {
            menuService.updateMenu(menuNo, MenuUpdateRequest.builder().menuName("변경된 이름").build());
        } else {
            menuService.deactivateMenu(menuNo);
        }
    }

    private void assertDatabaseLockWait(long holder, long waiter) throws Exception {
        // 이 클래스는 일회용 MariaDBContainer 전용이다. 그 컨테이너는 root에도 같은
        // test password를 설정한다. PROCESS 권한은 관측 연결에만 쓰고 앱 계정은 바꾸지 않는다.
        try (Connection observer = DriverManager.getConnection(connectionDetails.getJdbcUrl(),
                "root", connectionDetails.getPassword());
             var query = observer.prepareStatement("""
                     SELECT waiting.trx_query FROM information_schema.INNODB_LOCK_WAITS locks
                     JOIN information_schema.INNODB_TRX waiting ON waiting.trx_id = locks.requesting_trx_id
                     JOIN information_schema.INNODB_TRX holding ON holding.trx_id = locks.blocking_trx_id
                     WHERE waiting.trx_mysql_thread_id = ? AND holding.trx_mysql_thread_id = ?
                     """)) {
            query.setLong(1, waiter);
            query.setLong(2, holder);
            query.setQueryTimeout(2);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            do {
                try (var result = query.executeQuery()) {
                    if (result.next()) {
                        assertTrue(result.getString(1).toLowerCase(java.util.Locale.ROOT).contains("for update"),
                                "UPDATE flush 대기가 아니라 최초 SELECT FOR UPDATE 대기여야 한다");
                        System.out.printf("M-04 DB lock wait verified: holder=%d waiter=%d%n", holder, waiter);
                        return;
                    }
                }
                Thread.sleep(25); // 관측 query의 polling 간격일 뿐, sleep을 잠금 증거로 쓰지 않는다.
            } while (System.nanoTime() < deadline);
            fail("실제 DB row-lock 대기를 관측하지 못함: holder=" + holder + ", waiter=" + waiter);
        }
    }

    private static void await(CountDownLatch latch, String phase) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "시간 초과: " + phase);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대기 중 중단: " + phase, e);
        }
    }

    private static void finishWorkers(ExecutorService executor, List<Future<?>> workers) throws Exception {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "worker가 종료되지 않음");
                fail("worker 정상 종료 시간 초과");
            }
            for (Future<?> worker : workers) {
                worker.get(1, TimeUnit.SECONDS); // latch 이전 실패와 cleanup 중 worker 실패도 드러낸다.
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            try {
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "중단 후 worker가 종료되지 않음");
            } finally {
                Thread.currentThread().interrupt();
            }
            throw interrupted;
        } finally {
            executor.shutdownNow();
        }
    }
}
