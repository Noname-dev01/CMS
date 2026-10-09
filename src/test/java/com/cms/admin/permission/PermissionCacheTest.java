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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 캐시 단위 시험(DB 없음): generation 경합, fail-closed, 관대한 파싱. 실제 DB의 격리(REQUIRES_NEW) 시험은
 * {@code PermissionCacheIsolationIntegrationTest}가 맡는다.
 */
class PermissionCacheTest {

    /** 트랜잭션 의미가 필요 없는 단위 시험용 관리자 — 콜백만 그대로 실행한다. */
    private static PlatformTransactionManager noopTransactionManager() {
        return new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        };
    }

    /** 게시판별 권한 행이 없는 리포지토리(기능 단위 캐시 동작 시험용). */
    private static MemberBoardPermissionRepository emptyBoardRepository() {
        MemberBoardPermissionRepository boardRepository = mock(MemberBoardPermissionRepository.class);
        when(boardRepository.findAllGrantRows()).thenReturn(List.of());
        return boardRepository;
    }

    private static List<Object[]> rows(Object... triples) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < triples.length; i += 3) {
            rows.add(new Object[]{triples[i], triples[i + 1], triples[i + 2]});
        }
        return rows;
    }

    /** 기능 단위(member_permission) 행이 없는 리포지토리 — 현재 카탈로그에는 기능 단위 위임 기능이 없다(공지는 게시판 권한으로 흡수, PLAN-notice-to-board.md). */
    private static MemberPermissionRepository emptyFeatureRepository() {
        MemberPermissionRepository repository = mock(MemberPermissionRepository.class);
        when(repository.findAllGrantRows()).thenReturn(List.of());
        return repository;
    }

    @Test
    @DisplayName("스냅샷은 한 번 로드해 캐시하고, invalidate 뒤에는 다시 로드한다")
    void cachesUntilInvalidated() {
        MemberBoardPermissionRepository boardRepository = mock(MemberBoardPermissionRepository.class);
        AtomicInteger loads = new AtomicInteger();
        when(boardRepository.findAllGrantRows()).thenAnswer(invocation -> {
            loads.incrementAndGet();
            return rows(1L, 10L, "READ");
        });
        PermissionCache cache = new PermissionCache(emptyFeatureRepository(), boardRepository, noopTransactionManager());

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
        MemberBoardPermissionRepository boardRepository = mock(MemberBoardPermissionRepository.class);
        AtomicInteger loads = new AtomicInteger();
        PermissionCache[] holder = new PermissionCache[1];
        when(boardRepository.findAllGrantRows()).thenAnswer(invocation -> {
            int n = loads.incrementAndGet();
            if (n == 1) {
                holder[0].invalidate(); // 첫 로드가 DB를 읽는 도중 권한이 바뀌었다고 가정
                return rows(1L, 10L, "READ", 1L, 10L, "DELETE"); // 낡은 값
            }
            return rows(1L, 10L, "READ"); // 회수 후 최신 값
        });
        PermissionCache cache = new PermissionCache(emptyFeatureRepository(), boardRepository, noopTransactionManager());
        holder[0] = cache;

        PermissionSnapshot first = cache.snapshot(); // 이번 요청만 낡은 값으로 판정
        PermissionSnapshot second = cache.snapshot(); // 설치되지 않았으므로 다시 로드

        assertThat(first.hasBoard(1L, 10L, PermissionAction.DELETE)).isTrue();
        assertThat(second.hasBoard(1L, 10L, PermissionAction.DELETE))
                .as("낡은 스냅샷이 최신으로 설치되면 회수된 권한이 계속 허용된다").isFalse();
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("로드 실패는 빈 스냅샷(fail-closed)으로 판정하고 캐시하지 않아 다음 요청이 재시도한다")
    void loadFailureIsFailClosedAndNotCached() {
        MemberBoardPermissionRepository boardRepository = mock(MemberBoardPermissionRepository.class);
        AtomicInteger loads = new AtomicInteger();
        when(boardRepository.findAllGrantRows()).thenAnswer(invocation -> {
            if (loads.incrementAndGet() == 1) {
                throw new IllegalStateException("DB 장애");
            }
            return rows(1L, 10L, "READ");
        });
        PermissionCache cache = new PermissionCache(emptyFeatureRepository(), boardRepository, noopTransactionManager());

        PermissionSnapshot failed = cache.snapshot();
        PermissionSnapshot recovered = cache.snapshot();

        assertThat(failed.boardGrants()).isEmpty();
        assertThat(recovered.hasBoard(1L, 10L, PermissionAction.READ)).isTrue();
    }

    @Test
    @DisplayName("모르는 기능·동작 행과 위임 불가 기능 행은 무시한다(관대한 파싱) — 카탈로그에서 제거된 NOTICE 행도 로딩을 깨지 않고 무시된다")
    void unknownAndNonDelegableRowsAreIgnored() {
        PermissionSnapshot snapshot = PermissionCache.toSnapshot(rows(
                1L, "NOTICE", "READ",            // 게시판 권한으로 흡수되며 카탈로그에서 제거된 기능(V32가 행을 지우지만 남아 있어도 안전)
                1L, "REMOVED_FEATURE", "READ",   // 코드에서 지운 기능
                1L, "MENU", "READ",              // 위임 불가 기능에 수동으로 들어간 행
                1L, "DASHBOARD", "READ"));       // 상시 허용 기능(DB 행은 의미 없음)

        assertThat(snapshot.grants()).isEmpty();
    }

    @Test
    @DisplayName("게시판 행: 정상 행은 적재하고 모르는 동작 행은 무시하며, member_permission의 BOARD 행은 무시한다(게시판별 테이블로만 부여)")
    void boardRowsAndBoardFeatureRowsInMemberPermission() {
        PermissionSnapshot snapshot = PermissionCache.toSnapshot(
                rows(1L, "BOARD", "READ"),           // 기능 단위 테이블의 게시판 행 — 무시
                rows(1L, 10L, "READ",
                     1L, 10L, "UPDATE",
                     1L, 20L, "ARCHIVE"));            // 모르는 동작 — 무시

        assertThat(snapshot.grants()).isEmpty();
        assertThat(snapshot.boardGrants()).containsExactlyInAnyOrder(
                new PermissionSnapshot.BoardGrant(1L, 10L, PermissionAction.READ),
                new PermissionSnapshot.BoardGrant(1L, 10L, PermissionAction.UPDATE));
        assertThat(snapshot.boardIds(1L, PermissionAction.UPDATE)).containsExactly(10L);
    }

    @Test
    @DisplayName("두 테이블을 같은 로드에서 읽고, 게시판 행 로드가 실패하면 전체가 fail-closed(빈 스냅샷)다")
    void boardRowsLoadedTogetherAndFailClosed() {
        MemberPermissionRepository repository = emptyFeatureRepository();
        MemberBoardPermissionRepository boardRepository = mock(MemberBoardPermissionRepository.class);
        AtomicInteger boardLoads = new AtomicInteger();
        when(boardRepository.findAllGrantRows()).thenAnswer(invocation -> {
            if (boardLoads.incrementAndGet() == 1) {
                throw new IllegalStateException("DB 장애");
            }
            return rows(1L, 10L, "READ");
        });
        PermissionCache cache = new PermissionCache(repository, boardRepository, noopTransactionManager());

        PermissionSnapshot failed = cache.snapshot();

        assertThat(failed.grants()).isEmpty();
        assertThat(failed.boardGrants()).isEmpty();
        verify(repository, times(1)).findAllGrantRows();   // 기능 단위 행을 먼저 읽고 게시판 행에서 실패해도 전체를 버린다

        PermissionSnapshot recovered = cache.snapshot();   // 실패는 캐시하지 않아 다음 요청이 두 테이블을 다시 읽는다
        verify(repository, times(2)).findAllGrantRows();
        assertThat(recovered.hasBoard(1L, 10L, PermissionAction.READ)).isTrue();
    }

    @Test
    @DisplayName("동시에 여러 요청이 와도 로드는 한 번만 일어난다(단일 비행)")
    void singleFlightLoad() throws Exception {
        MemberBoardPermissionRepository boardRepository = mock(MemberBoardPermissionRepository.class);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        when(boardRepository.findAllGrantRows()).thenAnswer(invocation -> {
            loads.incrementAndGet();
            release.await(5, TimeUnit.SECONDS);
            return rows(1L, 10L, "READ");
        });
        PermissionCache cache = new PermissionCache(emptyFeatureRepository(), boardRepository, noopTransactionManager());

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
                        .hasBoard(1L, 10L, PermissionAction.READ)).isTrue();
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(loads.get()).isEqualTo(1);
    }
}
