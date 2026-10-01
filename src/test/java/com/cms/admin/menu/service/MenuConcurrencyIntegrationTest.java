package com.cms.admin.menu.service;

import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.log.domain.AdminActionLog;
import com.cms.admin.log.domain.AdminActionResult;
import com.cms.admin.log.repository.AdminActionLogRepository;
import com.cms.admin.menu.Menu;
import com.cms.admin.menu.MenuRepository;
import com.cms.admin.menu.dto.request.MenuCreateRequest;
import com.cms.admin.menu.dto.request.MenuMoveRequest;
import com.cms.admin.menu.dto.request.MenuOrderRequest;
import com.cms.admin.menu.dto.request.MenuOrderScope;
import com.cms.admin.menu.dto.request.MenuStructureRequest;
import com.cms.admin.menu.dto.request.MenuUpdateRequest;
import com.cms.admin.menu.dto.response.MenuMoveResponse;
import com.cms.admin.menu.dto.response.MenuOrderResponse;
import com.cms.admin.menu.dto.response.MenuResponse;
import com.cms.admin.menu.dto.response.MenuStructureResponse;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    AdminActionLogRepository adminActionLogRepository;

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

    // ============ 형제 순서 재조정(reorderMenus) 동시성 — PLAN-menu-reorder.md 쟁점 7 ============

    private Menu saveMenu(String name, Long upMenuNo, boolean useYn, Integer ord) {
        LocalDateTime now = LocalDateTime.now();
        Menu saved = menuRepository.save(Menu.builder()
                .menuName(name)
                .useYn(useYn)
                .ord(ord)
                .upMenuNo(upMenuNo)
                .createDate(now)
                .updateDate(now)
                .build());
        extraMenuIds.add(saved.getMenuNo());
        return saved;
    }

    /** 형제의 현재 표시 순서(ord asc·null 먼저, menuNo asc) — 화면·사이드바 조회와 같은 기준. */
    private List<Long> displayOrder(Long upMenuNo) {
        var rows = upMenuNo == null
                ? menuRepository.findRootSiblingRows()
                : menuRepository.findSiblingRowsByUpMenuNo(upMenuNo);
        return rows.stream()
                .sorted(Comparator.comparing(MenuRepository.SiblingRow::ord,
                                Comparator.nullsFirst(Comparator.<Integer>naturalOrder()))
                        .thenComparing(MenuRepository.SiblingRow::menuNo))
                .map(MenuRepository.SiblingRow::menuNo)
                .toList();
    }

    private static MenuOrderRequest orderRequest(Long upMenuNo, MenuOrderScope scope, Long... menuNos) {
        return MenuOrderRequest.builder().upMenuNo(upMenuNo).scope(scope).menuNos(List.of(menuNos)).build();
    }

    /**
     * 대상 스레드(재조정·이동)의 {@code findByIdForUpdate} 호출 지점에서만 멈추는 테스트 advice.
     * 제품 코드에 latch를 넣지 않는다. 멈추는 지점은 (a) 첫 잠금 호출 직전(스냅샷·검증 앞), (b) 지정한 menuNo를
     * 잠근 직후 — 둘을 함께 지정하면 (a)→(b) 순서로 두 번 멈춘다((b)는 {@code reachedAfter}/{@code releaseAfter} 사용).
     * 다른 스레드의 호출은 통과시키되 대기 대상 menuNo에 한해 DB 연결 id만 기록한다.
     * {@code failAtCommit}이면 대상 스레드의 첫 잠금 호출에서 커밋 직전 예외를 던지는 트랜잭션 동기화를 등록해,
     * 서비스 메서드 내부 예외가 아니라 <b>실제 커밋 단계 예외</b>를 유발한다.
     */
    private final class LockProbe implements MethodInterceptor {
        final AtomicReference<Thread> probedThread = new AtomicReference<>();
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch reachedAfter = new CountDownLatch(1);
        final CountDownLatch releaseAfter = new CountDownLatch(1);
        final CountDownLatch waiterEntered = new CountDownLatch(1);
        final AtomicLong holderConnection = new AtomicLong();
        final AtomicLong waiterConnection = new AtomicLong();
        final Map<Long, String> lockedNames = new ConcurrentHashMap<>();
        private final AtomicBoolean firstCallSeen = new AtomicBoolean();
        private final boolean pauseBeforeFirstLock;
        private final Long pauseAfterMenuNo;
        private final Long waiterTargetMenuNo;
        private boolean failAtCommit;

        LockProbe(boolean pauseBeforeFirstLock, Long pauseAfterMenuNo, Long waiterTargetMenuNo) {
            this.pauseBeforeFirstLock = pauseBeforeFirstLock;
            this.pauseAfterMenuNo = pauseAfterMenuNo;
            this.waiterTargetMenuNo = waiterTargetMenuNo;
        }

        LockProbe failAtCommit() {
            this.failAtCommit = true;
            return this;
        }

        @Override
        public Object invoke(org.aopalliance.intercept.MethodInvocation invocation) throws Throwable {
            if (invocation.getMethod().getName().equals("findAllForUpdate")) {
                // 전체 행 잠금(생성·구조 반영·accessRole 수정)의 첫 조회 — 다른 스레드의 호출이면 락 대기 진입으로 기록한다.
                if (Thread.currentThread() != probedThread.get() && waiterTargetMenuNo != null) {
                    waiterConnection.set(connectionId());
                    waiterEntered.countDown();
                }
                return invocation.proceed();
            }
            if (!invocation.getMethod().getName().equals("findByIdForUpdate")) {
                return invocation.proceed();
            }
            Long menuNo = (Long) invocation.getArguments()[0];
            if (Thread.currentThread() != probedThread.get()) {
                if (waiterTargetMenuNo != null && waiterTargetMenuNo.equals(menuNo)) {
                    waiterConnection.set(connectionId());
                    waiterEntered.countDown();
                }
                return invocation.proceed();
            }
            if (firstCallSeen.compareAndSet(false, true)) {
                holderConnection.set(connectionId());
                if (failAtCommit) {
                    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                            new org.springframework.transaction.support.TransactionSynchronization() {
                                @Override
                                public void beforeCommit(boolean readOnly) {
                                    throw new IllegalStateException("simulated commit-time failure");
                                }
                            });
                }
                if (pauseBeforeFirstLock) {
                    reached.countDown();
                    await(release, "대상 스레드 정지 해제(첫 잠금 직전)");
                }
            }
            Object result = invocation.proceed();
            ((Optional<?>) result).ifPresent(m -> lockedNames.put(menuNo, ((Menu) m).getMenuName()));
            if (pauseAfterMenuNo != null && menuNo.equals(pauseAfterMenuNo)) {
                // (a)와 (b)를 모두 지정했으면 (b)는 별도 latch를 쓴다.
                boolean both = pauseBeforeFirstLock;
                (both ? reachedAfter : reached).countDown();
                await(both ? releaseAfter : release, "대상 스레드 정지 해제(지정 행 잠금 직후)");
            }
            return result;
        }

        private long connectionId() {
            return ((Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult()).longValue();
        }
    }

    private Future<MenuOrderResponse> submitReorder(ExecutorService executor, LockProbe probe, MenuOrderRequest request) {
        return executor.submit(() -> {
            probe.probedThread.set(Thread.currentThread());
            return menuService.reorderMenus(request);
        });
    }

    /** ① reorder 선행: 마지막 형제까지 잠근 채 멈춘 사이 동시 수정/비활성화가 실제 DB 락 대기에 걸리고, 해제 후 둘 다 반영된다. */
    private void verifyReorderFirst_thenOtherWriteApplied(boolean deactivateInsteadOfRename) throws Exception {
        Menu parent = saveMenu("재조정 잠금 대기 부모", null, true, 900);
        Menu c1 = saveMenu("형제1", parent.getMenuNo(), true, 0);
        Menu c2 = saveMenu("형제2", parent.getMenuNo(), true, 1);
        Menu c3 = saveMenu("형제3", parent.getMenuNo(), true, 2);
        Long target = c1.getMenuNo(); // 재조정이 이미 잠근 형제 중 하나 — 이 행을 쓰려는 동시 작업이 대기해야 한다
        LockProbe probe = new LockProbe(false, c3.getMenuNo(), target);
        Advised repositoryProxy = (Advised) menuRepository;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        repositoryProxy.addAdvice(0, probe);
        try {
            Future<MenuOrderResponse> reorder = submitReorder(executor, probe,
                    orderRequest(parent.getMenuNo(), MenuOrderScope.ALL, c3.getMenuNo(), c1.getMenuNo(), c2.getMenuNo()));
            await(probe.reached, "재조정이 형제 3개를 모두 잠금");

            Future<?> other = executor.submit(() -> {
                if (deactivateInsteadOfRename) {
                    menuService.deactivateMenu(target);
                } else {
                    menuService.updateMenu(target, MenuUpdateRequest.builder().menuName("동시에 바뀐 이름").build());
                }
            });
            await(probe.waiterEntered, "동시 작업의 잠금 조회 진입");
            assertDatabaseLockWait(probe.holderConnection.get(), probe.waiterConnection.get());

            probe.release.countDown();
            reorder.get(10, TimeUnit.SECONDS);
            other.get(10, TimeUnit.SECONDS);
        } finally {
            probe.release.countDown();
            try {
                finishWorkers(executor, List.of());
            } finally {
                repositoryProxy.removeAdvice(probe);
            }
        }

        Menu finalC1 = menuRepository.findById(target).orElseThrow();
        assertEquals(1, finalC1.getOrd(), "재조정 결과(c3=0, c1=1, c2=2)가 반영되어야 한다");
        assertEquals(deactivateInsteadOfRename, !finalC1.getUseYn(),
                deactivateInsteadOfRename ? "동시 비활성화가 재조정 커밋으로 되돌아가면 안 된다" : "이름만 바꿨으므로 활성 상태는 유지");
        assertEquals(deactivateInsteadOfRename ? "형제1" : "동시에 바뀐 이름", finalC1.getMenuName(),
                "동시 수정이 재조정 커밋으로 유실되면 안 된다");
        assertEquals(List.of(c3.getMenuNo(), c1.getMenuNo(), c2.getMenuNo()), displayOrder(parent.getMenuNo()));
    }

    @Test
    @DisplayName("재조정 선행: 동시 이름 수정은 실제 락 대기 후 둘 다 반영되고 이름이 유실되지 않는다")
    void reorderFirst_concurrentRenameWaits_bothApplied() throws Exception {
        verifyReorderFirst_thenOtherWriteApplied(false);
    }

    @Test
    @DisplayName("재조정 선행: 동시 비활성화는 실제 락 대기 후 둘 다 반영되고 비활성화가 되돌아가지 않는다")
    void reorderFirst_concurrentDeactivateWaits_bothApplied() throws Exception {
        verifyReorderFirst_thenOtherWriteApplied(true);
    }

    /**
     * ①-b (이 설계의 핵심 구간): 재조정이 형제 id 스냅샷을 읽은 뒤 첫 잠금 직전에 멈춘 사이 다른 트랜잭션이
     * 형제의 이름 변경·비활성화를 커밋해도, 뒤이은 잠금 조회가 최신 엔티티를 적재해 변경을 되돌리지 않는다.
     */
    @Test
    @DisplayName("스냅샷 이후·첫 잠금 이전에 커밋된 이름 변경·비활성화는 재조정 후에도 보존된다")
    void commitBetweenSnapshotAndFirstLock_isPreservedByLockingRead() throws Exception {
        Menu parent = saveMenu("재조정 스냅샷 부모", null, true, 901);
        Menu c1 = saveMenu("형제1", parent.getMenuNo(), true, 0);
        Menu c2 = saveMenu("형제2", parent.getMenuNo(), true, 1);
        Menu c3 = saveMenu("형제3", parent.getMenuNo(), true, 2);
        LockProbe probe = new LockProbe(true, null, null);
        Advised repositoryProxy = (Advised) menuRepository;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        repositoryProxy.addAdvice(0, probe);
        try {
            Future<MenuOrderResponse> reorder = submitReorder(executor, probe,
                    orderRequest(parent.getMenuNo(), MenuOrderScope.ALL, c3.getMenuNo(), c1.getMenuNo(), c2.getMenuNo()));
            await(probe.reached, "재조정이 스냅샷을 읽고 첫 잠금 직전에 정지");

            // 정지된 사이 다른 트랜잭션이 형제 2·3을 각각 수정·비활성화하고 커밋한다(아직 잠기지 않은 행이라 대기 없음).
            menuService.updateMenu(c2.getMenuNo(), MenuUpdateRequest.builder().menuName("스냅샷 이후 바뀐 이름").build());
            menuService.deactivateMenu(c3.getMenuNo());

            probe.release.countDown();
            reorder.get(10, TimeUnit.SECONDS);
        } finally {
            probe.release.countDown();
            try {
                finishWorkers(executor, List.of());
            } finally {
                repositoryProxy.removeAdvice(probe);
            }
        }

        assertEquals("스냅샷 이후 바뀐 이름", probe.lockedNames.get(c2.getMenuNo()),
                "잠금 조회가 최신 이름을 적재해야 한다(오래된 스냅샷이 아니라)");
        Menu finalC2 = menuRepository.findById(c2.getMenuNo()).orElseThrow();
        Menu finalC3 = menuRepository.findById(c3.getMenuNo()).orElseThrow();
        assertEquals("스냅샷 이후 바뀐 이름", finalC2.getMenuName(), "이름 변경이 유실되면 안 된다");
        assertFalse(finalC3.getUseYn(), "비활성화가 되돌아가면 안 된다");
        assertEquals(0, finalC3.getOrd());
        assertEquals(1, menuRepository.findById(c1.getMenuNo()).orElseThrow().getOrd());
        assertEquals(2, finalC2.getOrd());
    }

    /** ①-c: ACTIVE 재검증 — 정지된 사이 활성 집합이 바뀌면 409이고 어떤 ord도 바뀌지 않는다. */
    private void verifyActiveRecheckConflict(boolean deactivateActiveSibling) throws Exception {
        Menu parent = saveMenu("재조정 ACTIVE 부모", null, true, 902);
        Menu c1 = saveMenu("형제1(활성)", parent.getMenuNo(), true, 0);
        Menu c2 = saveMenu("형제2(비활성)", parent.getMenuNo(), false, 1);
        Menu c3 = saveMenu("형제3(활성)", parent.getMenuNo(), true, 2);
        LockProbe probe = new LockProbe(true, null, null);
        Advised repositoryProxy = (Advised) menuRepository;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        repositoryProxy.addAdvice(0, probe);
        try {
            Future<MenuOrderResponse> reorder = submitReorder(executor, probe,
                    orderRequest(parent.getMenuNo(), MenuOrderScope.ACTIVE, c3.getMenuNo(), c1.getMenuNo()));
            await(probe.reached, "재조정이 스냅샷을 읽고 첫 잠금 직전에 정지");

            if (deactivateActiveSibling) {
                menuService.deactivateMenu(c1.getMenuNo());
            } else {
                menuService.updateMenu(c2.getMenuNo(), MenuUpdateRequest.builder().useYn(true).build());
            }

            probe.release.countDown();
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> reorder.get(10, TimeUnit.SECONDS));
            assertInstanceOf(ConflictException.class, thrown.getCause());
        } finally {
            probe.release.countDown();
            try {
                finishWorkers(executor, List.of());
            } finally {
                repositoryProxy.removeAdvice(probe);
            }
        }

        assertEquals(0, menuRepository.findById(c1.getMenuNo()).orElseThrow().getOrd());
        assertEquals(1, menuRepository.findById(c2.getMenuNo()).orElseThrow().getOrd());
        assertEquals(2, menuRepository.findById(c3.getMenuNo()).orElseThrow().getOrd());
    }

    @Test
    @DisplayName("ACTIVE 재검증: 정지된 사이 요청 대상 활성 형제가 비활성화되면 409이고 ord는 그대로")
    void activeReorder_siblingDeactivatedMeanwhile_conflictAndUnchanged() throws Exception {
        verifyActiveRecheckConflict(true);
    }

    @Test
    @DisplayName("ACTIVE 재검증: 정지된 사이 숨은 비활성 형제가 재활성화되면 409이고 ord는 그대로")
    void activeReorder_hiddenSiblingReactivatedMeanwhile_conflictAndUnchanged() throws Exception {
        verifyActiveRecheckConflict(false);
    }

    /**
     * ② 잠금 범위 실증: 재조정이 형제 행을 모두 잡고 멈춘 상태에서, 다른 부모의 행·루트 행·재조정 대상의 부모 행은
     * 락 대기 없이 수정된다(잠금이 테이블 전체가 아니고 부모 행도 잠그지 않음을 실제 MariaDB로 증명).
     */
    @Test
    @DisplayName("잠금 범위: 재조정이 형제를 잡고 있어도 다른 그룹 행·부모 행은 대기 없이 수정된다")
    void reorderHoldsOnlySiblingRows_otherRowsNotBlocked() throws Exception {
        Menu parent = saveMenu("재조정 범위 부모", null, true, 903);
        Menu c1 = saveMenu("형제1", parent.getMenuNo(), true, 0);
        Menu c2 = saveMenu("형제2", parent.getMenuNo(), true, 1);
        Menu c3 = saveMenu("형제3", parent.getMenuNo(), true, 2);
        Menu otherParent = saveMenu("다른 그룹 부모", null, true, 904);
        Menu otherChild = saveMenu("다른 그룹 자식", otherParent.getMenuNo(), true, 0);
        LockProbe probe = new LockProbe(false, c3.getMenuNo(), null);
        Advised repositoryProxy = (Advised) menuRepository;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        repositoryProxy.addAdvice(0, probe);
        try {
            Future<MenuOrderResponse> reorder = submitReorder(executor, probe,
                    orderRequest(parent.getMenuNo(), MenuOrderScope.ALL, c3.getMenuNo(), c2.getMenuNo(), c1.getMenuNo()));
            await(probe.reached, "재조정이 형제 3개를 모두 잠금");

            tx.executeWithoutResult(status -> {
                Number original = (Number) entityManager
                        .createNativeQuery("SELECT @@session.innodb_lock_wait_timeout").getSingleResult();
                Object isolation = entityManager.createNativeQuery("SELECT @@tx_isolation").getSingleResult();
                System.out.printf("메뉴 재조정 잠금 범위 실증: isolation=%s%n", isolation);
                try {
                    // 잠금 대기가 생기면 1초 뒤 PessimisticLockingFailureException — 테이블 전체 잠금이면 여기서 실패한다.
                    entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = 1").executeUpdate();
                    menuService.updateMenu(otherChild.getMenuNo(), MenuUpdateRequest.builder().menuName("다른 그룹 자식 수정").build());
                    menuService.updateMenu(otherParent.getMenuNo(), MenuUpdateRequest.builder().menuName("다른 그룹 부모 수정").build());
                    menuService.updateMenu(parent.getMenuNo(), MenuUpdateRequest.builder().menuName("재조정 대상의 부모 수정").build());
                } finally {
                    entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = " + original).executeUpdate();
                }
            });

            probe.release.countDown();
            reorder.get(10, TimeUnit.SECONDS);
        } finally {
            probe.release.countDown();
            try {
                finishWorkers(executor, List.of());
            } finally {
                repositoryProxy.removeAdvice(probe);
            }
        }

        assertEquals("다른 그룹 자식 수정", menuRepository.findById(otherChild.getMenuNo()).orElseThrow().getMenuName());
        assertEquals("재조정 대상의 부모 수정", menuRepository.findById(parent.getMenuNo()).orElseThrow().getMenuName());
        assertEquals(List.of(c3.getMenuNo(), c2.getMenuNo(), c1.getMenuNo()), displayOrder(parent.getMenuNo()));
    }

    /**
     * ③ 루트 재조정 vs 자식 재활성화(자식→부모 잠금): 재조정이 부모(=루트 형제)를 잡고 있는 동안 재활성화는 부모 대기에 걸리지만
     * 재조정은 자식을 기다리지 않으므로 교착 없이 둘 다 완료된다. 루트 형제에는 시드 메뉴가 있으므로 현재 표시 순서 그대로
     * (=아무 ord도 바꾸지 않음) 요청해 공유 테스트 DB를 건드리지 않는다.
     */
    @Test
    @DisplayName("루트 재조정이 부모를 잡은 동안 자식 재활성화는 대기 후 교착 없이 완료된다")
    void rootReorder_vsChildReactivation_noDeadlock() throws Exception {
        Menu parent = saveMenu("교착 검증 부모", null, true, 905);
        Menu child = saveMenu("교착 검증 자식(비활성)", parent.getMenuNo(), false, 0);
        List<Long> rootsInDisplayOrder = displayOrder(null);
        Long lastRootMenuNo = rootsInDisplayOrder.stream().max(Long::compare).orElseThrow();
        LockProbe probe = new LockProbe(false, lastRootMenuNo, parent.getMenuNo());
        Advised repositoryProxy = (Advised) menuRepository;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        repositoryProxy.addAdvice(0, probe);
        try {
            Future<MenuOrderResponse> reorder = submitReorder(executor, probe,
                    MenuOrderRequest.builder().upMenuNo(null).scope(MenuOrderScope.ALL)
                            .menuNos(rootsInDisplayOrder).build());
            await(probe.reached, "재조정이 루트 형제를 모두 잠금");

            Future<?> reactivate = executor.submit(() ->
                    menuService.updateMenu(child.getMenuNo(), MenuUpdateRequest.builder().useYn(true).build()));
            await(probe.waiterEntered, "재활성화가 부모 행 잠금 조회에 진입");
            assertDatabaseLockWait(probe.holderConnection.get(), probe.waiterConnection.get());

            probe.release.countDown();
            reorder.get(10, TimeUnit.SECONDS);
            reactivate.get(10, TimeUnit.SECONDS); // 교착이면 예외(CannotAcquireLock/DeadlockLoser) 또는 시간 초과
        } finally {
            probe.release.countDown();
            try {
                finishWorkers(executor, List.of());
            } finally {
                repositoryProxy.removeAdvice(probe);
            }
        }

        assertTrue(menuRepository.findById(child.getMenuNo()).orElseThrow().getUseYn(), "재활성화가 완료되어야 한다");
        assertEquals(rootsInDisplayOrder, displayOrder(null), "표시 순서 그대로 요청했으므로 루트 순서가 변하면 안 된다");
    }

    /**
     * ⑤ 동시 생성 계약(약한 계약): 스냅샷 이후 커밋된 새 형제는 검증·재조정 대상이 아니다. 명시·중복 ord로 요청 형제들
     * 사이에 끼어들 수는 있어도 예외 없이 완료되고, 요청한 형제들끼리의 상대 순서는 보존되며 새 메뉴는 유지된다.
     */
    private void verifyConcurrentCreateContract(boolean explicitOrd) throws Exception {
        Menu parent = saveMenu("재조정 동시 생성 부모", null, true, 906);
        Menu a = saveMenu("형제A", parent.getMenuNo(), true, 0);
        Menu b = saveMenu("형제B", parent.getMenuNo(), true, 0); // ord 중복은 정상 API로 생길 수 있다
        Menu c = saveMenu("형제C", parent.getMenuNo(), true, 0);
        LockProbe probe = new LockProbe(true, null, null);
        Advised repositoryProxy = (Advised) menuRepository;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Long createdMenuNo;
        repositoryProxy.addAdvice(0, probe);
        try {
            Future<MenuOrderResponse> reorder = submitReorder(executor, probe,
                    orderRequest(parent.getMenuNo(), MenuOrderScope.ALL, c.getMenuNo(), b.getMenuNo(), a.getMenuNo()));
            await(probe.reached, "재조정이 스냅샷을 읽고 첫 잠금 직전에 정지");

            var createRequest = MenuCreateRequest.builder().menuName("동시에 생성된 형제").upMenuNo(parent.getMenuNo());
            if (explicitOrd) {
                createRequest.ord(1);
            }
            createdMenuNo = menuService.createMenu(createRequest.build()).getMenuNo();
            extraMenuIds.add(createdMenuNo);

            probe.release.countDown();
            reorder.get(10, TimeUnit.SECONDS); // 예외 없이 완료되어야 한다
        } finally {
            probe.release.countDown();
            try {
                finishWorkers(executor, List.of());
            } finally {
                repositoryProxy.removeAdvice(probe);
            }
        }

        List<Long> finalOrder = displayOrder(parent.getMenuNo());
        assertTrue(finalOrder.contains(createdMenuNo), "동시에 생성된 메뉴는 유지되어야 한다");
        assertEquals(List.of(c.getMenuNo(), b.getMenuNo(), a.getMenuNo()),
                finalOrder.stream().filter(id -> !id.equals(createdMenuNo)).toList(),
                "요청한 형제들끼리의 상대 순서는 보존되어야 한다(새 메뉴의 위치는 보장하지 않음)");
    }

    @Test
    @DisplayName("동시 생성(명시 ord): 예외 없이 완료되고 요청 형제들의 상대 순서는 보존된다")
    void concurrentCreateWithExplicitOrd_relativeOrderPreserved() throws Exception {
        verifyConcurrentCreateContract(true);
    }

    @Test
    @DisplayName("동시 생성(자동 ord, 중복 ord 상태): 예외 없이 완료되고 요청 형제들의 상대 순서는 보존된다")
    void concurrentCreateWithAutoOrd_relativeOrderPreserved() throws Exception {
        verifyConcurrentCreateContract(false);
    }

    // ============ 부모 이동(moveMenu) 동시성 — PLAN-menu-move.md 쟁점 E ============

    private Future<MenuMoveResponse> submitMove(ExecutorService executor, LockProbe probe, Long menuNo, Long newParentNo) {
        return executor.submit(() -> {
            probe.probedThread.set(Thread.currentThread());
            return menuService.moveMenu(menuNo, MenuMoveRequest.toParent(newParentNo));
        });
    }

    /** 프로브를 걷어내고 워커를 정리한다(공유 Spring context에 test advice를 남기지 않는다). */
    private void finishProbed(ExecutorService executor, LockProbe probe, Advised repositoryProxy) throws Exception {
        probe.release.countDown();
        probe.releaseAfter.countDown();
        try {
            finishWorkers(executor, List.of());
        } finally {
            repositoryProxy.removeAdvice(probe);
        }
    }

    private static Throwable failureOf(Future<?> future) throws Exception {
        try {
            future.get(15, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private MenuMoveResponse move(Long menuNo, Long newParentNo) {
        return menuService.moveMenu(menuNo, MenuMoveRequest.toParent(newParentNo));
    }

    /**
     * ① 자식 검사는 잠금 뒤 최신값: 이동이 첫 잠금 직전에 정지한 사이 T 아래에 자식이 생성·커밋되면, 재개된 이동은
     * 잠근 최신 값으로 그 자식을 보고 400으로 끝난다(잠금 전에 자식을 검사하는 구현이면 놓치고 3단을 만든다).
     */
    @Test
    @DisplayName("이동 정지 중 대상 아래에 자식이 생성·커밋되면 재개 시 400이고 이동하지 않는다")
    void moveFirstLockPaused_childCreatedMeanwhile_rejectedAfterLock() throws Exception {
        Menu target = saveMenu("이동 대상", null, true, 950);
        Menu newParent = saveMenu("새 부모", null, true, 951);
        LockProbe probe = new LockProbe(true, null, null);
        Advised repositoryProxy = (Advised) menuRepository;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        repositoryProxy.addAdvice(0, probe);
        try {
            Future<MenuMoveResponse> move = submitMove(executor, probe, target.getMenuNo(), newParent.getMenuNo());
            await(probe.reached, "이동이 첫 잠금 직전에 정지");

            Long childNo = menuService.createMenu(MenuCreateRequest.builder()
                    .menuName("정지 중 생긴 자식").upMenuNo(target.getMenuNo()).build()).getMenuNo();
            extraMenuIds.add(childNo);

            probe.release.countDown();
            assertInstanceOf(InvalidRequestException.class, failureOf(move));
        } finally {
            finishProbed(executor, probe, repositoryProxy);
        }

        assertNull(menuRepository.findById(target.getMenuNo()).orElseThrow().getUpMenuNo(), "이동이 일어나면 안 된다");
    }

    /** ② N 최신 상태 재검증: 정지 중 N이 비활성화되거나 다른 부모 아래로 이동되면 재개된 이동은 400이다. */
    private void verifyNewParentChangedMeanwhile(boolean deactivate) throws Exception {
        Menu target = saveMenu("이동 대상", null, true, 952);
        Menu newParent = saveMenu("새 부모", null, true, 953);
        Menu otherRoot = saveMenu("다른 최상위", null, true, 954);
        LockProbe probe = new LockProbe(true, null, null);
        Advised repositoryProxy = (Advised) menuRepository;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        repositoryProxy.addAdvice(0, probe);
        try {
            Future<MenuMoveResponse> move = submitMove(executor, probe, target.getMenuNo(), newParent.getMenuNo());
            await(probe.reached, "이동이 첫 잠금 직전에 정지");

            if (deactivate) {
                menuService.deactivateMenu(newParent.getMenuNo());
            } else {
                move(newParent.getMenuNo(), otherRoot.getMenuNo()); // N이 다른 최상위 아래로 이동(N은 자식 없음)
            }

            probe.release.countDown();
            assertInstanceOf(InvalidRequestException.class, failureOf(move));
        } finally {
            finishProbed(executor, probe, repositoryProxy);
        }

        assertNull(menuRepository.findById(target.getMenuNo()).orElseThrow().getUpMenuNo(), "이동이 일어나면 안 된다");
    }

    @Test
    @DisplayName("이동 정지 중 새 부모가 비활성화되면 재개 시 400(활성 메뉴가 비활성 부모 아래 남지 않음)")
    void moveFirstLockPaused_newParentDeactivatedMeanwhile_400() throws Exception {
        verifyNewParentChangedMeanwhile(true);
    }

    @Test
    @DisplayName("이동 정지 중 새 부모가 다른 부모 아래로 이동되면 재개 시 400(3단 방지)")
    void moveFirstLockPaused_newParentMovedMeanwhile_400() throws Exception {
        verifyNewParentChangedMeanwhile(false);
    }

    /**
     * ③ 이동 선행: 이동이 T·N을 모두 잠근 채 멈춘 사이 N 비활성화가 실제 DB 락 대기에 걸리고, 해제 뒤 "활성 자식 존재"로
     * 409이므로 활성 T가 비활성 N 아래 남지 않는다.
     */
    @Test
    @DisplayName("이동이 새 부모를 잠근 동안 새 부모 비활성화는 락 대기 후 409 — 활성 메뉴가 비활성 부모 아래 남지 않는다")
    void moveHoldsLocks_parentDeactivateWaitsThenConflicts() throws Exception {
        Menu target = saveMenu("이동 대상", null, true, 960);
        Menu newParent = saveMenu("새 부모", null, true, 961);
        Long lastLocked = Math.max(target.getMenuNo(), newParent.getMenuNo());
        LockProbe probe = new LockProbe(false, lastLocked, newParent.getMenuNo());
        Advised repositoryProxy = (Advised) menuRepository;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        repositoryProxy.addAdvice(0, probe);
        try {
            Future<MenuMoveResponse> move = submitMove(executor, probe, target.getMenuNo(), newParent.getMenuNo());
            await(probe.reached, "이동이 두 행을 모두 잠금");

            Future<?> deactivate = executor.submit(() -> menuService.deactivateMenu(newParent.getMenuNo()));
            await(probe.waiterEntered, "비활성화의 잠금 조회 진입");
            assertDatabaseLockWait(probe.holderConnection.get(), probe.waiterConnection.get());

            probe.release.countDown();
            assertNull(failureOf(move), "이동은 성공해야 한다");
            assertInstanceOf(ConflictException.class, failureOf(deactivate), "이동 커밋 뒤 활성 자식이 보여 409여야 한다");
        } finally {
            finishProbed(executor, probe, repositoryProxy);
        }

        Menu finalTarget = menuRepository.findById(target.getMenuNo()).orElseThrow();
        Menu finalParent = menuRepository.findById(newParent.getMenuNo()).orElseThrow();
        assertEquals(newParent.getMenuNo(), finalTarget.getUpMenuNo());
        assertTrue(finalParent.getUseYn(), "새 부모는 비활성화되면 안 된다");
        assertFalse(!finalParent.getUseYn() && finalTarget.getUseYn(), "비활성 부모 아래 활성 자식 불변식");
    }

    /**
     * ④ 재조정과의 경합(정합성): 재조정이 O의 형제 스냅샷을 읽고 첫 잠금 직전에 정지한 사이 이동이 T를 N 아래로 옮겨
     * 커밋하면, 재개된 재조정은 잠근 T의 부모가 다름을 보고 409로 끝나며 어떤 형제의 ord도 바뀌지 않는다.
     */
    @Test
    @DisplayName("재조정 정지 중 그 형제가 다른 부모 아래로 이동되면 재개된 재조정은 409이고 ord는 불변")
    void reorderPaused_siblingMovedAway_reorderConflictsAndNothingChanges() throws Exception {
        Menu owner = saveMenu("재조정 부모 O", null, true, 970);
        Menu movedChild = saveMenu("이동될 형제 T", owner.getMenuNo(), true, 0);
        Menu stayChild = saveMenu("남는 형제 S", owner.getMenuNo(), true, 1);
        Menu newParent = saveMenu("새 부모 N", null, true, 971);
        LockProbe probe = new LockProbe(true, null, null);
        Advised repositoryProxy = (Advised) menuRepository;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        repositoryProxy.addAdvice(0, probe);
        try {
            Future<MenuOrderResponse> reorder = submitReorder(executor, probe,
                    orderRequest(owner.getMenuNo(), MenuOrderScope.ALL, stayChild.getMenuNo(), movedChild.getMenuNo()));
            await(probe.reached, "재조정이 스냅샷을 읽고 첫 잠금 직전에 정지");

            move(movedChild.getMenuNo(), newParent.getMenuNo());

            probe.release.countDown();
            assertInstanceOf(ConflictException.class, failureOf(reorder));
        } finally {
            finishProbed(executor, probe, repositoryProxy);
        }

        Menu finalMoved = menuRepository.findById(movedChild.getMenuNo()).orElseThrow();
        assertEquals(newParent.getMenuNo(), finalMoved.getUpMenuNo(), "이동은 유지되어야 한다");
        assertEquals(0, finalMoved.getOrd(), "이동된 행에 이전 그룹의 ord를 쓰면 안 된다(새 부모 아래 첫 자식 ord=0)");
        assertEquals(1, menuRepository.findById(stayChild.getMenuNo()).orElseThrow().getOrd(), "남는 형제의 ord는 불변");
    }

    /**
     * ⑤ 루트 재조정 ↔ 최상위 이동(일반 경합) 무교착: 재조정이 루트 형제를 오름차순으로 잠그다 N까지 잠근 채 멈추고(T는
     * 아직 안 잠금, N<T) 이동(T→N)이 시작한다. 이동이 menuNo 오름차순(N 먼저)으로 잠그면 N에서 대기하다 재조정이 끝난 뒤
     * 진행한다. T를 먼저 잡는 잘못된 순서라면 이동은 T를 잡고 N을 기다리고 재조정은 T를 기다려 교착한다 —
     * 이 테스트가 잘못된 잠금 순서를 잡는다.
     */
    @Test
    @DisplayName("루트 재조정이 N까지 잠근 동안 최상위 이동은 N에서 락 대기 후 교착 없이 완료된다(잠금 순서 = menuNo 오름차순)")
    void rootReorderHoldsSmallerId_rootMoveWaitsWithoutDeadlock() throws Exception {
        Menu newParent = saveMenu("새 부모 N", null, true, 980);   // 먼저 저장 → 더 작은 menuNo
        Menu target = saveMenu("이동 대상 T", null, true, 981);
        assertTrue(newParent.getMenuNo() < target.getMenuNo());
        List<Long> rootsInDisplayOrder = displayOrder(null);
        LockProbe probe = new LockProbe(false, newParent.getMenuNo(), newParent.getMenuNo());
        Advised repositoryProxy = (Advised) menuRepository;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        repositoryProxy.addAdvice(0, probe);
        try {
            Future<MenuOrderResponse> reorder = submitReorder(executor, probe,
                    MenuOrderRequest.builder().upMenuNo(null).scope(MenuOrderScope.ALL).menuNos(rootsInDisplayOrder).build());
            await(probe.reached, "재조정이 루트 형제를 N까지 잠금(T는 아직 안 잠금)");

            Future<MenuMoveResponse> move = executor.submit(() -> move(target.getMenuNo(), newParent.getMenuNo()));
            await(probe.waiterEntered, "이동의 첫 잠금 조회 진입");
            assertDatabaseLockWait(probe.holderConnection.get(), probe.waiterConnection.get());

            probe.release.countDown();
            assertNull(failureOf(reorder), "재조정이 교착 없이 완료되어야 한다");
            assertNull(failureOf(move), "이동이 교착 없이 완료되어야 한다");
        } finally {
            finishProbed(executor, probe, repositoryProxy);
        }

        assertEquals(newParent.getMenuNo(), menuRepository.findById(target.getMenuNo()).orElseThrow().getUpMenuNo());
    }

    /** ⑥ 서로 반대 방향의 동시 이동: 같은 규칙으로 잠그므로 정확히 하나만 성공하고 다른 하나는 400이며 교착이 없다. */
    @Test
    @DisplayName("A→B와 B→A 이동이 동시에 오면 정확히 하나만 성공하고 나머지는 400 — 교착·순환 없음")
    void oppositeMovesConcurrently_exactlyOneSucceeds() throws Exception {
        Menu a = saveMenu("A", null, true, 990);
        Menu b = saveMenu("B", null, true, 991);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<MenuMoveResponse>> futures = new ArrayList<>();
        try {
            futures.add(executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return move(a.getMenuNo(), b.getMenuNo());
            }));
            futures.add(executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return move(b.getMenuNo(), a.getMenuNo());
            }));
            int successes = 0;
            int invalid = 0;
            for (Future<MenuMoveResponse> future : futures) {
                Throwable failure = failureOf(future);
                if (failure == null) {
                    successes++;
                } else if (failure instanceof InvalidRequestException) {
                    invalid++;
                } else {
                    fail("예상 밖 예외(교착 포함): " + failure);
                }
            }
            assertEquals(1, successes);
            assertEquals(1, invalid);
        } finally {
            finishWorkers(executor, List.of());
        }

        boolean aUnderB = b.getMenuNo().equals(menuRepository.findById(a.getMenuNo()).orElseThrow().getUpMenuNo());
        boolean bUnderA = a.getMenuNo().equals(menuRepository.findById(b.getMenuNo()).orElseThrow().getUpMenuNo());
        assertTrue(aUnderB ^ bUnderA, "둘 중 하나만 상대 아래여야 한다(서로 자식이 되는 순환 금지)");
    }

    /**
     * ⑦ 이동이 T를 잠근 동안 T 아래 하위 생성(부모 행 잠금)은 실제 락 대기에 걸리고 둘 다 완료된다. 이동 뒤 T(이제 2단)에
     * 하위가 생기면 3단이 될 수 있는 것은 생성 API의 깊이 검증 부재 때문이며(알려진 한계, 계획 결정 6) 최종 상태는 단언하지 않는다.
     */
    @Test
    @DisplayName("이동이 대상을 잠근 동안 대상 아래 하위 생성은 락 대기 후 둘 다 완료된다")
    void moveHoldsTarget_childCreationWaitsThenCompletes() throws Exception {
        Menu target = saveMenu("이동 대상", null, true, 995);
        Menu newParent = saveMenu("새 부모", null, true, 996);
        Long lastLocked = Math.max(target.getMenuNo(), newParent.getMenuNo());
        LockProbe probe = new LockProbe(false, lastLocked, target.getMenuNo());
        Advised repositoryProxy = (Advised) menuRepository;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        repositoryProxy.addAdvice(0, probe);
        try {
            Future<MenuMoveResponse> move = submitMove(executor, probe, target.getMenuNo(), newParent.getMenuNo());
            await(probe.reached, "이동이 두 행을 모두 잠금");

            Future<MenuResponse> create = executor.submit(() -> menuService.createMenu(MenuCreateRequest.builder()
                    .menuName("이동 중 생성된 하위").upMenuNo(target.getMenuNo()).build()));
            await(probe.waiterEntered, "하위 생성의 부모 행 잠금 조회 진입");
            assertDatabaseLockWait(probe.holderConnection.get(), probe.waiterConnection.get());

            probe.release.countDown();
            assertNull(failureOf(move));
            MenuResponse created = create.get(15, TimeUnit.SECONDS);
            extraMenuIds.add(created.getMenuNo());
        } finally {
            finishProbed(executor, probe, repositoryProxy);
        }
    }

    /**
     * ⑧ 알려진 교착 계약 고정(계획 결정 9, 리뷰 반례): 재조정 R이 루트 스냅샷을 읽고 정지 → 이동 M이 T를 N 아래로 커밋 →
     * R이 N까지 잠근 뒤 재정지 → 재활성화 U가 T를 잠그고 N을 기다림 → R 재개 → 교착. 잠금 예외는 정확히 하나이고,
     * 피해자에 따라 결과가 다르다(둘 다 허용): 피해자가 R이면 U 성공(T 활성), U면 R은 부모 불일치로 409(T 비활성 유지).
     * 어느 쪽이든 M의 이동은 유지되고 부분 반영이 없다.
     */
    @Test
    @DisplayName("알려진 교착: 잠금 예외는 정확히 하나이고 피해자별 결과가 일관되며 이동은 유지된다")
    void knownDeadlock_exactlyOneLockFailure_consistentOutcome() throws Exception {
        Menu newParent = saveMenu("교착 N", null, true, 900);
        Menu target = saveMenu("교착 T(비활성)", null, false, 901);
        assertTrue(newParent.getMenuNo() < target.getMenuNo());
        List<Long> rootsBefore = displayOrder(null);
        LockProbe probe = new LockProbe(true, newParent.getMenuNo(), newParent.getMenuNo());
        Advised repositoryProxy = (Advised) menuRepository;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        repositoryProxy.addAdvice(0, probe);
        Throwable reorderFailure;
        Throwable reactivateFailure;
        try {
            Future<MenuOrderResponse> reorder = submitReorder(executor, probe,
                    MenuOrderRequest.builder().upMenuNo(null).scope(MenuOrderScope.ALL).menuNos(rootsBefore).build());
            await(probe.reached, "재조정이 루트 스냅샷을 읽고 첫 잠금 직전에 정지");

            move(target.getMenuNo(), newParent.getMenuNo()); // M: T(비활성)를 N(활성) 아래로 이동·커밋
            probe.release.countDown();
            await(probe.reachedAfter, "재조정이 N까지 잠근 채 재정지");

            Future<?> reactivate = executor.submit(() ->
                    menuService.updateMenu(target.getMenuNo(), MenuUpdateRequest.builder().useYn(true).build()));
            await(probe.waiterEntered, "재활성화가 T를 잠그고 부모 N 잠금 조회에 진입");
            assertDatabaseLockWait(probe.holderConnection.get(), probe.waiterConnection.get());

            probe.releaseAfter.countDown(); // R이 다음 형제 T를 기다림 → 교착
            reorderFailure = failureOf(reorder);
            reactivateFailure = failureOf(reactivate);
        } finally {
            finishProbed(executor, probe, repositoryProxy);
        }

        long lockFailures = java.util.stream.Stream.of(reorderFailure, reactivateFailure)
                .filter(f -> f instanceof PessimisticLockingFailureException).count();
        assertEquals(1, lockFailures, "교착 피해자는 정확히 하나여야 한다. reorder=" + reorderFailure + ", reactivate=" + reactivateFailure);

        Menu finalTarget = menuRepository.findById(target.getMenuNo()).orElseThrow();
        assertEquals(newParent.getMenuNo(), finalTarget.getUpMenuNo(), "M의 커밋된 이동은 유지되어야 한다(부분 반영 아님)");
        if (reorderFailure instanceof PessimisticLockingFailureException) {
            assertNull(reactivateFailure, "재조정이 피해자면 재활성화는 성공해야 한다");
            assertTrue(finalTarget.getUseYn());
        } else {
            assertInstanceOf(ConflictException.class, reorderFailure, "U가 피해자면 R은 이동된 T의 부모 불일치로 409여야 한다");
            assertFalse(finalTarget.getUseYn(), "U가 롤백됐으므로 T는 비활성 그대로");
        }
        assertEquals(rootsBefore.stream().filter(id -> !id.equals(target.getMenuNo())).toList(),
                displayOrder(null).stream().filter(id -> !id.equals(target.getMenuNo())).toList(),
                "남은 루트 순서는 불변");
    }

    private long maxActionLogId() {
        return adminActionLogRepository.findAll().stream().mapToLong(AdminActionLog::getId).max().orElse(0L);
    }

    private List<AdminActionLog> moveLogsAfter(long lastId) {
        return adminActionLogRepository.findAll().stream()
                .filter(log -> log.getId() > lastId && AdminActionTypes.MENU_MOVE.equals(log.getActionType()))
                .toList();
    }

    /** ⑨ 감사 커밋 순서(정상): 이동 성공은 커밋 뒤에 SUCCESS 1건, targetId = 이동한 메뉴 번호. */
    @Test
    @DisplayName("이동 성공: MENU_MOVE SUCCESS가 정확히 1건이고 targetId는 이동한 메뉴 번호")
    void move_success_recordsSuccessWithTargetId() {
        Menu target = saveMenu("감사 대상", null, true, 940);
        Menu newParent = saveMenu("감사 부모", null, true, 941);
        long before = maxActionLogId();

        move(target.getMenuNo(), newParent.getMenuNo());

        List<AdminActionLog> logs = moveLogsAfter(before);
        assertEquals(1, logs.size());
        assertEquals(AdminActionResult.SUCCESS, logs.get(0).getActionResult());
        assertEquals(target.getMenuNo(), logs.get(0).getTargetId());
    }

    /**
     * ⑨ 감사 커밋 순서(커밋 단계 실패): 서비스 내부 예외가 아니라 실제 커밋 직전 동기화 예외로 실패시키면 업무 변경은
     * 롤백되고 SUCCESS는 남지 않으며 FAIL(targetId null)만 남는다.
     */
    @Test
    @DisplayName("커밋 단계 실패: 이동은 롤백되고 SUCCESS는 없으며 FAIL(targetId null)만 기록된다")
    void move_commitTimeFailure_recordsFailNotSuccess() throws Exception {
        Menu target = saveMenu("감사 대상", null, true, 942);
        Menu newParent = saveMenu("감사 부모", null, true, 943);
        long before = maxActionLogId();
        LockProbe probe = new LockProbe(false, null, null).failAtCommit();
        Advised repositoryProxy = (Advised) menuRepository;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        repositoryProxy.addAdvice(0, probe);
        Throwable failure;
        try {
            failure = failureOf(submitMove(executor, probe, target.getMenuNo(), newParent.getMenuNo()));
        } finally {
            finishProbed(executor, probe, repositoryProxy);
        }

        assertTrue(failure != null, "커밋 단계 예외로 실패해야 한다");
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertTrue(String.valueOf(root.getMessage()).contains("simulated commit-time failure"), "원인: " + root);
        assertNull(menuRepository.findById(target.getMenuNo()).orElseThrow().getUpMenuNo(), "커밋 실패로 이동이 롤백되어야 한다");

        List<AdminActionLog> logs = moveLogsAfter(before);
        assertEquals(1, logs.size());
        assertEquals(AdminActionResult.FAIL, logs.get(0).getActionResult());
        assertNull(logs.get(0).getTargetId());
        assertTrue(logs.stream().noneMatch(log -> log.getActionResult() == AdminActionResult.SUCCESS), "SUCCESS가 남으면 안 된다");
    }

    // ============ 구조 일괄 반영(applyStructure) — PLAN-menu-structure-apply.md ============

    /** 현재 DB 전체를 요청 항목으로 만든다(base = 현재, 새 부모 = 현재, 배열 순서 = 표시 순서). */
    private List<MenuStructureRequest.Item> currentItems() {
        return menuRepository.findAllByOrderByOrdAscMenuNoAsc().stream()
                .map(menu -> MenuStructureRequest.Item.of(menu.getMenuNo(), menu.getUpMenuNo(), menu.getOrd(), menu.getUpMenuNo()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static MenuStructureRequest structureOf(List<MenuStructureRequest.Item> items) {
        MenuStructureRequest request = new MenuStructureRequest();
        request.setMenus(items);
        return request;
    }

    /** 항목 menuNo를 newParent 아래로 옮기고, 배열에서 beforeMenuNo 항목 바로 앞에 둔다(null이면 맨 끝). */
    private List<MenuStructureRequest.Item> withMoved(List<MenuStructureRequest.Item> items, Long menuNo, Long newParent, Long beforeMenuNo) {
        Menu current = menuRepository.findById(menuNo).orElseThrow();
        List<MenuStructureRequest.Item> result = new ArrayList<>(items);
        result.removeIf(item -> item.resolvedMenuNo().equals(menuNo));
        int position = result.size();
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i).resolvedMenuNo().equals(beforeMenuNo)) {
                position = i;
                break;
            }
        }
        result.add(position, MenuStructureRequest.Item.of(menuNo, current.getUpMenuNo(), current.getOrd(), newParent));
        return result;
    }

    private List<AdminActionLog> logsAfter(String actionType, long lastId) {
        return adminActionLogRepository.findAll().stream()
                .filter(log -> log.getId() > lastId && actionType.equals(log.getActionType()))
                .toList();
    }

    private long currentConnectionId() {
        return ((Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult()).longValue();
    }

    /** holder 연결이 잡은 락을 기다리는 트랜잭션이 하나 이상 생길 때까지 관측한다(sleep을 증거로 쓰지 않는다). */
    private void awaitSomeoneWaitingFor(long holder) throws Exception {
        try (Connection observer = DriverManager.getConnection(connectionDetails.getJdbcUrl(),
                "root", connectionDetails.getPassword());
             var query = observer.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.INNODB_LOCK_WAITS locks
                     JOIN information_schema.INNODB_TRX holding ON holding.trx_id = locks.blocking_trx_id
                     WHERE holding.trx_mysql_thread_id = ?
                     """)) {
            query.setLong(1, holder);
            query.setQueryTimeout(2);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            do {
                try (var result = query.executeQuery()) {
                    if (result.next() && result.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(25); // 관측 query의 polling 간격일 뿐, sleep을 잠금 증거로 쓰지 않는다.
            } while (System.nanoTime() < deadline);
            fail("실제 DB row-lock 대기를 관측하지 못함: holder=" + holder);
        }
    }

    /**
     * 별도 트랜잭션이 전체 메뉴 행을 잠근 채(필요하면 `afterLock`으로 쓰기까지 하고) 멈춘다. {@code locked}가 풀리면 잠금을
     * 쥐고 있는 것이고, {@code release}를 풀면 커밋한다.
     */
    private Future<?> holdAllRowLocks(ExecutorService executor, CountDownLatch locked, CountDownLatch release,
                                      AtomicLong holderConnection, Runnable afterLock) {
        return executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            menuRepository.findAllForUpdate();
            holderConnection.set(currentConnectionId());
            afterLock.run();
            locked.countDown();
            await(release, "전체 행 잠금 보유 트랜잭션 해제");
        }));
    }

    @Test
    @DisplayName("구조 반영: 서브트리를 다른 최상위 아래 지정한 위치로 옮기고, 한 건의 MENU_STRUCTURE_APPLY 감사 로그를 남긴다")
    void applyStructure_movesSubtreeToPosition_andLogsOnce() {
        Menu r1 = saveMenu("S-루트1", null, true, 950);
        Menu r2 = saveMenu("S-루트2", null, true, 951);
        Menu x = saveMenu("S-이동대상", r1.getMenuNo(), true, 0);
        Menu z = saveMenu("S-손자", x.getMenuNo(), true, 0);
        Menu y = saveMenu("S-기존자식", r2.getMenuNo(), true, 0);
        List<MenuStructureRequest.Item> items = withMoved(currentItems(), x.getMenuNo(), r2.getMenuNo(), y.getMenuNo());
        long before = maxActionLogId();

        MenuStructureResponse response = menuService.applyStructure(structureOf(items));

        assertEquals(2, response.getChanged()); // x(부모 변경) + y(ord 밀림). 값이 같은 다른 행은 쓰지 않는다
        Menu movedX = menuRepository.findById(x.getMenuNo()).orElseThrow();
        assertEquals(r2.getMenuNo(), movedX.getUpMenuNo());
        assertEquals(0, movedX.getOrd());
        assertEquals(1, menuRepository.findById(y.getMenuNo()).orElseThrow().getOrd());
        assertEquals(x.getMenuNo(), menuRepository.findById(z.getMenuNo()).orElseThrow().getUpMenuNo(), "손자는 서브트리째 따라간다");
        List<AdminActionLog> logs = logsAfter(AdminActionTypes.MENU_STRUCTURE_APPLY, before);
        assertEquals(1, logs.size());
        assertEquals(AdminActionResult.SUCCESS, logs.get(0).getActionResult());
        assertNull(logs.get(0).getTargetId());
    }

    @Test
    @DisplayName("전체 행 잠금을 쥔 트랜잭션이 있으면 구조 반영은 실제 락 대기 후 커밋 뒤에 적용된다")
    void applyStructure_waitsForAllRowLockHolder_thenApplies() throws Exception {
        Menu r1 = saveMenu("W-루트1", null, true, 952);
        Menu r2 = saveMenu("W-루트2", null, true, 953);
        // r2를 r1 바로 앞으로(최상위 그룹 안 위치 맞바꿈) — 시드 메뉴의 상대 순서는 그대로다
        List<MenuStructureRequest.Item> items = withMoved(currentItems(), r2.getMenuNo(), null, r1.getMenuNo());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        try {
            Future<?> holder = holdAllRowLocks(executor, locked, release, holderConnection, () -> { });
            await(locked, "전체 행 잠금 획득");
            Future<MenuStructureResponse> apply = executor.submit(() -> menuService.applyStructure(structureOf(items)));
            awaitSomeoneWaitingFor(holderConnection.get());
            assertFalse(apply.isDone(), "잠금 보유 중에는 반영이 끝나면 안 된다");

            release.countDown();
            MenuStructureResponse response = apply.get(15, TimeUnit.SECONDS);
            holder.get(5, TimeUnit.SECONDS);

            assertTrue(response.getChanged() >= 1);
            assertTrue(menuRepository.findById(r2.getMenuNo()).orElseThrow().getOrd()
                    < menuRepository.findById(r1.getMenuNo()).orElseThrow().getOrd(), "r2가 r1 앞으로 확정");
            assertNull(menuRepository.findById(r1.getMenuNo()).orElseThrow().getUpMenuNo());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("다른 트랜잭션이 메뉴를 INSERT·커밋한 뒤 도착한 낡은 구조 반영은 409이고 아무것도 바뀌지 않는다")
    void applyStructure_afterConcurrentInsert_conflictAndUnchanged() throws Exception {
        Menu r1 = saveMenu("I-루트1", null, true, 954);
        Menu r2 = saveMenu("I-루트2", null, true, 955);
        List<MenuStructureRequest.Item> staleItems = currentItems(); // 새 메뉴가 생기기 전 초안
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        AtomicReference<Long> insertedMenuNo = new AtomicReference<>();
        try {
            Future<?> holder = holdAllRowLocks(executor, locked, release, holderConnection, () -> {
                LocalDateTime now = LocalDateTime.now();
                Menu inserted = menuRepository.save(Menu.builder().menuName("I-동시 생성").useYn(true).ord(956)
                        .createDate(now).updateDate(now).build());
                menuRepository.flush(); // 커밋 전에 INSERT를 실제로 보내 락·갭을 쥔 상태를 만든다
                insertedMenuNo.set(inserted.getMenuNo());
            });
            await(locked, "전체 행 잠금 + INSERT");
            Future<MenuStructureResponse> apply = executor.submit(() -> menuService.applyStructure(structureOf(staleItems)));
            awaitSomeoneWaitingFor(holderConnection.get());

            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            extraMenuIds.add(insertedMenuNo.get());

            Throwable failure = failureOf(apply);
            assertInstanceOf(ConflictException.class, failure, "새 메뉴가 요청에 없으므로 낡은 초안 409여야 한다: " + failure);
            assertEquals(954, menuRepository.findById(r1.getMenuNo()).orElseThrow().getOrd());
            assertEquals(955, menuRepository.findById(r2.getMenuNo()).orElseThrow().getOrd());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("전체 행 잠금을 쥔 트랜잭션이 있으면 메뉴 생성도 실제 락 대기 후 커밋 뒤에 완료된다")
    void createMenu_waitsForAllRowLockHolder_thenCompletes() throws Exception {
        saveMenu("C-기존", null, true, 957);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        try {
            Future<?> holder = holdAllRowLocks(executor, locked, release, holderConnection, () -> { });
            await(locked, "전체 행 잠금 획득");
            Future<MenuResponse> create = executor.submit(() -> menuService.createMenu(
                    MenuCreateRequest.builder().menuName("C-대기 후 생성").build()));
            awaitSomeoneWaitingFor(holderConnection.get());
            assertFalse(create.isDone(), "잠금 보유 중에는 생성이 끝나면 안 된다");

            release.countDown();
            MenuResponse created = create.get(15, TimeUnit.SECONDS);
            holder.get(5, TimeUnit.SECONDS);
            extraMenuIds.add(created.getMenuNo());

            assertTrue(created.getOrd() > 957, "기존 최대 ord 뒤(맨 끝)에 생성된다: " + created.getOrd());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

}
