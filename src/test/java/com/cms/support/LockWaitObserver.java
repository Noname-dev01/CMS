package com.cms.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 동시성 시험의 "실제 DB 락 대기" 관측 — 락을 기다리는 트랜잭션이 생길 때까지 {@code information_schema.INNODB_LOCK_WAITS}·{@code INNODB_TRX}를
 * 폴링한다(sleep을 증거로 쓰지 않는다). 이 뷰들은 PROCESS 권한이 필요해 root로 조회하므로 일회용 Testcontainers 컨테이너 전용이다.
 *
 * <p><b>폴링 간격은 100ms를 넘겨야 한다</b>(이슈 #119·#121). InnoDB는 이 뷰를 캐시로 돌려주는데, 그보다 촘촘히(예: 25ms) 계속 읽으면 첫 조회 시점의
 * 스냅샷이 갱신되지 않은 채 남아, 대기자가 이미 락을 기다리고 있어도 대기를 수 초~15초 넘게 놓친다. 첫 조회가 대기자의 락 요청보다 먼저 나가는 순서
 * (빠른 CI처럼 대기자 경로가 관측용 연결 생성보다 느린 환경)에서 그대로 시험이 실패한다. 실측(같은 MariaDB 10.11 이미지, 대기자가 생기기 <b>전에</b>
 * 폴링 시작): 25ms 간격은 9초~15초 이상 못 보았고 150ms 간격은 26~148ms 안에 보았다. {@code SHOW ENGINE INNODB STATUS}·{@code PROCESSLIST}는
 * 이 캐시를 거치지 않는다. 이 값은 이 클래스 한 곳에만 두고, 락 대기 시험은 직접 폴링하지 않고 이 클래스를 쓴다 — 복제본 하나만 고쳐 나머지가 남았던
 * 것이 #121이었다.
 *
 * <p>같은 캐시가 <b>반대 방향</b>으로도 작용한다: 직전 관측이 100ms 이내에 읽은 스냅샷은 갱신되지 않아, 그 스냅샷에 남은 <b>이미 끝난 대기</b>를 새 관측이
 * "대기자 있음"으로 받을 수 있다. 그래서 첫 조회 전에 폴링 간격만큼 쉬어 첫 조회가 캐시를 갱신하게 한다(<b>시간 제한은 이 대기가 끝난 뒤부터 센다</b> —
 * 관측 창은 호출 후 간격만큼 뒤에 열리고, 제한이 아무리 짧아도 최소 한 번은 조회한다). 또 {@link #awaitAnyWaiter}는 전역(어떤 대기자든)이라
 * 이런 낡은 스냅샷에 취약하므로, 대기자를 특정할 수 있으면 holder·waiter 연결 ID로 좁히는 {@link #awaitWaiterFor}·{@link #awaitWaiterPair}를 쓴다(연결 ID는
 * 매번 새로 생겨 낡은 스냅샷과 겹치지 않는다).
 */
public final class LockWaitObserver {

    /** 폴링 간격(ms). 100ms 초과여야 한다 — 클래스 설명 참조. */
    static final long POLL_INTERVAL_MS = 150;

    private final String jdbcUrl;
    private final String rootPassword;
    private final Runnable afterFirstEmptyPoll;

    public LockWaitObserver(String jdbcUrl, String rootPassword) {
        this(jdbcUrl, rootPassword, () -> { });
    }

    /**
     * 시험용 훅: <b>첫 조회가 대기를 찾지 못한 직후</b> 한 번 호출된다. 헬퍼 자신의 시험({@code LockWaitObserverTest})이 "대기자가 첫 폴링 뒤에 생기는"
     * 순서를 시간 지연 없이 결정적으로 만들기 위한 것이다(시간 지연은 관측 스레드가 늦으면 그 순서가 안 나온다).
     */
    LockWaitObserver(String jdbcUrl, String rootPassword, Runnable afterFirstEmptyPoll) {
        this.jdbcUrl = jdbcUrl;
        this.rootPassword = rootPassword;
        this.afterFirstEmptyPoll = afterFirstEmptyPoll;
    }

    /** 락을 기다리는 트랜잭션이 하나라도 생기면 true, {@code timeout} 안에 안 생기면 false. */
    public boolean awaitAnyWaiter(Duration timeout) throws Exception {
        return poll("SELECT COUNT(*) FROM information_schema.INNODB_LOCK_WAITS", query -> { },
                rs -> rs.next() && rs.getInt(1) > 0 ? Optional.of(Boolean.TRUE) : Optional.empty(), timeout).isPresent();
    }

    /** {@code holder} 연결이 잡은 락을 기다리는 트랜잭션이 생길 때까지 관측한다. {@code timeout} 안에 안 생기면 시험을 실패시킨다. */
    public void awaitWaiterFor(long holderConnectionId, Duration timeout) throws Exception {
        Optional<Boolean> found = poll("""
                        SELECT COUNT(*) FROM information_schema.INNODB_LOCK_WAITS locks
                        JOIN information_schema.INNODB_TRX holding ON holding.trx_id = locks.blocking_trx_id
                        WHERE holding.trx_mysql_thread_id = ?
                        """,
                query -> query.setLong(1, holderConnectionId),
                rs -> rs.next() && rs.getInt(1) > 0 ? Optional.of(Boolean.TRUE) : Optional.empty(), timeout);
        if (found.isEmpty()) {
            fail("실제 DB row-lock 대기를 관측하지 못함: holder=" + holderConnectionId);
        }
    }

    /**
     * {@code waiter} 연결이 {@code holder} 연결의 락을 기다리는 모습을 관측하고, 대기 중인 문장({@code trx_query})을 돌려준다. {@code timeout} 안에 안
     * 보이면 시험을 실패시킨다.
     */
    public String awaitWaiterPair(long holderConnectionId, long waiterConnectionId, Duration timeout) throws Exception {
        Optional<String> found = poll("""
                        SELECT waiting.trx_query FROM information_schema.INNODB_LOCK_WAITS locks
                        JOIN information_schema.INNODB_TRX waiting ON waiting.trx_id = locks.requesting_trx_id
                        JOIN information_schema.INNODB_TRX holding ON holding.trx_id = locks.blocking_trx_id
                        WHERE waiting.trx_mysql_thread_id = ? AND holding.trx_mysql_thread_id = ?
                        """,
                query -> {
                    query.setLong(1, waiterConnectionId);
                    query.setLong(2, holderConnectionId);
                },
                rs -> rs.next() ? Optional.of(rs.getString(1) == null ? "" : rs.getString(1)) : Optional.empty(), timeout);
        if (found.isEmpty()) {
            fail("실제 DB row-lock 대기를 관측하지 못함: holder=" + holderConnectionId + ", waiter=" + waiterConnectionId);
        }
        return found.get();
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement query) throws Exception;
    }

    @FunctionalInterface
    private interface Reader<T> {
        Optional<T> read(ResultSet resultSet) throws Exception;
    }

    private <T> Optional<T> poll(String sql, Binder binder, Reader<T> reader, Duration timeout) throws Exception {
        try (Connection observer = DriverManager.getConnection(jdbcUrl, "root", rootPassword);
             PreparedStatement query = observer.prepareStatement(sql)) {
            binder.bind(query);
            query.setQueryTimeout(2);
            // 첫 조회 전에 간격만큼 쉰다 — 캐시 정체의 반대 방향을 막는 것이다: 직전 관측(100ms 이내)이 읽은 스냅샷이 아직 갱신되지 않았다면, 이미
            // 끝난 대기를 새 관측이 그대로 받아 "대기자 있음"으로 오판한다(대기자가 없는 상태를 확인하는 시험이 true를 받았던 사례, #121 리뷰).
            // 직전 읽기와 100ms 넘게 떨어져 시작하면 첫 조회가 캐시를 갱신한다. 이 대기는 증거가 아니라 고정된 준비 시간(캐시 위생)이라 timeout은
            // 이 대기가 끝난 뒤부터 센다 — 그래서 timeout이 아무리 짧아도 최소 한 번은 조회하고(원래 do-while 의미), 만료 뒤 조회가 성공을 주는 모순이 없다.
            Thread.sleep(POLL_INTERVAL_MS);
            long deadline = System.nanoTime() + timeout.toNanos();
            boolean firstPoll = true;
            do {
                try (ResultSet resultSet = query.executeQuery()) {
                    Optional<T> found = reader.read(resultSet);
                    if (found.isPresent()) {
                        return found;
                    }
                }
                if (firstPoll) {
                    firstPoll = false;
                    afterFirstEmptyPoll.run();
                }
                Thread.sleep(POLL_INTERVAL_MS);
            } while (System.nanoTime() < deadline);
            return Optional.empty();
        }
    }
}
