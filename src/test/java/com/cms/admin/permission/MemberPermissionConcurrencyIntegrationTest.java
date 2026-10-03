package com.cms.admin.permission;

import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.log.domain.AdminActionLog;
import com.cms.admin.log.domain.AdminActionResult;
import com.cms.admin.log.repository.AdminActionLogRepository;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.AdminMemberUpdateRequest;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.service.AdminMemberService;
import com.cms.admin.permission.dto.request.MemberPermissionUpdateRequest;
import com.cms.admin.permission.service.MemberPermissionService;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
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
 * 회원별 권한 저장의 동시성 시험(PLAN-member-permission.md §7-1 ⑤): 실제 MariaDB 행 잠금 대기를 {@code INNODB_LOCK_WAITS}로 관측한다
 * ({@code MenuConcurrencyIntegrationTest}와 같은 패턴 — sleep을 증거로 쓰지 않는다). 권한 저장과 역할 변경이 <b>같은 회원 행</b>을 잠가
 * 직렬화되는지도 두 방향으로 시험한다. 이 시험은 일회용 MariaDBContainer 전용이다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class MemberPermissionConcurrencyIntegrationTest extends MariaDbContainerSupport {

    @Autowired MemberPermissionService service;
    @Autowired AdminMemberService adminMemberService;
    @Autowired MemberRepository memberRepository;
    @Autowired AdminActionLogRepository auditRepository;
    @Autowired PermissionCache cache;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcConnectionDetails connectionDetails;
    @PersistenceContext EntityManager entityManager;

    private long auditBaseline;
    private Member manager;

    @BeforeEach
    void setUp() {
        manager = TestMembers.save(memberRepository, "perm-conc", Role.ROLE_MANAGER);
        restoreSeed();
        auditBaseline = auditRepository.findAll().stream().mapToLong(AdminActionLog::getId).max().orElse(0L);
    }

    @AfterEach
    void cleanUp() {
        audits().forEach(auditRepository::delete);
        TestMembers.delete(jdbc, List.of(manager.getId()));
        cache.invalidate();
    }

    private void restoreSeed() {
        jdbc.update("DELETE FROM member_permission WHERE member_id = ?", manager.getId());
        for (PermissionAction action : PermissionAction.values()) {
            jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, 'NOTICE', ?)", manager.getId(), action.name());
        }
        cache.invalidate();
    }

    private long version() {
        return jdbc.queryForObject("SELECT permission_version FROM member WHERE id = ?", Long.class, manager.getId());
    }

    private Set<String> noticeActions() {
        return new TreeSet<>(jdbc.queryForList(
                "SELECT action FROM member_permission WHERE member_id = ? AND BINARY feature = 'NOTICE'", String.class, manager.getId()));
    }

    private String role() {
        return jdbc.queryForObject("SELECT user_type FROM member WHERE id = ?", String.class, manager.getId());
    }

    private List<AdminActionLog> audits() {
        return auditRepository.findAll().stream()
                .filter(log -> log.getId() > auditBaseline && AdminActionTypes.PERMISSION_UPDATE.equals(log.getActionType()))
                .toList();
    }

    private static MemberPermissionUpdateRequest request(long version, PermissionAction... actions) {
        return MemberPermissionUpdateRequest.builder().version(version)
                .grants(Arrays.stream(actions).map(a -> new MemberPermissionUpdateRequest.Grant(AdminFeature.NOTICE, a)).toList())
                .build();
    }

    // ── ① 실제 락 대기 ──────────────────────────────────────

    @Test
    @DisplayName("다른 트랜잭션이 회원 행을 잠그고 version을 올려 커밋하면, 대기하던 PUT은 실제 락 대기를 거친 뒤 409이고 행은 바뀌지 않는다")
    void replace_waitsForRowLock_thenConflicts() throws Exception {
        long before = version();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        try {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Member locking = memberRepository.findByIdForUpdate(manager.getId()).orElseThrow();
                holderConnection.set(((Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult()).longValue());
                locking.increasePermissionVersion(); // 다른 관리자가 먼저 저장한 것과 같은 효과
                locked.countDown();
                await(release);
            }));
            await(locked);

            Future<?> waiter = executor.submit(() -> service.replace(manager.getId(), request(before, PermissionAction.READ)));
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
    private boolean attempt(CyclicBarrier barrier, MemberPermissionUpdateRequest request) throws Exception {
        barrier.await(15, TimeUnit.SECONDS);
        try {
            service.replace(manager.getId(), request);
            return true;
        } catch (ConflictException e) {
            return false;
        }
    }

    // ── ③ 권한 저장 ↔ 역할 변경 직렬화(같은 회원 행 잠금) ──────────

    @Test
    @DisplayName("역할 변경이 먼저 커밋되면(권한 행 삭제·버전 증가) 대기하던 PUT은 실제 락 대기를 거친 뒤 ADMIN 대상 400이고 허용 행은 0개다")
    void roleChangeFirst_thenReplaceIsRejected() throws Exception {
        long before = version();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        try {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Member locking = memberRepository.findByIdForUpdate(manager.getId()).orElseThrow();
                holderConnection.set(((Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult()).longValue());
                locking.changeRole(Role.ROLE_ADMIN, LocalDateTime.now()); // 역할 변경 트랜잭션이 잠금을 쥐고 있다
                jdbc.update("DELETE FROM member_permission WHERE member_id = ?", manager.getId());
                locking.increasePermissionVersion();
                locked.countDown();
                await(release);
            }));
            await(locked);

            Future<?> waiter = executor.submit(() -> service.replace(manager.getId(), request(before, PermissionAction.READ)));
            awaitSomeoneWaitingFor(holderConnection.get());
            assertThat(waiter.isDone()).as("역할 변경이 커밋되기 전에는 PUT이 끝나지 않는다").isFalse();

            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            assertThat(causeOf(waiter)).as("커밋된 새 역할(ADMIN)을 본다").isInstanceOf(InvalidRequestException.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertThat(role()).isEqualTo("ROLE_ADMIN");
        assertThat(noticeActions()).isEmpty();
        assertThat(version()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("권한 저장 중(회원 행 잠금)에 들어온 역할 변경은 실제 락 대기를 거친 뒤 실행되어 개별 권한을 지우고 버전을 올린다")
    void replaceInFlight_thenRoleChangeWaitsAndDeletesRows() throws Exception {
        long before = version();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        try {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                memberRepository.findByIdForUpdate(manager.getId()).orElseThrow(); // 진행 중인 권한 저장이 회원 행을 쥐고 있다
                holderConnection.set(((Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult()).longValue());
                locked.countDown();
                await(release);
            }));
            await(locked);

            Future<?> waiter = executor.submit(() -> adminMemberService.updateAdminMember(
                    -1L, manager.getId(), AdminMemberUpdateRequest.builder().userType(Role.ROLE_ADMIN).build()));
            awaitSomeoneWaitingFor(holderConnection.get());
            assertThat(waiter.isDone()).as("권한 저장이 끝나기 전에는 역할 변경이 끝나지 않는다").isFalse();

            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            assertThat(causeOf(waiter)).as("역할 변경은 성공한다").isNull();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertThat(role()).isEqualTo("ROLE_ADMIN");
        assertThat(noticeActions()).as("역할 변경이 개별 권한 행을 지웠다").isEmpty();
        assertThat(version()).isEqualTo(before + 1);
        assertThat(cache.snapshot().has(manager.getId(), AdminFeature.NOTICE, PermissionAction.READ))
                .as("삭제 뒤 캐시가 무효화돼 낡은 허용이 남지 않는다").isFalse();
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
