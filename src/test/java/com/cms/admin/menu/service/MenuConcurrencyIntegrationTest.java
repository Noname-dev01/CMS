package com.cms.admin.menu.service;

import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.log.domain.AdminActionLog;
import com.cms.admin.log.domain.AdminActionResult;
import com.cms.admin.log.repository.AdminActionLogRepository;
import com.cms.admin.menu.Menu;
import com.cms.admin.menu.MenuRepository;
import com.cms.admin.menu.dto.request.MenuCreateRequest;
import com.cms.admin.menu.dto.request.MenuStructureRequest;
import com.cms.admin.menu.dto.request.MenuUpdateRequest;
import com.cms.admin.menu.dto.response.MenuResponse;
import com.cms.admin.menu.dto.response.MenuStructureResponse;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
                    menuService.updateMenu(parentMenuNo, MenuUpdateRequest.builder().useYn(false).build());
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
     * 트랜잭션 1에서 직접 호출해, 이 잠금이 실제로 동시 비활성화(PATCH useYn=false)를 락 대기
     * 타임아웃으로 막는지 확인한다. 비활성화(PATCH useYn=false)는 수정 전부터 이미 같은 잠금
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
                            menuService.updateMenu(menuNo, MenuUpdateRequest.builder().useYn(false).build());
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
            menuService.updateMenu(menuNo, MenuUpdateRequest.builder().useYn(false).build());
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

    // ============ 공용 보조 메서드 ============

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

    private static Throwable failureOf(Future<?> future) throws Exception {
        try {
            future.get(15, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private long maxActionLogId() {
        return adminActionLogRepository.findAll().stream().mapToLong(AdminActionLog::getId).max().orElse(0L);
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


    @Test
    @DisplayName("동시에 두 최상위 메뉴를 생성해도 전체 행 잠금으로 직렬화되어 ord가 겹치지 않는다")
    void concurrentRootCreates_serializedWithDistinctOrd() throws Exception {
        saveMenu("D-기존", null, true, 958);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<MenuResponse>> creates = new ArrayList<>();
            for (String name : List.of("D-동시1", "D-동시2")) {
                creates.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return menuService.createMenu(MenuCreateRequest.builder().menuName(name).build());
                }));
            }
            MenuResponse first = creates.get(0).get(20, TimeUnit.SECONDS);
            MenuResponse second = creates.get(1).get(20, TimeUnit.SECONDS);
            extraMenuIds.add(first.getMenuNo());
            extraMenuIds.add(second.getMenuNo());

            assertTrue(first.getOrd() > 958 && second.getOrd() > 958, "기존 최대 ord 뒤에 생성된다");
            assertFalse(first.getOrd().equals(second.getOrd()), "직렬화되어 서로 다른 ord를 받는다: " + first.getOrd() + ", " + second.getOrd());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("전체 행 잠금을 쥔 트랜잭션이 있으면 이름만 바꾸는 단일 행 수정도 락 대기 후 완료된다(교착 없음)")
    void singleRowEdit_waitsForAllRowLockHolder_thenCompletes() throws Exception {
        Menu target = saveMenu("E-대상", null, true, 959);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        try {
            Future<?> holder = holdAllRowLocks(executor, locked, release, holderConnection, () -> { });
            await(locked, "전체 행 잠금 획득");
            Future<MenuResponse> edit = executor.submit(() -> menuService.updateMenu(target.getMenuNo(),
                    MenuUpdateRequest.builder().menuName("E-수정됨").build()));
            awaitSomeoneWaitingFor(holderConnection.get());
            assertFalse(edit.isDone(), "잠금 보유 중에는 수정이 끝나면 안 된다");

            release.countDown();
            assertEquals("E-수정됨", edit.get(15, TimeUnit.SECONDS).getMenuName());
            holder.get(5, TimeUnit.SECONDS);
            assertEquals(959, menuRepository.findById(target.getMenuNo()).orElseThrow().getOrd(), "수정은 ord를 바꾸지 않는다");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    // ============ 영구삭제(deleteMenu) — PLAN-menu-permanent-delete.md ============

    /**
     * 별도 트랜잭션이 대상 행 하나만 잠근 채(필요하면 {@code afterLock}으로 쓰기까지 하고) 멈춘다. {@code locked}가 풀리면 잠금을
     * 쥐고 있는 것이고, {@code release}를 풀면 커밋한다. 일반 수정·재활성화·삭제가 쓰는 단일 행 잠금과 같은 조회를 쓴다.
     */
    private Future<?> holdRowLock(ExecutorService executor, Long menuNo, CountDownLatch locked, CountDownLatch release,
                                  AtomicLong holderConnection, java.util.function.Consumer<Menu> afterLock) {
        return executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Menu target = menuRepository.findByIdForUpdate(menuNo).orElseThrow();
            holderConnection.set(currentConnectionId());
            afterLock.accept(target);
            locked.countDown();
            await(release, "단일 행 잠금 보유 트랜잭션 해제");
        }));
    }

    @Test
    @DisplayName("영구삭제: 비활성 잎 메뉴의 row가 사라지고, MENU_DELETE 감사 로그에 targetId와 이름·URL 라벨이 남는다")
    void deleteMenu_removesRow_andLogsWithLabel() {
        Menu target = menuRepository.save(Menu.builder().menuName("X-삭제 대상").menuUrl("/x/delete").useYn(false)
                .ord(970).createDate(LocalDateTime.now()).updateDate(LocalDateTime.now()).build());
        long before = maxActionLogId();

        menuService.deleteMenu(target.getMenuNo());

        assertTrue(menuRepository.findById(target.getMenuNo()).isEmpty(), "row가 물리적으로 삭제된다");
        List<AdminActionLog> logs = logsAfter(AdminActionTypes.MENU_DELETE, before);
        assertEquals(1, logs.size());
        assertEquals(AdminActionResult.SUCCESS, logs.get(0).getActionResult());
        assertEquals(target.getMenuNo(), logs.get(0).getTargetId());
        assertEquals("X-삭제 대상 (/x/delete)", logs.get(0).getTargetLabel());
    }

    @Test
    @DisplayName("영구삭제: 비활성 자식만 남아 있어도 부모는 삭제되지 않는다(409) — 활성 자식 전용 검사가 아니다")
    void deleteMenu_parentWithInactiveChild_conflict_unchanged() {
        Menu parent = saveMenu("P-비활성 부모", null, false, 971);
        Menu child = saveMenu("P-비활성 자식", parent.getMenuNo(), false, 0);

        assertThrows(ConflictException.class, () -> menuService.deleteMenu(parent.getMenuNo()));

        assertTrue(menuRepository.findById(parent.getMenuNo()).isPresent());
        assertTrue(menuRepository.findById(child.getMenuNo()).isPresent());
    }

    @Test
    @DisplayName("영구삭제: 활성 메뉴는 409이고 row가 그대로 남는다")
    void deleteMenu_activeMenu_conflict_unchanged() {
        Menu active = saveMenu("A-활성", null, true, 972);

        assertThrows(ConflictException.class, () -> menuService.deleteMenu(active.getMenuNo()));

        assertTrue(menuRepository.findById(active.getMenuNo()).orElseThrow().getUseYn());
    }

    @Test
    @DisplayName("영구삭제 ↔ 하위 생성: 전체 행 잠금으로 비활성 자식을 만드는 트랜잭션이 먼저면, 삭제는 락 대기 후 자식을 발견해 409")
    void deleteMenu_afterConcurrentChildInsert_conflict() throws Exception {
        Menu parent = saveMenu("C-비활성 부모", null, false, 973);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        AtomicReference<Long> insertedChild = new AtomicReference<>();
        try {
            // createMenu와 같은 전체 행 잠금(대상 부모 포함) 안에서 비활성 자식을 INSERT한다 — 활성 자식만 보면 놓치는 경우
            Future<?> holder = holdAllRowLocks(executor, locked, release, holderConnection, () -> {
                LocalDateTime now = LocalDateTime.now();
                Menu child = menuRepository.save(Menu.builder().menuName("C-동시 비활성 자식").useYn(false).ord(0)
                        .upMenuNo(parent.getMenuNo()).createDate(now).updateDate(now).build());
                menuRepository.flush();
                insertedChild.set(child.getMenuNo());
            });
            await(locked, "전체 행 잠금 + 자식 INSERT");
            Future<?> delete = executor.submit(() -> menuService.deleteMenu(parent.getMenuNo()));
            awaitSomeoneWaitingFor(holderConnection.get());
            assertFalse(delete.isDone(), "잠금 보유 중에는 삭제가 끝나면 안 된다");

            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            extraMenuIds.add(insertedChild.get());

            Throwable failure = failureOf(delete);
            assertInstanceOf(ConflictException.class, failure, "커밋된 자식을 발견해 409여야 한다: " + failure);
            assertTrue(menuRepository.findById(parent.getMenuNo()).isPresent(), "부모는 삭제되지 않는다");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("영구삭제 ↔ 재활성화: 다른 트랜잭션이 먼저 재활성화를 커밋하면, 삭제는 락 대기 후 활성 상태를 읽어 409")
    void deleteMenu_afterConcurrentReactivate_conflict() throws Exception {
        Menu target = saveMenu("R-비활성", null, false, 974);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        try {
            Future<?> holder = holdRowLock(executor, target.getMenuNo(), locked, release, holderConnection,
                    menu -> menu.update(menu.getMenuName(), menu.getMenuUrl(), menu.getMenuIcon(), menu.getMenuDesc(),
                            true, menu.getAccessRole(), menu.getOrd(), LocalDateTime.now()));
            await(locked, "대상 행 잠금 + 재활성화");
            Future<?> delete = executor.submit(() -> menuService.deleteMenu(target.getMenuNo()));
            awaitSomeoneWaitingFor(holderConnection.get());
            assertFalse(delete.isDone(), "잠금 보유 중에는 삭제가 끝나면 안 된다");

            release.countDown();
            holder.get(5, TimeUnit.SECONDS);

            Throwable failure = failureOf(delete);
            assertInstanceOf(ConflictException.class, failure, "재활성화가 커밋된 뒤라 활성 메뉴 삭제 거부(409)여야 한다: " + failure);
            assertTrue(menuRepository.findById(target.getMenuNo()).orElseThrow().getUseYn());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("영구삭제 ↔ 영구삭제: 먼저 삭제가 커밋되면 뒤 요청은 락 대기 후 404")
    void deleteMenu_afterConcurrentDelete_notFound() throws Exception {
        Menu target = saveMenu("D-이중 삭제", null, false, 975);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        try {
            Future<?> holder = holdRowLock(executor, target.getMenuNo(), locked, release, holderConnection,
                    menu -> menuRepository.delete(menu));
            await(locked, "대상 행 잠금 + 삭제");
            Future<?> delete = executor.submit(() -> menuService.deleteMenu(target.getMenuNo()));
            awaitSomeoneWaitingFor(holderConnection.get());
            assertFalse(delete.isDone(), "잠금 보유 중에는 삭제가 끝나면 안 된다");

            release.countDown();
            holder.get(5, TimeUnit.SECONDS);

            Throwable failure = failureOf(delete);
            assertInstanceOf(ResourceNotFoundException.class, failure, "이미 삭제돼 404여야 한다: " + failure);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("영구삭제 뒤 도착한 낡은 구조 초안은 400이 아니라 409이고 아무것도 바뀌지 않는다(화면이 초안을 폐기)")
    void applyStructure_afterPermanentDelete_staleDraftConflict() {
        Menu r1 = saveMenu("T-루트1", null, true, 976);
        Menu r2 = saveMenu("T-루트2", null, true, 977);
        Menu gone = saveMenu("T-삭제될 메뉴", null, false, 978);
        List<MenuStructureRequest.Item> staleItems = currentItems(); // 삭제 전 초안(현재 행 수보다 항목이 많아진다)

        menuService.deleteMenu(gone.getMenuNo());

        Throwable failure = assertThrows(RuntimeException.class, () -> menuService.applyStructure(structureOf(staleItems)));
        assertInstanceOf(ConflictException.class, failure, "집합 불일치는 개수 초과 400이 아니라 낡은 초안 409여야 한다: " + failure);
        assertEquals(976, menuRepository.findById(r1.getMenuNo()).orElseThrow().getOrd());
        assertEquals(977, menuRepository.findById(r2.getMenuNo()).orElseThrow().getOrd());
    }

    /**
     * 감사 커밋 순서(커밋 단계 실패) — 프로브가 아닌 실제 서비스 메서드(applyStructure)로 확인한다. 서비스 내부 예외가 아니라
     * 실제 커밋 직전 트랜잭션 동기화 예외로 실패시키면 업무 변경은 롤백되고 SUCCESS는 남지 않으며 FAIL(targetId null)만 남는다.
     * (정상 커밋의 SUCCESS 1건·targetId 없음은 {@link #applyStructure_movesSubtreeToPosition_andLogsOnce()}가 검증한다.)
     * 이 보장은 log 패키지 CLAUDE.md "커밋 순서 보장"의 유일한 실서비스 회귀 테스트다.
     */
    @Test
    @DisplayName("커밋 단계 실패: 구조 반영은 롤백되고 SUCCESS는 없으며 FAIL(targetId null)만 기록된다")
    void applyStructure_commitTimeFailure_recordsFailNotSuccess() throws Exception {
        Menu r1 = saveMenu("F-루트1", null, true, 960);
        Menu r2 = saveMenu("F-루트2", null, true, 961);
        List<MenuStructureRequest.Item> items = withMoved(currentItems(), r2.getMenuNo(), null, r1.getMenuNo());
        long before = maxActionLogId();

        // 이 테스트는 동시 실행이 필요 없으므로 테스트 스레드에서 직접 호출한다(별도 스레드·executor가 없어 타임아웃 시 스레드나
        // advice가 후속 테스트·공유 DB를 오염시킬 수 없다). 첫 전체 행 잠금(findAllForUpdate)에서 "커밋 직전 예외"를 던지는
        // 트랜잭션 동기화를 등록하고, 다른 스레드의 호출에는 개입하지 않는다.
        Thread testThread = Thread.currentThread();
        Advised repositoryProxy = (Advised) menuRepository;
        MethodInterceptor failAtCommit = invocation -> {
            if (invocation.getMethod().getName().equals("findAllForUpdate")
                    && Thread.currentThread() == testThread) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void beforeCommit(boolean readOnly) {
                                throw new IllegalStateException("simulated commit-time failure");
                            }
                        });
            }
            return invocation.proceed();
        };
        Throwable failure = null;
        repositoryProxy.addAdvice(0, failAtCommit);
        try {
            menuService.applyStructure(structureOf(items));
        } catch (Throwable thrown) {
            failure = thrown;
        } finally {
            repositoryProxy.removeAdvice(failAtCommit);
        }

        assertTrue(failure != null, "커밋 단계 예외로 실패해야 한다");
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertTrue(String.valueOf(root.getMessage()).contains("simulated commit-time failure"), "원인: " + root);
        assertEquals(960, menuRepository.findById(r1.getMenuNo()).orElseThrow().getOrd(), "커밋 실패로 구조 반영이 롤백되어야 한다");
        assertEquals(961, menuRepository.findById(r2.getMenuNo()).orElseThrow().getOrd(), "커밋 실패로 구조 반영이 롤백되어야 한다");

        List<AdminActionLog> logs = logsAfter(AdminActionTypes.MENU_STRUCTURE_APPLY, before);
        assertEquals(1, logs.size());
        assertEquals(AdminActionResult.FAIL, logs.get(0).getActionResult());
        assertNull(logs.get(0).getTargetId());
        assertTrue(logs.stream().noneMatch(log -> log.getActionResult() == AdminActionResult.SUCCESS), "SUCCESS가 남으면 안 된다");
    }

}
