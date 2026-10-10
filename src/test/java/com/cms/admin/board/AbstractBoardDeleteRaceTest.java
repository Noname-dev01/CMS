package com.cms.admin.board;

import com.cms.admin.board.repository.PostRepository;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.MemberBoardPermissionRepository;
import com.cms.admin.permission.PermissionCache;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 게시판 삭제의 동시성 계약(PLAN-board.md 쟁점 5·6·7, 리뷰 R3-3·R4-2)을 {@code innodb_snapshot_isolation} ON·OFF 두 설정에서 확인하는 공통 시험.
 * 설정은 연결 초기화 SQL로 풀의 모든 연결에 걸고 서브클래스마다 전용 Spring 컨텍스트를 쓴다({@code AbstractMessageSnapshotIsolationTest}와 같은 방식 —
 * {@code SET SESSION}은 풀의 물리 연결에 남아 공유 컨텍스트를 오염시키므로 {@code @DirtiesContext}로 폐기한다).
 *
 * <ul>
 *   <li><b>삭제 ↔ 회수 PUT</b>: 회수 PUT이 기존 행을 읽은 뒤 그 게시판 삭제가 행을 지우고 커밋한다. 회수는 키 단위 벌크 삭제라 0건이어도 성공해야 한다
 *       (OFF = 200, 엔티티 삭제였다면 stale-state 500). ON이면 스냅샷 이후 지워진 행의 잠금이 오류 1020이 되어 409이고 PUT 전체가 롤백된다.
 *       어느 쪽이든 재조회 후 재저장이 성공한다.</li>
 *   <li><b>삭제 ↔ 게시글 생성</b>: 생성이 게시판 행을 {@code FOR SHARE}로 쥔 동안 삭제({@code FOR UPDATE})는 기다리고, 생성이 커밋된 뒤 삭제는 살아 있는
 *       게시글을 보고 409다 — 고아 게시글이 생기지 않는다.</li>
 * </ul>
 */
abstract class AbstractBoardDeleteRaceTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired JdbcConnectionDetails connectionDetails;
    @Autowired MemberRepository memberRepository;
    @Autowired PermissionCache cache;
    @Autowired ObjectMapper objectMapper;

    private Member admin;
    private Member manager;
    private final List<Long> boardIds = new ArrayList<>();
    private long auditBaseline;

    /** 이 서브클래스 컨텍스트의 {@code innodb_snapshot_isolation} 기대값. */
    abstract boolean snapshotIsolationExpected();

    // ===================== 계측 =====================

    static final class Probe {
        static volatile CountDownLatch revokeReached;
        static volatile CountDownLatch revokeRelease;
        static volatile CountDownLatch postSaved;
        static volatile CountDownLatch postRelease;

        static void reset() {
            revokeReached = null;
            revokeRelease = null;
            postSaved = null;
            postRelease = null;
        }

        private static void awaitOrFail(CountDownLatch latch) throws InterruptedException {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("시험 래치 해제 시간 초과");
            }
        }

        /** 회수 PUT이 키 단위 삭제에 도달하면 멈춘다 — 그 사이 시험이 게시판을 삭제·커밋한다. */
        static Object aroundPermission(MethodInvocation invocation) throws Throwable {
            if ("deleteKey".equals(invocation.getMethod().getName()) && revokeReached != null) {
                revokeReached.countDown();
                awaitOrFail(revokeRelease);
            }
            return invocation.proceed();
        }

        /** 게시글이 INSERT된(아직 커밋 전, 게시판 공유 잠금 보유) 시점에 멈춘다. */
        static Object aroundPost(MethodInvocation invocation) throws Throwable {
            Object result = invocation.proceed();
            if ("save".equals(invocation.getMethod().getName()) && postSaved != null) {
                postSaved.countDown();
                awaitOrFail(postRelease);
            }
            return result;
        }
    }

    @TestConfiguration
    static class Instrumentation {
        @Bean
        static BeanPostProcessor boardRaceProbe() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof MemberBoardPermissionRepository) {
                        ProxyFactory factory = new ProxyFactory(bean);
                        factory.addAdvice((MethodInterceptor) Probe::aroundPermission);
                        return factory.getProxy();
                    }
                    if (bean instanceof PostRepository) {
                        ProxyFactory factory = new ProxyFactory(bean);
                        factory.addAdvice((MethodInterceptor) Probe::aroundPost);
                        return factory.getProxy();
                    }
                    return bean;
                }
            };
        }
    }

    // ===================== 준비·정리 =====================

    @BeforeEach
    void setUp() {
        Probe.reset();
        admin = TestMembers.save(memberRepository, "race-admin", Role.ROLE_ADMIN);
        manager = TestMembers.save(memberRepository, "race-manager", Role.ROLE_MANAGER);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
    }

    @AfterEach
    void cleanUp() {
        Probe.reset();
        for (Long boardId : boardIds) {
            jdbc.update("DELETE FROM content_image_ref WHERE owner_type = 'POST' AND owner_id IN (SELECT id FROM post WHERE board_id = ?)", boardId);
            jdbc.update("DELETE FROM post WHERE board_id = ?", boardId);
            jdbc.update("DELETE FROM member_board_permission WHERE board_id = ?", boardId);
            jdbc.update("DELETE FROM board WHERE id = ?", boardId);
        }
        TestMembers.delete(jdbc, List.of(admin.getId(), manager.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE id > ?", auditBaseline);
        cache.invalidate();
    }

    private long createBoard() throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/api/boards").with(TestMembers.asMember(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", "race-" + System.nanoTime(), "publicYn", true, "attachmentYn", true)))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        long id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        boardIds.add(id);
        return id;
    }

    private long version() {
        return jdbc.queryForObject("SELECT permission_version FROM member WHERE id = ?", Long.class, manager.getId());
    }

    private MvcResult putGrants(long version, String boardGrantsJson) throws Exception {
        return mockMvc.perform(put("/admin/api/members/{id}/permissions", manager.getId()).with(TestMembers.asMember(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + ",\"grants\":[],\"boardGrants\":" + boardGrantsJson + "}")).andReturn();
    }

    /** 락을 기다리는 트랜잭션이 생길 때까지 관측한다 — information_schema.INNODB_LOCK_WAITS는 PROCESS 권한이 필요해 root로 조회한다(sleep을 증거로 쓰지 않는다). */
    private boolean awaitSomeoneWaiting() throws Exception {
        try (Connection observer = DriverManager.getConnection(connectionDetails.getJdbcUrl(), "root", connectionDetails.getPassword());
             var query = observer.prepareStatement("SELECT COUNT(*) FROM information_schema.INNODB_LOCK_WAITS")) {
            query.setQueryTimeout(2);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            do {
                try (var result = query.executeQuery()) {
                    if (result.next() && result.getInt(1) > 0) {
                        return true;
                    }
                }
                Thread.sleep(25);
            } while (System.nanoTime() < deadline);
            return false;
        }
    }

    /**
     * 락 대기가 관측되지 않았을 때의 진단 문자열(이슈 #119) — 실패 메시지에만 쓰이고 시험의 판정에는 영향이 없다. 호출 시점은 생성 스레드가 아직 래치에 묶여
     * 있는 때라(릴리스·{@code shutdownNow()} 전) 삭제 요청이 어디에서 멈춰 있는지가 그대로 남아 있다. 어떤 조회가 실패해도 진단 때문에 예외가 새지 않는다.
     */
    private String diagnoseNoLockWait(Future<Integer> deleteBoard) {
        StringBuilder out = new StringBuilder("\n[진단] 삭제 요청 완료 여부: ").append(deleteBoard.isDone()).append('\n');
        try (Connection observer = DriverManager.getConnection(connectionDetails.getJdbcUrl(), "root", connectionDetails.getPassword());
             Statement statement = observer.createStatement()) {
            statement.setQueryTimeout(3);
            out.append(dumpRows(statement, "INNODB_TRX", "SELECT * FROM information_schema.INNODB_TRX"));
            out.append(dumpRows(statement, "INNODB_LOCK_WAITS", "SELECT * FROM information_schema.INNODB_LOCK_WAITS"));
            out.append(dumpRows(statement, "PROCESSLIST", "SELECT ID, USER, DB, COMMAND, TIME, STATE, INFO FROM information_schema.PROCESSLIST"));
        } catch (Exception e) {
            out.append("[진단] DB 조회 실패: ").append(e).append('\n');
        }
        try {
            var pool = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).getHikariPoolMXBean();
            out.append("[진단] Hikari 풀: active=").append(pool.getActiveConnections())
                    .append(", idle=").append(pool.getIdleConnections())
                    .append(", total=").append(pool.getTotalConnections())
                    .append(", threadsAwaitingConnection=").append(pool.getThreadsAwaitingConnection()).append('\n');
        } catch (Exception e) {
            out.append("[진단] Hikari 풀 상태 조회 실패: ").append(e).append('\n');
        }
        Thread.getAllStackTraces().forEach((thread, frames) -> {
            if (thread.getName().startsWith("pool-")) {
                out.append("[진단] 스레드 ").append(thread.getName()).append(" (").append(thread.getState()).append(")\n");
                for (int i = 0; i < Math.min(frames.length, 14); i++) {
                    out.append("    at ").append(frames[i]).append('\n');
                }
            }
        });
        return out.toString();
    }

    private static String dumpRows(Statement statement, String label, String sql) {
        StringBuilder out = new StringBuilder("[진단] ").append(label).append(":\n");
        try (java.sql.ResultSet rs = statement.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            int rows = 0;
            while (rs.next()) {
                rows++;
                out.append("    ");
                for (int c = 1; c <= columns; c++) {
                    String value = String.valueOf(rs.getString(c));
                    out.append(rs.getMetaData().getColumnLabel(c)).append('=')
                            .append(value.length() > 140 ? value.substring(0, 140) + "…" : value).append(c < columns ? " | " : "");
                }
                out.append('\n');
            }
            if (rows == 0) {
                out.append("    (없음)\n");
            }
        } catch (Exception e) {
            out.append("    조회 실패: ").append(e).append('\n');
        }
        return out.toString();
    }

    // ===================== 시험 =====================

    @Test
    @DisplayName("사전 조건: 이 컨텍스트의 모든 연결이 기대한 innodb_snapshot_isolation 값이다(지원 필수 — 건너뛰지 않는다)")
    void precondition_sessionVariableMatchesContext() throws Exception {
        for (int i = 0; i < 3; i++) {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                 var rs = statement.executeQuery("SELECT @@session.innodb_snapshot_isolation")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1) == 1).isEqualTo(snapshotIsolationExpected());
            }
        }
    }

    @Test
    @DisplayName("[R3-3·R4-2] 회수 PUT이 행을 읽은 뒤 그 게시판이 삭제·커밋되면: OFF=200(멱등 벌크 삭제), ON=409(전체 롤백·버전 불변), 재조회 후 재저장은 어느 쪽이든 성공")
    void revokeRacingWithBoardDelete() throws Exception {
        long boardId = createBoard();
        jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, 'READ')", manager.getId(), boardId);
        cache.invalidate();
        long versionBefore = version();

        Probe.revokeReached = new CountDownLatch(1);
        Probe.revokeRelease = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> revoke = executor.submit(() -> putGrants(versionBefore, "[]"));
            assertThat(Probe.revokeReached.await(20, TimeUnit.SECONDS)).as("PUT이 키 단위 삭제에 도달").isTrue();

            // PUT이 기존 행을 읽은 뒤 — 회수 대상 게시판은 PUT이 잠그지 않았으므로 삭제가 끼어들어 커밋할 수 있다
            int deleteStatus = mockMvc.perform(delete("/admin/api/boards/{id}", boardId).with(TestMembers.asMember(admin)).with(csrf()))
                    .andReturn().getResponse().getStatus();
            assertThat(deleteStatus).isEqualTo(204);
            assertThat(jdbc.queryForObject("SELECT deleted FROM board WHERE id = ?", Boolean.class, boardId)).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_board_permission WHERE board_id = ?", Long.class, boardId)).isZero();
            Probe.revokeRelease.countDown();

            MvcResult result = revoke.get(30, TimeUnit.SECONDS);
            if (snapshotIsolationExpected()) {
                assertThat(result.getResponse().getStatus()).as("ON: 스냅샷 이후 지워진 행의 잠금(1020) → 409").isEqualTo(409);
                assertThat(version()).as("409면 PUT 전체가 롤백되어 버전 불변").isEqualTo(versionBefore);
            } else {
                assertThat(result.getResponse().getStatus()).as("OFF: 벌크 삭제 0건도 성공").isEqualTo(200);
                assertThat(version()).isEqualTo(versionBefore + 1);
            }
        } finally {
            Probe.revokeRelease.countDown();
            executor.shutdownNow();
        }

        // 어느 쪽이든 재조회한 버전으로 다시 저장하면 성공한다(삭제된 게시판의 행은 이미 없다)
        Probe.reset();
        MvcResult refetch = mockMvc.perform(get("/admin/api/members/{id}/permissions", manager.getId()).with(TestMembers.asMember(admin))).andReturn();
        JsonNode matrix = objectMapper.readTree(refetch.getResponse().getContentAsString());
        assertThat(putGrants(matrix.get("version").asLong(), "[]").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("[쟁점 7] 게시글 생성이 게시판 공유 잠금을 쥔 동안 게시판 삭제는 기다리고, 생성이 커밋되면 삭제는 409 — 고아 게시글이 생기지 않는다")
    void boardDeleteWaitsForPostCreateAndThenConflicts() throws Exception {
        long boardId = createBoard();
        jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, 'READ')", manager.getId(), boardId);
        jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, 'CREATE')", manager.getId(), boardId);
        cache.invalidate();

        Probe.postSaved = new CountDownLatch(1);
        Probe.postRelease = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> create = executor.submit(() -> mockMvc.perform(
                    post("/admin/api/boards/{b}/posts", boardId).with(TestMembers.asMember(manager)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "경합글", "content", "<p>x</p>", "contentFormat", "HTML"))))
                    .andReturn());
            assertThat(Probe.postSaved.await(20, TimeUnit.SECONDS)).as("게시글 INSERT 완료(커밋 전)").isTrue();

            Future<Integer> deleteBoard = executor.submit(() -> mockMvc.perform(
                    delete("/admin/api/boards/{id}", boardId).with(TestMembers.asMember(admin)).with(csrf()))
                    .andReturn().getResponse().getStatus());

            // 삭제 요청이 게시판 행 잠금을 기다리는 것을 DB에서 관측한다 — 공유 잠금이 없다면 삭제가 바로 진행됐을 것이다
            boolean waiting = awaitSomeoneWaiting();
            assertThat(waiting).as("삭제가 생성의 게시판 공유 잠금을 기다린다%s", waiting ? "" : diagnoseNoLockWait(deleteBoard)).isTrue();
            assertThat(deleteBoard.isDone()).isFalse();

            Probe.postRelease.countDown();
            assertThat(create.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
            assertThat(deleteBoard.get(30, TimeUnit.SECONDS)).as("커밋된 게시글이 보이므로 409").isEqualTo(409);
        } finally {
            Probe.postRelease.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT deleted FROM board WHERE id = ?", Boolean.class, boardId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post WHERE board_id = ? AND deleted = 0", Long.class, boardId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_board_permission WHERE board_id = ?", Long.class, boardId)).isEqualTo(2);
    }
}
