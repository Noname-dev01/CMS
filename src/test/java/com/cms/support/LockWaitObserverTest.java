package com.cms.support;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link LockWaitObserver} 시험(이슈 #119·#121). 핵심은 <b>대기자가 첫 폴링 뒤에 락을 요청하는 순서</b>다 — 이 순서에서 폴링 간격이 짧으면
 * information_schema 캐시가 낡은 스냅샷에 머물러 대기를 놓친다. 시간 지연(sleep)으로 이 순서를 만들면 관측 스레드가 늦을 때 순서가 안 나오므로,
 * 헬퍼의 시험용 훅(첫 조회가 비었을 때 호출)으로 대기자를 풀어 순서를 <b>결정적으로</b> 만든다.
 */
class LockWaitObserverTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "lock_wait_observer_test";

    private static String url() {
        return "jdbc:mariadb://" + MARIA_DB.getHost() + ":" + MARIA_DB.getFirstMappedPort() + "/" + SCHEMA;
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(url(), "root", MARIA_DB.getPassword());
    }

    private final LockWaitObserver observer = new LockWaitObserver(url(), MARIA_DB.getPassword());

    private Connection holder;
    private long holderConnectionId;
    private ExecutorService executor;

    @BeforeAll
    static void createSchema() throws Exception {
        String root = "jdbc:mariadb://" + MARIA_DB.getHost() + ":" + MARIA_DB.getFirstMappedPort() + "/";
        try (Connection connection = DriverManager.getConnection(root, "root", MARIA_DB.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
            statement.execute("CREATE TABLE " + SCHEMA + ".t (id BIGINT PRIMARY KEY, v INT) ENGINE=InnoDB");
            statement.execute("INSERT INTO " + SCHEMA + ".t VALUES (1, 0)");
        }
    }

    @AfterAll
    static void dropSchema() throws Exception {
        String root = "jdbc:mariadb://" + MARIA_DB.getHost() + ":" + MARIA_DB.getFirstMappedPort() + "/";
        try (Connection connection = DriverManager.getConnection(root, "root", MARIA_DB.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + SCHEMA);
        }
    }

    @BeforeEach
    void holdRowLock() throws Exception {
        holder = connect();
        holder.setAutoCommit(false);
        try (Statement statement = holder.createStatement()) {
            statement.executeQuery("SELECT * FROM t WHERE id = 1 FOR UPDATE").close();
            try (ResultSet rs = statement.executeQuery("SELECT CONNECTION_ID()")) {
                rs.next();
                holderConnectionId = rs.getLong(1);
            }
        }
        executor = Executors.newSingleThreadExecutor();
    }

    /** 어느 단계가 실패해도 뒤 단계(연결 닫기·스레드 종료)가 건너뛰어지지 않게 중첩 finally로 정리한다. */
    @AfterEach
    void release() throws Exception {
        try {
            if (holder != null) {
                holder.rollback();       // 대기자가 있으면 이때 풀려난다
            }
        } finally {
            try {
                if (holder != null) {
                    holder.close();
                }
            } finally {
                if (executor != null) {
                    executor.shutdown();
                    if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                        fail("대기자 스레드가 15초 안에 끝나지 않았다");
                    }
                }
            }
        }
    }

    /**
     * {@code gate}가 열린 뒤(null이면 즉시) holder와 같은 행을 FOR UPDATE로 요청해 대기하는 스레드를 시작한다. 대기자의 연결 ID를 채운다.
     */
    private Future<?> startWaiter(CountDownLatch gate, AtomicLong waiterConnectionId) {
        return executor.submit(() -> {
            if (gate != null && !gate.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("대기자 게이트가 10초 안에 열리지 않았다");
            }
            try (Connection waiter = connect()) {
                waiter.setAutoCommit(false);
                try (Statement statement = waiter.createStatement()) {
                    try (ResultSet rs = statement.executeQuery("SELECT CONNECTION_ID()")) {
                        rs.next();
                        waiterConnectionId.set(rs.getLong(1));
                    }
                    statement.executeQuery("SELECT * FROM t WHERE id = 1 FOR UPDATE").close();   // holder가 풀 때까지 여기서 대기
                }
                waiter.rollback();
            }
            return null;
        });
    }

    @Test
    @DisplayName("폴링 간격은 100ms를 넘는다 — information_schema 캐시가 갱신되는 조건(이슈 #119·#121)")
    void pollIntervalExceedsCacheRefreshWindow() {
        assertThat(LockWaitObserver.POLL_INTERVAL_MS).isGreaterThan(100);
    }

    @Test
    @DisplayName("대기자가 첫 폴링이 빈 결과를 낸 뒤에야 생겨도 awaitWaiterFor가 관측한다 (#119와 같은 순서, 훅으로 결정적)")
    void awaitWaiterFor_seesWaiterThatAppearsAfterFirstEmptyPoll() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        Future<?> waiter = startWaiter(gate, new AtomicLong());
        LockWaitObserver delayedWaiterObserver = new LockWaitObserver(url(), MARIA_DB.getPassword(), gate::countDown);

        delayedWaiterObserver.awaitWaiterFor(holderConnectionId, Duration.ofSeconds(5));

        assertThat(gate.getCount()).as("훅이 호출됐다 = 첫 폴링은 대기를 못 본 상태였다").isZero();
        assertThat(waiter.isDone()).as("holder가 쥐고 있는 동안 대기자는 끝나지 않는다").isFalse();
    }

    @Test
    @DisplayName("awaitAnyWaiter도 첫 폴링 뒤에 생긴 대기자를 true로 관측한다")
    void awaitAnyWaiter_seesWaiterThatAppearsAfterFirstEmptyPoll() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        startWaiter(gate, new AtomicLong());
        LockWaitObserver delayedWaiterObserver = new LockWaitObserver(url(), MARIA_DB.getPassword(), gate::countDown);

        assertThat(delayedWaiterObserver.awaitAnyWaiter(Duration.ofSeconds(5))).isTrue();
        assertThat(gate.getCount()).isZero();
    }

    @Test
    @DisplayName("직전 관측이 본 대기가 이미 끝났다면 곧바로 시작한 새 관측은 그 낡은 스냅샷을 재사용하지 않는다 (캐시 정체의 반대 방향, #121 리뷰)")
    void awaitAnyWaiter_doesNotReuseStaleSnapshotOfAFinishedWait() throws Exception {
        // 1) 대기를 관측해 information_schema 캐시에 "대기자 있음" 스냅샷을 남긴다
        Future<?> waiter = startWaiter(null, new AtomicLong());
        observer.awaitWaiterFor(holderConnectionId, Duration.ofSeconds(5));

        // 2) 대기를 끝낸다 — holder가 풀리면 대기자는 락을 얻고 마친다. 이제 DB에는 대기자가 없다
        holder.rollback();
        waiter.get(10, TimeUnit.SECONDS);

        // 3) 곧바로 새 관측: 대기자가 없으니 false여야 한다. 직전 읽기와의 간격이 100ms 안이면 캐시가 갱신되지 않아 낡은 스냅샷이 true를 줄 수 있다
        assertThat(observer.awaitAnyWaiter(Duration.ofMillis(400))).as("끝난 대기를 낡은 스냅샷으로 다시 보면 안 된다").isFalse();
    }

    @Test
    @DisplayName("시간 제한은 첫 조회 전 캐시 위생 대기가 끝난 뒤부터 센다 — 제한이 0이어도 최소 한 번은 조회해 이미 있는 대기를 관측한다 (#121 리뷰 2라운드)")
    void timeoutStartsAfterCacheHygieneWait_andPollsAtLeastOnce() throws Exception {
        Future<?> waiter = startWaiter(null, new AtomicLong());
        observer.awaitWaiterFor(holderConnectionId, Duration.ofSeconds(5));   // 대기가 실제로 존재하는 상태를 만든다

        // 제한 0: 위생 대기(150ms) 뒤 한 번 조회하므로, 이미 있는 대기를 true로 관측해야 한다(만료 뒤 조회라서 실패하면 안 된다)
        assertThat(observer.awaitAnyWaiter(Duration.ZERO)).isTrue();
        // 제한 0이고 대기자가 없는 상태에서는 한 번 조회하고 false다
        holder.rollback();
        waiter.get(10, TimeUnit.SECONDS);
        assertThat(observer.awaitAnyWaiter(Duration.ZERO)).isFalse();
    }

    @Test
    @DisplayName("awaitWaiterPair는 holder·waiter 연결 쌍을 관측하고 대기 중인 문장(FOR UPDATE)을 돌려준다")
    void awaitWaiterPair_returnsWaitingQuery() throws Exception {
        AtomicLong waiterConnectionId = new AtomicLong();
        startWaiter(null, waiterConnectionId);
        // 대기자의 연결 ID는 대기자 스레드가 정한다 — 정해질 때까지 기다린 뒤 쌍으로 관측한다
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (waiterConnectionId.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(waiterConnectionId.get()).isNotZero();

        String query = observer.awaitWaiterPair(holderConnectionId, waiterConnectionId.get(), Duration.ofSeconds(5));

        assertThat(query.toLowerCase(java.util.Locale.ROOT)).contains("for update");
    }

    @Test
    @DisplayName("대기자가 없으면 awaitAnyWaiter는 시간 제한 뒤 false다")
    void awaitAnyWaiter_returnsFalseWhenNobodyWaits() throws Exception {
        long start = System.nanoTime();

        boolean found = observer.awaitAnyWaiter(Duration.ofMillis(500));

        assertThat(found).isFalse();
        assertThat((System.nanoTime() - start) / 1_000_000).as("시간 제한(500ms)은 지켜야 한다").isGreaterThanOrEqualTo(450);
    }

    @Test
    @DisplayName("대기자가 없으면 awaitWaiterFor·awaitWaiterPair는 holder 연결을 담은 메시지로 시험을 실패시킨다")
    void awaitWaiterFailsWithHolderInMessageWhenNobodyWaits() {
        assertThatThrownBy(() -> observer.awaitWaiterFor(holderConnectionId, Duration.ofMillis(400)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("holder=" + holderConnectionId);
        assertThatThrownBy(() -> observer.awaitWaiterPair(holderConnectionId, 999_999L, Duration.ofMillis(400)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("holder=" + holderConnectionId).hasMessageContaining("waiter=999999");
    }
}
