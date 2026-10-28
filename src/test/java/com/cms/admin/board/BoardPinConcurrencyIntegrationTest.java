package com.cms.admin.board;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionCache;
import com.cms.config.auth.AdminSecurityService;
import com.cms.support.CmsTestApplication;
import com.cms.support.LockWaitObserver;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 상단 고정 개수 상한의 동시성 <b>계약</b>을 실제 MariaDB로 증명한다(PLAN-board-definition.md PR 3 D3-4):
 * <ul>
 *   <li>상한 직전(2개 중 1개)에서 동시에 새로 고정하는 두 쓰기는 정확히 하나만 성공하고 하나는 409 — 생성↔생성·생성↔수정·수정↔수정</li>
 *   <li>상한을 낮추는 게시판 PATCH가 진행 중이면 고정 쓰기는 기다렸다가 낮아진 상한을 보고 409</li>
 *   <li>{@code createPost}·{@code updatePost}는 {@code READ_COMMITTED} 트랜잭션에서 실행된다(잠금 뒤의 개수 읽기가 앞 커밋을 보게 하는 전제)</li>
 * </ul>
 * 동기화는 "시험이 게시판 행을 먼저 쥔 채 두 요청을 보내고, 요청이 그 잠금을 기다리는 것을 관측한 뒤 해제"한다 — 요청 시작만 맞추면 잘못된
 * 구현(게시판 FOR SHARE)도 우연히 한쪽이 먼저 커밋해 200·409가 나올 수 있다. 고정 쓰기가 게시판을 공유 잠금으로 잡으면 시험이 쥔 공유 잠금과
 * 호환돼 대기가 생기지 않으므로 관측 단계에서 실패한다(변이 실험).
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class BoardPinConcurrencyIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired PermissionCache cache;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcConnectionDetails connectionDetails;
    @MockitoSpyBean AdminSecurityService adminSecurityService;

    private Member admin;
    private final List<Long> boardIds = new ArrayList<>();
    private long auditBaseline;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "pin-admin", Role.ROLE_ADMIN);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
    }

    @AfterEach
    void cleanUp() {
        for (Long boardId : boardIds) {
            jdbc.update("DELETE FROM post WHERE board_id = ?", boardId);
            jdbc.update("DELETE FROM board WHERE id = ?", boardId);
        }
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE id > ?", auditBaseline);
        cache.invalidate();
    }

    private RequestPostProcessor asAdmin() {
        return TestMembers.asMember(admin);
    }

    private long insertBoard(int pinMax) {
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted, pin_max_count) VALUES (?, 1, 1, 0, ?)", "PIN_" + System.nanoTime(), pinMax);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM board", Long.class);
        boardIds.add(id);
        return id;
    }

    private long insertPost(long boardId, boolean pinned) {
        jdbc.update("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, pinned, create_date, update_date)"
                + " VALUES (?, 'PIN 글', '<p>x</p>', 1, 0, 'pin-admin', ?, NOW(6), NOW(6))", boardId, pinned);
        return jdbc.queryForObject("SELECT MAX(id) FROM post WHERE board_id = ?", Long.class, boardId);
    }

    private long pinnedCount(long boardId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM post WHERE board_id = ? AND pinned = 1 AND deleted = 0", Long.class, boardId);
    }

    private TransactionTemplate readCommitted() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("해제 신호 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private LockWaitObserver observer() {
        return new LockWaitObserver(connectionDetails.getJdbcUrl(), connectionDetails.getPassword());
    }

    private int createPinned(long boardId) throws Exception {
        String json = "{\"title\":\"PIN 새 글\",\"content\":\"<p>x</p>\",\"contentFormat\":\"HTML\",\"useYn\":true,\"pinned\":true}";
        return mockMvc.perform(post("/admin/api/boards/{b}/posts", boardId).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn().getResponse().getStatus();
    }

    private int pin(long boardId, long postId) throws Exception {
        return mockMvc.perform(patch("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":true}")).andReturn().getResponse().getStatus();
    }

    /**
     * 시험이 게시판 행을 {@code LOCK IN SHARE MODE}로 쥔 채 두 요청을 보내고, 요청이 그 잠금을 기다리는 것을 관측한 뒤 해제해 두 상태 코드를
     * 정렬해 돌려준다. 배타 잠금을 잡는 구현은 이 공유 잠금과 충돌해 대기하고, 공유 잠금을 잡는 구현은 대기하지 않는다.
     */
    private List<Integer> runBehindSharedBoardLock(long boardId, Callable<Integer> first, Callable<Integer> second) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = executor.submit(() -> readCommitted().executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM board WHERE id = ? LOCK IN SHARE MODE", Long.class, boardId);
                holderConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                held.countDown();
                await(release);
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();

            Future<Integer> a = executor.submit(first);
            Future<Integer> b = executor.submit(second);
            observer().awaitWaiterFor(holderConnection.get(), Duration.ofSeconds(8));
            assertThat(a.isDone() || b.isDone()).as("고정 쓰기는 게시판 배타 잠금을 기다려야 한다").isFalse();

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            List<Integer> statuses = new ArrayList<>(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)));
            statuses.sort(Integer::compare);
            return statuses;
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("생성↔생성: 상한 2에 고정 1개일 때 동시에 새로 고정하는 두 생성은 정확히 하나만 201이고 하나는 409이며 고정은 2개다")
    void createVsCreate() throws Exception {
        long boardId = insertBoard(2);
        insertPost(boardId, true);

        List<Integer> statuses = runBehindSharedBoardLock(boardId, () -> createPinned(boardId), () -> createPinned(boardId));

        assertThat(statuses).containsExactly(201, 409);
        assertThat(pinnedCount(boardId)).isEqualTo(2);
    }

    @Test
    @DisplayName("생성↔수정: 동시에 새로 고정하는 생성과 수정은 정확히 하나만 성공하고 고정은 2개다")
    void createVsUpdate() throws Exception {
        long boardId = insertBoard(2);
        insertPost(boardId, true);
        long target = insertPost(boardId, false);

        List<Integer> statuses = runBehindSharedBoardLock(boardId, () -> createPinned(boardId), () -> pin(boardId, target));

        // 누가 먼저 잠금을 얻든 성공은 정확히 하나(생성 201 또는 수정 200)이고 나머지는 409다
        assertThat(statuses).hasSize(2);
        assertThat(statuses.get(0)).isIn(200, 201);
        assertThat(statuses.get(1)).isEqualTo(409);
        assertThat(pinnedCount(boardId)).isEqualTo(2);
    }

    @Test
    @DisplayName("수정↔수정: 서로 다른 두 글을 동시에 고정하면 정확히 하나만 200이고 하나는 409이며 고정은 2개다")
    void updateVsUpdate() throws Exception {
        long boardId = insertBoard(2);
        insertPost(boardId, true);
        long first = insertPost(boardId, false);
        long second = insertPost(boardId, false);

        List<Integer> statuses = runBehindSharedBoardLock(boardId, () -> pin(boardId, first), () -> pin(boardId, second));

        assertThat(statuses).containsExactly(200, 409);
        assertThat(pinnedCount(boardId)).isEqualTo(2);
    }

    @Test
    @DisplayName("상한을 낮추는 게시판 PATCH가 진행 중이면 고정 쓰기는 기다렸다가 낮아진 상한을 보고 409이며 고정은 늘지 않는다")
    void pinWaitsForLimitLowering() throws Exception {
        long boardId = insertBoard(2);
        insertPost(boardId, true);
        long target = insertPost(boardId, false);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 게시판 PATCH와 같은 잠금(FOR UPDATE): 상한을 1로 낮췄지만 아직 커밋하지 않았다
            Future<?> lowering = executor.submit(() -> readCommitted().executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM board WHERE id = ? FOR UPDATE", Long.class, boardId);
                holderConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                jdbc.update("UPDATE board SET pin_max_count = 1, version = version + 1 WHERE id = ?", boardId);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();

            Future<Integer> pin = executor.submit(() -> pin(boardId, target));
            observer().awaitWaiterFor(holderConnection.get(), Duration.ofSeconds(8));
            assertThat(pin.isDone()).as("상한 변경이 게시판 행을 쥐는 동안 고정은 끝나지 않는다").isFalse();

            release.countDown();
            lowering.get(10, TimeUnit.SECONDS);
            assertThat(pin.get(10, TimeUnit.SECONDS)).as("커밋된 낮은 상한을 보고 거절").isEqualTo(409);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(pinnedCount(boardId)).isEqualTo(1);
    }

    @Test
    @DisplayName("createPost·updatePost는 READ_COMMITTED 트랜잭션에서 실행된다 (잠금 뒤 개수 읽기가 앞서 커밋된 고정을 보는 전제)")
    void postWritesRunAtReadCommitted() throws Exception {
        long boardId = insertBoard(2);
        long target = insertPost(boardId, false);
        List<Integer> seen = new CopyOnWriteArrayList<>();
        Mockito.doAnswer(invocation -> {
            seen.add(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            return invocation.callRealMethod();
        }).when(adminSecurityService).getCurrentAdminUserId();

        assertThat(createPinned(boardId)).isEqualTo(201);
        long afterCreate = seen.stream().filter(level -> level != null && level == TransactionDefinition.ISOLATION_READ_COMMITTED).count();
        assertThat(afterCreate).as("생성 트랜잭션의 격리 수준").isEqualTo(1);

        assertThat(pin(boardId, target)).isEqualTo(200);
        long afterUpdate = seen.stream().filter(level -> level != null && level == TransactionDefinition.ISOLATION_READ_COMMITTED).count();
        assertThat(afterUpdate).as("수정 트랜잭션의 격리 수준").isEqualTo(2);
    }
}
