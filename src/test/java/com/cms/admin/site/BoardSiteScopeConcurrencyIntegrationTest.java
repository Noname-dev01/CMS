package com.cms.admin.site;

import com.cms.admin.board.dto.request.BoardCreateRequest;
import com.cms.admin.board.dto.request.BoardUpdateRequest;
import com.cms.admin.board.service.BoardService;
import com.cms.admin.site.service.SiteService;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.support.CmsTestApplication;
import com.cms.support.LockWaitObserver;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 사이트 삭제 ↔ 사이트 전용 지정 경합(PLAN-site-menu.md PR ④ T4·ContentSiteScope 계약을 게시판에 적용)의 <b>계약</b>을 실제 MariaDB로 증명한다. FK {@code RESTRICT}가 있으면 공유 잠금이
 * 없어도 고아는 생기지 않으므로 "고아 없음"이 아니라 사용자가 받는 결과를 단언한다:
 * <ul>
 *   <li>지정 쪽이 공유 잠금을 먼저 쥐면 → 삭제는 그 커밋을 기다린 뒤 새 소속을 보고 <b>409</b>(지정은 성공)</li>
 *   <li>삭제가 사이트 행을 먼저 쥐면 → 지정은 삭제 커밋을 기다린 뒤 사이트가 없어 <b>400</b>(FK 예외·500 아님)</li>
 * </ul>
 * 두 경우 모두 교착이 없다. 공유 잠금을 일반 읽기로 바꾸면 첫 시나리오가 삭제 성공 + FK 예외로 어긋난다(변이 실험).
 */
@SpringBootTest(classes = CmsTestApplication.class)
class BoardSiteScopeConcurrencyIntegrationTest extends MariaDbContainerSupport {

    private static final String KEY = "bsc-";

    @Autowired ContentSiteScope contentSiteScope;
    @Autowired SiteService siteService;
    @Autowired SiteRepository siteRepository;
    @Autowired BoardService boardService;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcConnectionDetails connectionDetails;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM board WHERE name LIKE 'BSC_%'");
        jdbc.update("DELETE FROM site WHERE site_key LIKE ?", KEY + "%");
    }

    private long newSite() {
        String key = KEY + (System.nanoTime() % 1_000_000);
        jdbc.update("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, default_yn) VALUES (?, 'CSC', 'PUBLIC', 0, 1, 0)", key);
        return jdbc.queryForObject("SELECT id FROM site WHERE site_key = ?", Long.class, key);
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

    @Test
    @DisplayName("지정이 공유 잠금을 먼저 쥐면: 삭제는 대기하다가 새 전용 콘텐츠를 보고 409이며 사이트·콘텐츠가 그대로 남는다")
    void assignFirst_thenDeleteConflicts() throws Exception {
        long siteId = newSite();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 지정 쪽: 공유 잠금을 쥐고, 전용 게시판을 INSERT한 뒤 신호를 기다려 커밋한다
            Future<?> assign = executor.submit(() -> readCommitted().executeWithoutResult(status -> {
                contentSiteScope.requireAssignable(siteId);
                holderConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted, site_id) VALUES ('BSC_assigned', 1, 1, 0, ?)", siteId);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();

            Future<?> delete = executor.submit(() -> siteService.deleteSite(siteId));
            observer().awaitWaiterFor(holderConnection.get(), Duration.ofSeconds(8));
            assertThat(delete.isDone()).as("공유 잠금을 쥐는 동안 삭제는 끝나지 않는다").isFalse();

            release.countDown();
            assign.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> delete.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ConflictException.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site WHERE id = ?", Integer.class, siteId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM board WHERE site_id = ?", Integer.class, siteId)).isEqualTo(1);
    }

    @Test
    @DisplayName("삭제가 사이트 행을 먼저 쥐면: 실제 게시판 생성 서비스의 사이트 전용 지정은 대기하다가 사이트가 없어 400이다(FK 예외·교착 아님)")
    void deleteFirst_thenAssignIsRejectedAs400() throws Exception {
        long siteId = newSite();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 삭제 진행 중을 흉내 낸다: 사이트 행을 FOR UPDATE로 쥐고 있다가 지우고 커밋한다
            Future<?> deleting = executor.submit(() -> readCommitted().executeWithoutResult(status -> {
                siteRepository.findByIdForUpdate(siteId).orElseThrow();
                holderConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                held.countDown();
                await(release);
                jdbc.update("DELETE FROM site WHERE id = ?", siteId);
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();

            BoardCreateRequest request = BoardCreateRequest.builder().name("BSC_late").publicYn(true).attachmentYn(true)
                    .siteScope(String.valueOf(siteId)).build();
            Future<?> assign = executor.submit(() -> boardService.createBoard(request));
            observer().awaitWaiterFor(holderConnection.get(), Duration.ofSeconds(8));
            assertThat(assign.isDone()).as("삭제가 사이트 행을 쥐는 동안 지정은 끝나지 않는다").isFalse();

            release.countDown();
            deleting.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> assign.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(InvalidRequestException.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM board WHERE name = 'BSC_late'", Integer.class)).as("게시판이 만들어지지 않았다").isZero();
    }

    @Test
    @DisplayName("삭제가 사이트 행을 먼저 쥐면: 실제 게시판 수정 서비스의 공통→전용 변경도 대기하다가 400이고 게시판 대상이 그대로다")
    void deleteFirst_thenUpdateToDeletedSiteIsRejectedAs400() throws Exception {
        long siteId = newSite();
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('BSC_update', 1, 1, 0)");
        long boardId = jdbc.queryForObject("SELECT id FROM board WHERE name = 'BSC_update'", Long.class);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> deleting = executor.submit(() -> readCommitted().executeWithoutResult(status -> {
                siteRepository.findByIdForUpdate(siteId).orElseThrow();
                holderConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                held.countDown();
                await(release);
                jdbc.update("DELETE FROM site WHERE id = ?", siteId);
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();

            BoardUpdateRequest request = BoardUpdateRequest.builder().siteScope(String.valueOf(siteId)).build();
            Future<?> update = executor.submit(() -> boardService.updateBoard(boardId, request));
            observer().awaitWaiterFor(holderConnection.get(), Duration.ofSeconds(8));
            assertThat(update.isDone()).as("삭제가 사이트 행을 쥐는 동안 수정은 끝나지 않는다").isFalse();

            release.countDown();
            deleting.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(InvalidRequestException.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT site_id FROM board WHERE id = ?", Long.class, boardId)).as("대상이 바뀌지 않았다").isNull();
    }
}
