package com.cms.admin.popup;

import com.cms.admin.popup.domain.Popup;
import com.cms.admin.popup.dto.request.PopupUpdateRequest;
import com.cms.admin.popup.repository.PopupRepository;
import com.cms.admin.popup.service.PopupService;
import com.cms.common.image.ImageFileValidatorTest;
import com.cms.common.storage.FileStorage;
import com.cms.support.CmsTestApplication;
import com.cms.support.LockWaitObserver;
import com.cms.support.MariaDbContainerSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 팝업 수정이 대상 행을 잠근 채 {@code revision}을 올린다는 계약의 실제 MariaDB 시험(PLAN-popup.md R4-2). 순차 시험은
 * {@code PopupRepository.findByIdForUpdate}의 {@code @Lock(PESSIMISTIC_WRITE)} 선언이 빠져도 통과하므로, 독립 트랜잭션 둘이 겹치는 상황을 만든다.
 * 잠금이 없으면 두 트랜잭션이 같은 {@code revision}을 읽고 각각 +1을 저장해 증가분이 유실되고, 방문자가 먼저 끝난 수정 뒤에 저장한
 * "오늘 하루 보지 않기"가 두 번째 수정에서도 유지된다.
 *
 * <p>여러 스레드가 독립 트랜잭션을 가져야 하므로 {@code @Transactional}을 붙이지 않고, 시험이 만든 팝업과 파일을 직접 정리한다.
 * 변이 실험(결과는 PLAN-popup.md에 기록): {@code @Lock}을 지우면 두 시험이 모두 실패한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class PopupConcurrencyIntegrationTest extends MariaDbContainerSupport {

    @Autowired PopupService popupService;
    @Autowired PopupRepository popupRepository;
    @Autowired FileStorage fileStorage;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcConnectionDetails connectionDetails;
    @PersistenceContext EntityManager entityManager;

    @BeforeEach
    @AfterEach
    void cleanPopups() {
        for (String key : jdbc.queryForList("SELECT storage_key FROM popup WHERE storage_key IS NOT NULL", String.class)) {
            fileStorage.delete(key, PopupService.STORAGE_NAMESPACE);
        }
        jdbc.update("DELETE FROM popup");
    }

    private long fixturePopup() throws Exception {
        String key = fileStorage.store(ImageFileValidatorTest.png(20, 10), "p.png", PopupService.STORAGE_NAMESPACE);
        jdbc.update("INSERT INTO popup (title, revision, popup_type, storage_key, content_type, file_size, pos_x, pos_y, width, use_yn, create_date, update_date) "
                + "VALUES ('conc', 0, 'IMAGE', ?, 'image/png', 10, 10, 10, 300, 1, ?, ?)", key, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject("SELECT MAX(id) FROM popup", Long.class);
    }

    private int revisionOf(long id) {
        return jdbc.queryForObject("SELECT revision FROM popup WHERE id = ?", Integer.class, id);
    }

    private static PopupUpdateRequest request(String title) {
        PopupUpdateRequest request = new PopupUpdateRequest();
        request.setTitle(title);
        request.setPosX(10);
        request.setPosY(10);
        request.setWidth(300);
        request.setUseYn(true);
        return request;
    }

    @Test
    @DisplayName("먼저 시작한 수정이 행 락을 쥐고 revision을 올린 채 커밋을 미루면, 두 번째 수정은 실제 락 대기를 거친 뒤 그 값을 읽어 revision이 n+2가 된다")
    void secondUpdateWaitsForRowLock_thenIncrementsFromCommittedValue() throws Exception {
        long id = fixturePopup();
        int before = revisionOf(id);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        try {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Popup target = popupRepository.findByIdForUpdate(id).orElseThrow();
                holderConnection.set(((Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult()).longValue());
                target.replace("holder", target.getLinkUrl(), null, 10, 10, 300, null, null, null, true, LocalDateTime.now());   // 첫 수정: revision +1, 커밋은 미룬다
                locked.countDown();
                await(release);
            }));
            await(locked);

            Future<?> waiter = executor.submit(() -> popupService.updatePopup(id, request("waiter")));
            new LockWaitObserver(connectionDetails.getJdbcUrl(), connectionDetails.getPassword())
                    .awaitWaiterFor(holderConnection.get(), Duration.ofSeconds(8));
            assertThat(waiter.isDone()).as("행 락이 풀리기 전에는 두 번째 수정이 끝나지 않는다(@Lock 선언 누락 회귀 의심)").isFalse();

            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            waiter.get(15, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }

        assertThat(revisionOf(id)).as("두 수정이 모두 반영되어 증가분이 유실되지 않는다").isEqualTo(before + 2);
        assertThat(jdbc.queryForObject("SELECT title FROM popup WHERE id = ?", String.class, id)).as("나중에 락을 얻은 수정이 마지막 값").isEqualTo("waiter");
    }

    @Test
    @DisplayName("같은 팝업을 8개 스레드가 동시에 수정해도 모두 성공하고 revision은 정확히 +8이다(증가분 유실 없음)")
    void concurrentUpdates_neverLoseIncrements() throws Exception {
        long id = fixturePopup();
        int before = revisionOf(id);
        int count = 8;
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CyclicBarrier barrier = new CyclicBarrier(count);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String title = "t" + i;
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return popupService.updatePopup(id, request(title));
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }

        assertThat(revisionOf(id)).isEqualTo(before + count);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "시간 초과");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
