package com.cms.admin.permission;

import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.log.domain.AdminActionLog;
import com.cms.admin.log.domain.AdminActionResult;
import com.cms.admin.log.repository.AdminActionLogRepository;
import com.cms.admin.member.domain.Role;
import com.cms.admin.permission.dto.request.RolePermissionUpdateRequest;
import com.cms.admin.permission.service.RolePermissionService;
import com.cms.common.exception.ConflictException;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 권한 저장의 동시성 시험(PLAN-permission-management-pr3.md §7-1): 실제 MariaDB 행 잠금 대기를 {@code INNODB_LOCK_WAITS}로 관측한다
 * ({@code MenuConcurrencyIntegrationTest}와 같은 패턴 — sleep을 증거로 쓰지 않는다). 이 시험은 일회용 MariaDBContainer 전용이다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class RolePermissionConcurrencyIntegrationTest extends MariaDbContainerSupport {

    private static final String MANAGER = "ROLE_MANAGER";

    @Autowired RolePermissionService service;
    @Autowired PermissionRoleRepository permissionRoleRepository;
    @Autowired AdminActionLogRepository auditRepository;
    @Autowired RolePermissionCache cache;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcConnectionDetails connectionDetails;
    @PersistenceContext EntityManager entityManager;

    private long auditBaseline;

    @BeforeEach
    void setUp() {
        restoreSeed();
        auditBaseline = auditRepository.findAll().stream().mapToLong(AdminActionLog::getId).max().orElse(0L);
    }

    @AfterEach
    void cleanUp() {
        restoreSeed();
        audits().forEach(auditRepository::delete);
    }

    private void restoreSeed() {
        jdbc.update("DELETE FROM role_permission WHERE role = ?", MANAGER);
        for (PermissionAction action : PermissionAction.values()) {
            jdbc.update("INSERT INTO role_permission (role, feature, action) VALUES (?, 'NOTICE', ?)", MANAGER, action.name());
        }
        cache.invalidate();
    }

    private long version() {
        return jdbc.queryForObject("SELECT version FROM permission_role WHERE role = ?", Long.class, MANAGER);
    }

    private Set<String> noticeActions() {
        return new TreeSet<>(jdbc.queryForList(
                "SELECT action FROM role_permission WHERE BINARY role = ? AND BINARY feature = 'NOTICE'", String.class, MANAGER));
    }

    private List<AdminActionLog> audits() {
        return auditRepository.findAll().stream()
                .filter(log -> log.getId() > auditBaseline && AdminActionTypes.PERMISSION_UPDATE.equals(log.getActionType()))
                .toList();
    }

    private static RolePermissionUpdateRequest request(long version, PermissionAction... actions) {
        return RolePermissionUpdateRequest.builder().version(version)
                .grants(Arrays.stream(actions).map(a -> new RolePermissionUpdateRequest.Grant(AdminFeature.NOTICE, a)).toList())
                .build();
    }

    // ── ① 실제 락 대기 ──────────────────────────────────────

    @Test
    @DisplayName("다른 트랜잭션이 기준 행을 잠그고 version을 올려 커밋하면, 대기하던 PUT은 실제 락 대기를 거친 뒤 409이고 행은 바뀌지 않는다")
    void replace_waitsForRowLock_thenConflicts() throws Exception {
        long before = version();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        try {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                PermissionRole base = permissionRoleRepository.findByIdForUpdate(MANAGER).orElseThrow();
                holderConnection.set(((Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult()).longValue());
                base.increaseVersion(LocalDateTime.now()); // 다른 관리자가 먼저 저장한 것과 같은 효과
                locked.countDown();
                await(release);
            }));
            await(locked);

            Future<?> waiter = executor.submit(() -> service.replace(Role.ROLE_MANAGER, request(before, PermissionAction.READ)));
            awaitSomeoneWaitingFor(holderConnection.get());
            assertThat(waiter.isDone()).as("잠금이 풀리기 전에는 PUT이 끝나지 않는다").isFalse();

            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            assertThat(causeOf(waiter)).isInstanceOf(ConflictException.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertThat(version()).isEqualTo(before + 1);
        assertThat(noticeActions()).containsExactlyInAnyOrder("READ", "CREATE", "UPDATE", "DELETE");
    }

    // ── ② 동시 PUT 2건 ──────────────────────────────────────

    @Test
    @DisplayName("같은 version으로 동시에 저장하면 정확히 하나만 성공하고 하나는 409, 최종 행은 성공한 요청의 집합이며 감사는 SUCCESS 1·FAIL 1")
    void concurrentReplace_exactlyOneWins() throws Exception {
        long before = version();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<Boolean> readOnly = executor.submit(() -> attempt(barrier, request(before, PermissionAction.READ)));
            Future<Boolean> readCreate = executor.submit(() ->
                    attempt(barrier, request(before, PermissionAction.READ, PermissionAction.CREATE)));
            boolean readWon = readOnly.get(30, TimeUnit.SECONDS);
            boolean readCreateWon = readCreate.get(30, TimeUnit.SECONDS);

            assertThat(readWon ^ readCreateWon).as("정확히 하나만 성공").isTrue();
            assertThat(noticeActions()).isEqualTo(readWon
                    ? new TreeSet<>(Set.of("READ"))
                    : new TreeSet<>(Set.of("READ", "CREATE")));
        } finally {
            executor.shutdownNow();
        }

        assertThat(version()).isEqualTo(before + 1);
        List<AdminActionLog> audits = audits();
        assertThat(audits).extracting(AdminActionLog::getActionResult)
                .containsExactlyInAnyOrder(AdminActionResult.SUCCESS, AdminActionResult.FAIL);
    }

    /** @return 성공하면 true, 409(ConflictException)이면 false. 그 외 예외는 시험 실패. */
    private boolean attempt(CyclicBarrier barrier, RolePermissionUpdateRequest request) throws Exception {
        barrier.await(15, TimeUnit.SECONDS);
        try {
            service.replace(Role.ROLE_MANAGER, request);
            return true;
        } catch (ConflictException e) {
            return false;
        }
    }

    // ── 보조 ────────────────────────────────────────────────

    private static Throwable causeOf(Future<?> future) throws Exception {
        try {
            future.get(15, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "시간 초과");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** holder 연결이 잡은 락을 기다리는 트랜잭션이 하나 이상 생길 때까지 관측한다(sleep을 증거로 쓰지 않는다). */
    private void awaitSomeoneWaitingFor(long holder) throws Exception {
        try (Connection observer = DriverManager.getConnection(connectionDetails.getJdbcUrl(), "root", connectionDetails.getPassword());
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
}
