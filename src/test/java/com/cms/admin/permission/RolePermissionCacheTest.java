package com.cms.admin.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 캐시 단위 시험(DB 없음): generation 경합, fail-closed, 관대한 파싱. 실제 DB의 격리(REQUIRES_NEW) 시험은
 * {@code RolePermissionCacheIsolationIntegrationTest}가 맡는다.
 */
class RolePermissionCacheTest {

    /** 트랜잭션 의미가 필요 없는 단위 시험용 관리자 — 콜백만 그대로 실행한다. */
    private static PlatformTransactionManager noopTransactionManager() {
        return new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        };
    }

    private static List<Object[]> rows(String... triples) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < triples.length; i += 3) {
            rows.add(new Object[]{triples[i], triples[i + 1], triples[i + 2]});
        }
        return rows;
    }

    @Test
    @DisplayName("스냅샷은 한 번 로드해 캐시하고, invalidate 뒤에는 다시 로드한다")
    void cachesUntilInvalidated() {
        RolePermissionRepository repository = mock(RolePermissionRepository.class);
        AtomicInteger loads = new AtomicInteger();
        when(repository.findAllGrantRows()).thenAnswer(invocation -> {
            loads.incrementAndGet();
            return rows("ROLE_MANAGER", "NOTICE", "READ");
        });
        RolePermissionCache cache = new RolePermissionCache(repository, noopTransactionManager());

        cache.snapshot();
        cache.snapshot();
        assertThat(loads.get()).isEqualTo(1);

        cache.invalidate();
        cache.snapshot();
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("로드 도중 무효화가 끼면 낡은 결과를 캐시에 설치하지 않고, 다음 요청이 다시 로드한다")
    void invalidationDuringLoadIsNotInstalled() {
        RolePermissionRepository repository = mock(RolePermissionRepository.class);
        AtomicInteger loads = new AtomicInteger();
        RolePermissionCache[] holder = new RolePermissionCache[1];
        when(repository.findAllGrantRows()).thenAnswer(invocation -> {
            int n = loads.incrementAndGet();
            if (n == 1) {
                holder[0].invalidate(); // 첫 로드가 DB를 읽는 도중 권한이 바뀌었다고 가정
                return rows("ROLE_MANAGER", "NOTICE", "READ", "ROLE_MANAGER", "NOTICE", "DELETE"); // 낡은 값
            }
            return rows("ROLE_MANAGER", "NOTICE", "READ"); // 회수 후 최신 값
        });
        RolePermissionCache cache = new RolePermissionCache(repository, noopTransactionManager());
        holder[0] = cache;

        PermissionSnapshot first = cache.snapshot(); // 이번 요청만 낡은 값으로 판정
        PermissionSnapshot second = cache.snapshot(); // 설치되지 않았으므로 다시 로드

        assertThat(first.has("ROLE_MANAGER", AdminFeature.NOTICE, PermissionAction.DELETE)).isTrue();
        assertThat(second.has("ROLE_MANAGER", AdminFeature.NOTICE, PermissionAction.DELETE))
                .as("낡은 스냅샷이 최신으로 설치되면 회수된 권한이 계속 허용된다").isFalse();
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("로드 실패는 빈 스냅샷(fail-closed)으로 판정하고 캐시하지 않아 다음 요청이 재시도한다")
    void loadFailureIsFailClosedAndNotCached() {
        RolePermissionRepository repository = mock(RolePermissionRepository.class);
        AtomicInteger loads = new AtomicInteger();
        when(repository.findAllGrantRows()).thenAnswer(invocation -> {
            if (loads.incrementAndGet() == 1) {
                throw new IllegalStateException("DB 장애");
            }
            return rows("ROLE_MANAGER", "NOTICE", "READ");
        });
        RolePermissionCache cache = new RolePermissionCache(repository, noopTransactionManager());

        PermissionSnapshot failed = cache.snapshot();
        PermissionSnapshot recovered = cache.snapshot();

        assertThat(failed.grants()).isEmpty();
        assertThat(recovered.has("ROLE_MANAGER", AdminFeature.NOTICE, PermissionAction.READ)).isTrue();
    }

    @Test
    @DisplayName("모르는 기능·동작 행과 위임 불가 기능 행은 무시하고 나머지는 정상 로드한다(관대한 파싱)")
    void unknownAndNonDelegableRowsAreIgnored() {
        PermissionSnapshot snapshot = RolePermissionCache.toSnapshot(rows(
                "ROLE_MANAGER", "NOTICE", "READ",
                "ROLE_MANAGER", "REMOVED_FEATURE", "READ",   // 코드에서 지운 기능
                "ROLE_MANAGER", "NOTICE", "ARCHIVE",         // 모르는 동작
                "ROLE_MANAGER", "MENU", "READ",              // 위임 불가 기능에 수동으로 들어간 행
                "ROLE_MANAGER", "DASHBOARD", "READ"));       // 상시 허용 기능(DB 행은 의미 없음)

        assertThat(snapshot.grants()).containsExactly(
                new PermissionSnapshot.Grant("ROLE_MANAGER", AdminFeature.NOTICE, PermissionAction.READ));
    }

    @Test
    @DisplayName("동시에 여러 요청이 와도 로드는 한 번만 일어난다(단일 비행)")
    void singleFlightLoad() throws Exception {
        RolePermissionRepository repository = mock(RolePermissionRepository.class);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        when(repository.findAllGrantRows()).thenAnswer(invocation -> {
            loads.incrementAndGet();
            release.await(5, TimeUnit.SECONDS);
            return rows("ROLE_MANAGER", "NOTICE", "READ");
        });
        RolePermissionCache cache = new RolePermissionCache(repository, noopTransactionManager());

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<PermissionSnapshot>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(executor.submit(cache::snapshot));
            }
            Thread.sleep(200); // 모두 로드 대기 상태에 들어가게 한다(증거가 아니라 진행용 대기)
            release.countDown();
            for (Future<PermissionSnapshot> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)
                        .has("ROLE_MANAGER", AdminFeature.NOTICE, PermissionAction.READ)).isTrue();
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(loads.get()).isEqualTo(1);
    }
}
