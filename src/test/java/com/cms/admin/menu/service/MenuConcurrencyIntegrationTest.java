package com.cms.admin.menu.service;

import com.cms.admin.menu.Menu;
import com.cms.admin.menu.MenuRepository;
import com.cms.admin.menu.dto.request.MenuCreateRequest;
import com.cms.admin.menu.dto.request.MenuOrderRequest;
import com.cms.admin.menu.dto.request.MenuOrderScope;
import com.cms.admin.menu.dto.request.MenuUpdateRequest;
import com.cms.admin.menu.dto.response.MenuOrderResponse;
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
     * 재조정 스레드의 {@code findByIdForUpdate} 호출 지점에서만 멈추는 테스트 advice.
     * 제품 코드에 latch를 넣지 않는다. 멈추는 지점은 (a) 첫 잠금 호출 직전(형제 id 스냅샷은 이미 읽은 뒤),
     * (b) 지정한 menuNo를 잠근 직후(= 마지막 형제까지 잠근 상태) 둘 중 하나다.
     * 다른 스레드의 호출은 통과시키되 대기 대상 menuNo에 한해 DB 연결 id만 기록한다.
     */
    private final class ReorderProbe implements MethodInterceptor {
        final AtomicReference<Thread> reorderThread = new AtomicReference<>();
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch waiterEntered = new CountDownLatch(1);
        final AtomicLong holderConnection = new AtomicLong();
        final AtomicLong waiterConnection = new AtomicLong();
        final Map<Long, String> lockedNames = new ConcurrentHashMap<>();
        private final AtomicBoolean firstCallSeen = new AtomicBoolean();
        private final boolean pauseBeforeFirstLock;
        private final Long pauseAfterMenuNo;
        private final Long waiterTargetMenuNo;

        ReorderProbe(boolean pauseBeforeFirstLock, Long pauseAfterMenuNo, Long waiterTargetMenuNo) {
            this.pauseBeforeFirstLock = pauseBeforeFirstLock;
            this.pauseAfterMenuNo = pauseAfterMenuNo;
            this.waiterTargetMenuNo = waiterTargetMenuNo;
        }

        @Override
        public Object invoke(org.aopalliance.intercept.MethodInvocation invocation) throws Throwable {
            if (!invocation.getMethod().getName().equals("findByIdForUpdate")) {
                return invocation.proceed();
            }
            Long menuNo = (Long) invocation.getArguments()[0];
            if (Thread.currentThread() != reorderThread.get()) {
                if (waiterTargetMenuNo != null && waiterTargetMenuNo.equals(menuNo)) {
                    waiterConnection.set(connectionId());
                    waiterEntered.countDown();
                }
                return invocation.proceed();
            }
            if (firstCallSeen.compareAndSet(false, true)) {
                holderConnection.set(connectionId());
                if (pauseBeforeFirstLock) {
                    reached.countDown();
                    await(release, "재조정 스레드 정지 해제(첫 잠금 직전)");
                }
            }
            Object result = invocation.proceed();
            ((Optional<?>) result).ifPresent(m -> lockedNames.put(menuNo, ((Menu) m).getMenuName()));
            if (!pauseBeforeFirstLock && menuNo.equals(pauseAfterMenuNo)) {
                reached.countDown();
                await(release, "재조정 스레드 정지 해제(마지막 형제 잠금 직후)");
            }
            return result;
        }

        private long connectionId() {
            return ((Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult()).longValue();
        }
    }

    private Future<MenuOrderResponse> submitReorder(ExecutorService executor, ReorderProbe probe, MenuOrderRequest request) {
        return executor.submit(() -> {
            probe.reorderThread.set(Thread.currentThread());
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
        ReorderProbe probe = new ReorderProbe(false, c3.getMenuNo(), target);
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
        ReorderProbe probe = new ReorderProbe(true, null, null);
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
        ReorderProbe probe = new ReorderProbe(true, null, null);
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
        ReorderProbe probe = new ReorderProbe(false, c3.getMenuNo(), null);
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
        ReorderProbe probe = new ReorderProbe(false, lastRootMenuNo, parent.getMenuNo());
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
        ReorderProbe probe = new ReorderProbe(true, null, null);
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
}
