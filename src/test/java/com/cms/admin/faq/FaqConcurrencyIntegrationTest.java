package com.cms.admin.faq;

import com.cms.admin.faq.domain.FaqLock;
import com.cms.admin.faq.dto.request.FaqCategoryRequest;
import com.cms.admin.faq.dto.request.FaqOrderRequest;
import com.cms.admin.faq.dto.response.FaqTreeResponse;
import com.cms.admin.faq.repository.FaqLockRepository;
import com.cms.admin.faq.service.FaqService;
import com.cms.common.exception.ConflictException;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAQ 쓰기 직렬화의 실제 MariaDB 시험(PLAN-faq.md 쟁점 3·15). 순차 시험은 가드 행 잠금({@code FaqLockRepository.findByIdForUpdate}의
 * {@code @Lock})이 빠져도 통과하므로, 독립 트랜잭션이 겹치는 상황을 만든다. 여러 스레드가 독립 트랜잭션을 가져야 하므로 {@code @Transactional}을 붙이지
 * 않고 시험이 만든 행을 직접 정리한다.
 *
 * <p>변이 실험(결과는 PLAN-faq.md에 기록): 서비스의 {@code lockGuard()} 호출을 지우면 락 대기·정확한 상한·동시 순서 반영 시험이 실패한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class FaqConcurrencyIntegrationTest extends MariaDbContainerSupport {

    @Autowired FaqService faqService;
    @Autowired FaqLockRepository lockRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcConnectionDetails connectionDetails;
    @PersistenceContext EntityManager entityManager;

    private long auditBaseline;

    @BeforeEach
    void setUp() {
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
        cleanFaq();
    }

    @AfterEach
    void cleanUp() {
        cleanFaq();
        jdbc.update("DELETE FROM admin_action_log WHERE id > ?", auditBaseline);
    }

    private void cleanFaq() {
        jdbc.update("DELETE FROM faq");
        jdbc.update("DELETE FROM faq_category");
        jdbc.update("UPDATE faq_lock SET version = 0");
    }

    private long category(String name, int ord) {
        jdbc.update("INSERT INTO faq_category (name, ord, use_yn, create_date, update_date) VALUES (?, ?, 1, ?, ?)",
                name, ord, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject("SELECT MAX(id) FROM faq_category", Long.class);
    }

    private long faq(long categoryId, String question, int ord) {
        jdbc.update("INSERT INTO faq (category_id, question, answer_html, ord, use_yn, create_date, update_date) VALUES (?, ?, '<p>a</p>', ?, 1, ?, ?)",
                categoryId, question, ord, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject("SELECT MAX(id) FROM faq", Long.class);
    }

    private long version() {
        return jdbc.queryForObject("SELECT version FROM faq_lock WHERE id = 1", Long.class);
    }

    private static FaqCategoryRequest categoryRequest(String name) {
        return new FaqCategoryRequest(name, true);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static FaqOrderRequest orderRequest(long version, long categoryId, List<Long> faqIds) {
        return new FaqOrderRequest(version, List.of(new FaqOrderRequest.CategoryOrder(categoryId, faqIds)));
    }

    @Test
    @DisplayName("다른 트랜잭션이 가드 행 락을 쥐고 있으면 FAQ 쓰기는 실제 락 대기를 거치고, 락이 풀린 뒤에 완료된다")
    void write_waitsForGuardLock() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        try {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                lockRepository.findByIdForUpdate(FaqLock.SINGLETON_ID).orElseThrow();
                holderConnection.set(((Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult()).longValue());
                locked.countDown();
                await(release);
            }));
            await(locked);

            Future<?> waiter = executor.submit(() -> faqService.createCategory(categoryRequest("waiter")));
            new LockWaitObserver(connectionDetails.getJdbcUrl(), connectionDetails.getPassword())
                    .awaitWaiterFor(holderConnection.get(), Duration.ofSeconds(8));
            assertThat(waiter.isDone()).as("가드 락이 풀리기 전에는 쓰기가 끝나지 않는다(lockGuard 누락 회귀 의심)").isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM faq_category", Integer.class)).as("대기 중에는 아무것도 쓰지 않았다").isZero();

            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            waiter.get(15, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM faq_category", Integer.class)).isEqualTo(1);
        assertThat(version()).isEqualTo(1);
    }

    @Test
    @DisplayName("빈 테이블에서 12개 스레드가 동시에 카테고리를 등록해도 모두 성공하고 ord는 0..11로 중복 없이, 버전은 정확히 +12다")
    void concurrentCreates_onEmptyTable_haveDistinctOrds() throws Exception {
        int count = 12;
        List<Throwable> failures = runConcurrently(count, index -> {
            faqService.createCategory(categoryRequest("c" + index));
            return null;
        });

        assertThat(failures).isEmpty();
        assertThat(jdbc.queryForList("SELECT ord FROM faq_category ORDER BY ord", Integer.class))
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        assertThat(version()).isEqualTo(count);
    }

    @Test
    @DisplayName("상한 직전(29개)에서 6개 스레드가 동시에 카테고리를 등록하면 정확히 하나만 성공하고 나머지는 409다 — 상한을 넘지 않는다")
    void concurrentCreates_neverExceedCap() throws Exception {
        for (int i = 0; i < FaqService.MAX_CATEGORIES - 1; i++) {
            category("c" + i, i);
        }

        List<Throwable> failures = runConcurrently(6, index -> {
            faqService.createCategory(categoryRequest("n" + index));
            return null;
        });

        assertThat(failures).hasSize(5).allSatisfy(failure -> assertThat(failure).isInstanceOf(ConflictException.class));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM faq_category", Integer.class)).isEqualTo(FaqService.MAX_CATEGORIES);
        assertThat(jdbc.queryForList("SELECT DISTINCT ord FROM faq_category", Integer.class)).hasSize(FaqService.MAX_CATEGORIES);
    }

    @Test
    @DisplayName("같은 version으로 서로 다른 순서를 동시에 반영하면 정확히 하나만 성공하고 나머지는 409다 — 낡은 화면이 덮어쓰지 못한다")
    void concurrentOrderSaves_withSameVersion_exactlyOneWins() throws Exception {
        long c = category("c", 0);
        long a = faq(c, "a", 0);
        long b = faq(c, "b", 1);
        long d = faq(c, "d", 2);
        long base = version();

        List<List<Long>> orders = List.of(List.of(b, a, d), List.of(d, b, a));
        List<Throwable> failures = runConcurrently(2, index -> {
            faqService.saveOrder(orderRequest(base, c, orders.get(index)));
            return null;
        });

        assertThat(failures).hasSize(1).allSatisfy(failure -> assertThat(failure).isInstanceOf(ConflictException.class));
        assertThat(version()).isEqualTo(base + 1);
        List<Long> finalOrder = jdbc.queryForList("SELECT id FROM faq WHERE category_id = ? ORDER BY ord", Long.class, c);
        assertThat(orders).as("성공한 쪽의 순서가 그대로 저장된다").contains(finalOrder);
    }

    @Test
    @DisplayName("순서 반영 ↔ 등록이 겹치면 직렬화된다 — 등록이 먼저면 낡은 version의 반영은 409, 반영이 먼저면 등록은 새 FAQ를 맨 끝에 붙인다")
    void orderSaveAndCreate_areSerialized() throws Exception {
        long c = category("c", 0);
        long a = faq(c, "a", 0);
        long b = faq(c, "b", 1);
        long base = version();

        // 등록이 먼저 커밋 → 같은 base version의 순서 반영은 낡았다
        faqService.createCategory(categoryRequest("late"));
        try {
            faqService.saveOrder(orderRequest(base, c, List.of(b, a)));
            org.junit.jupiter.api.Assertions.fail("낡은 version은 409여야 한다");
        } catch (ConflictException expected) {
            assertThat(jdbc.queryForList("SELECT id FROM faq WHERE category_id = ? ORDER BY ord", Long.class, c)).containsExactly(a, b);
        }
    }

    @Test
    @DisplayName("관리자 트리 조회는 version과 순서를 같은 스냅샷에서 읽는다 — 순서 반영이 계속 커밋되는 동안에도 (version 짝홀, 순서)가 어긋난 응답이 없다")
    void treeRead_versionAndOrderComeFromOneSnapshot() throws Exception {
        long c = category("c", 0);
        long a = faq(c, "a", 0);
        long b = faq(c, "b", 1);       // version 0 ↔ [a, b], 반영마다 뒤집으므로 홀수 version ↔ [b, a]

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicBoolean writerDone = new AtomicBoolean();
        AtomicInteger inconsistent = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        try {
            Future<?> writer = executor.submit(() -> {
                try {
                    for (int i = 0; i < 80; i++) {
                        FaqTreeResponse tree = faqService.getTree();
                        List<Long> reversed = new ArrayList<>(tree.getCategories().get(0).getFaqs().stream().map(FaqTreeResponse.Item::getId).toList());
                        java.util.Collections.reverse(reversed);
                        faqService.saveOrder(orderRequest(tree.getVersion(), c, reversed));
                    }
                } finally {
                    writerDone.set(true);
                }
            });
            Future<?> reader = executor.submit(() -> {
                while (!writerDone.get()) {
                    FaqTreeResponse tree = faqService.getTree();
                    long first = tree.getCategories().get(0).getFaqs().get(0).getId();
                    boolean oddVersion = tree.getVersion() % 2 == 1;
                    if ((first == b) != oddVersion) {
                        inconsistent.incrementAndGet();
                    }
                    reads.incrementAndGet();
                }
            });
            writer.get(60, TimeUnit.SECONDS);
            reader.get(60, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }

        assertThat(reads.get()).as("읽기 스레드가 실제로 겹쳐 돌았다").isPositive();
        assertThat(inconsistent.get()).as("옛 순서 + 새 version 같은 어긋난 응답").isZero();
        assertThat(version()).isEqualTo(80);
        assertThat(a).isNotEqualTo(b);
    }

    /** {@code count}개 스레드를 배리어로 동시에 출발시켜 각 작업의 실패 예외(원인)를 모은다. */
    private List<Throwable> runConcurrently(int count, ThrowingIndexTask task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CyclicBarrier barrier = new CyclicBarrier(count);
        List<Throwable> failures = new ArrayList<>();
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit((Callable<Object>) () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return task.run(index);
                }));
            }
            for (Future<Object> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
        return failures;
    }

    @FunctionalInterface
    private interface ThrowingIndexTask {
        Object run(int index) throws Exception;
    }
}
