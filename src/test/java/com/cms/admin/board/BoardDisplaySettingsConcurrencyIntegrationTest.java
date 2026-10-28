package com.cms.admin.board;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionCache;
import com.cms.support.CmsTestApplication;
import com.cms.support.LockWaitObserver;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * 게시판 {@code version}과 쓰기 중지 전환의 동시성 <b>계약</b>을 실제 MariaDB로 증명한다(PLAN-board-definition.md PR 1 §4 D2·D5):
 * <ul>
 *   <li>같은 {@code version}으로 동시에 PATCH하면 하나만 200이고 나머지는 409(낡은 화면 보호)</li>
 *   <li>쓰기 중지 전환(게시판 {@code FOR UPDATE})이 진행 중이면 게시글 수정은 게시판 {@code FOR SHARE}에서 기다렸다가 전환 결과를 보고 400</li>
 *   <li>진행 중인 쓰기가 게시판 공유 잠금을 쥐고 있으면 쓰기 중지 전환은 그것이 끝나기를 기다린다</li>
 * </ul>
 * 수정이 게시판 공유 잠금을 잡지 않으면 두 번째 시나리오에서 수정이 전환 전 값을 읽어 200이 된다(변이 실험).
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class BoardDisplaySettingsConcurrencyIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired PermissionCache cache;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcConnectionDetails connectionDetails;

    private Member admin;
    private final List<Long> boardIds = new ArrayList<>();
    private long auditBaseline;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "bdc-admin", Role.ROLE_ADMIN);
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

    private long insertBoard() {
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES (?, 1, 1, 0)", "BDC_" + System.nanoTime());
        long id = jdbc.queryForObject("SELECT MAX(id) FROM board", Long.class);
        boardIds.add(id);
        return id;
    }

    private long insertPost(long boardId) {
        jdbc.update("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, create_date, update_date)"
                + " VALUES (?, 'BDC 원래 제목', '<p>x</p>', 1, 0, 'bdc-admin', NOW(6), NOW(6))", boardId);
        return jdbc.queryForObject("SELECT MAX(id) FROM post WHERE board_id = ?", Long.class, boardId);
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

    private int patchBoard(long boardId, String json) throws Exception {
        return mockMvc.perform(patch("/admin/api/boards/{id}", boardId).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn().getResponse().getStatus();
    }

    private int updatePostTitle(long boardId, long postId) throws Exception {
        return mockMvc.perform(patch("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"BDC 바뀐 제목\"}")).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("같은 version으로 동시에 PATCH하면 하나만 200이고 하나는 409이며 version은 정확히 1만 오른다")
    void sameVersionPatchOnlyOneWins() throws Exception {
        long boardId = insertBoard();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> {
                await(start);
                return patchBoard(boardId, "{\"name\":\"BDC_A\",\"version\":1}");
            });
            Future<Integer> second = executor.submit(() -> {
                await(start);
                return patchBoard(boardId, "{\"name\":\"BDC_B\",\"version\":1}");
            });
            start.countDown();

            List<Integer> statuses = new ArrayList<>(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)));
            statuses.sort(Integer::compare);
            assertThat(statuses).containsExactly(200, 409);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT version FROM board WHERE id = ?", Integer.class, boardId)).isEqualTo(2);
    }

    @Test
    @DisplayName("쓰기 중지 전환이 진행 중이면 게시글 수정은 게시판 공유 잠금에서 기다렸다가 전환 결과를 보고 400이며 제목은 그대로다")
    void postUpdateWaitsForStopTransition() throws Exception {
        long boardId = insertBoard();
        long postId = insertPost(boardId);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 전환 쪽(PATCH의 게시판 FOR UPDATE와 같은 잠금): 쓰기 중지를 반영했지만 아직 커밋하지 않았다
            Future<?> transition = executor.submit(() -> readCommitted().executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM board WHERE id = ? FOR UPDATE", Long.class, boardId);
                holderConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                jdbc.update("UPDATE board SET writable_yn = 0, version = version + 1 WHERE id = ?", boardId);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();

            Future<Integer> update = executor.submit(() -> updatePostTitle(boardId, postId));
            observer().awaitWaiterFor(holderConnection.get(), Duration.ofSeconds(8));
            assertThat(update.isDone()).as("전환이 게시판 행을 쥐는 동안 수정은 끝나지 않는다").isFalse();

            release.countDown();
            transition.get(10, TimeUnit.SECONDS);
            assertThat(update.get(10, TimeUnit.SECONDS)).as("전환 커밋 뒤에는 쓰기 중지를 보고 거절").isEqualTo(400);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT title FROM post WHERE id = ?", String.class, postId)).isEqualTo("BDC 원래 제목");
    }

    @Test
    @DisplayName("진행 중인 쓰기가 게시판 공유 잠금을 쥐고 있으면 쓰기 중지 전환은 그 쓰기가 끝나기를 기다린 뒤 200이 된다")
    void stopTransitionWaitsForInFlightWriter() throws Exception {
        long boardId = insertBoard();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 쓰기 쪽(게시글 수정·첨부 업로드가 잡는 게시판 FOR SHARE와 같은 잠금)
            Future<?> writer = executor.submit(() -> readCommitted().executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM board WHERE id = ? LOCK IN SHARE MODE", Long.class, boardId);
                holderConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                held.countDown();
                await(release);
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();

            Future<Integer> transition = executor.submit(() -> patchBoard(boardId, "{\"writableYn\":false,\"version\":1}"));
            observer().awaitWaiterFor(holderConnection.get(), Duration.ofSeconds(8));
            assertThat(transition.isDone()).as("쓰기가 공유 잠금을 쥐는 동안 전환은 끝나지 않는다").isFalse();

            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThat(transition.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT writable_yn FROM board WHERE id = ?", Boolean.class, boardId)).isFalse();
    }
}
