package com.cms.admin.banner.service;

import com.cms.admin.banner.domain.BannerLock;
import com.cms.admin.banner.dto.request.BannerCreateRequest;
import com.cms.admin.banner.dto.request.BannerOrderRequest;
import com.cms.admin.banner.dto.request.BannerUpdateRequest;
import com.cms.admin.banner.repository.BannerLockRepository;
import com.cms.admin.banner.repository.BannerRepository;
import com.cms.common.exception.ConflictException;
import com.cms.common.image.ImageFileValidatorTest;
import com.cms.common.storage.FileStorage;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 배너 생성·삭제·순서 저장을 직렬화하는 가드 행({@code banner_lock})의 실제 MariaDB 시험(PLAN-public-home-banner.md 쟁점 5, R1-1).
 * 핵심은 <b>배너 행이 하나도 없는 빈 테이블</b>에서도 직렬화된다는 점이다(전체 행 잠금은 빈 집합에서 잠글 행이 없다).
 * 여러 스레드가 독립 트랜잭션을 가져야 하므로 {@code @Transactional}을 붙이지 않고, 시험이 만든 배너와 파일을 직접 정리한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class BannerConcurrencyIntegrationTest extends MariaDbContainerSupport {

    @Autowired BannerService bannerService;
    @Autowired BannerRepository bannerRepository;
    @Autowired BannerLockRepository bannerLockRepository;
    @Autowired FileStorage fileStorage;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @PersistenceContext EntityManager entityManager;

    @BeforeEach
    @AfterEach
    void cleanBanners() {
        for (String key : jdbc.queryForList("SELECT storage_key FROM banner", String.class)) {
            fileStorage.delete(key, BannerService.STORAGE_NAMESPACE);
        }
        jdbc.update("DELETE FROM banner");
    }

    private BannerCreateRequest request(String title) throws Exception {
        BannerCreateRequest request = new BannerCreateRequest();
        request.setTitle(title);
        request.setImage(new MockMultipartFile("image", "b.png", "image/png", ImageFileValidatorTest.png(20, 10)));
        return request;
    }

    /** 모든 작업을 같은 순간에 출발시켜 결과(성공 수, 충돌 수, 그 밖의 예외)를 모은다. */
    private List<Object> runTogether(int count, Callable<Object> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    try {
                        return task.call();
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            go.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("가드 행 잠금 실증: 배너 행이 하나도 없어도 가드를 보유한 동안 다른 트랜잭션의 가드 재조회는 락 대기 타임아웃으로 실패한다")
    void guardRow_lockedEvenWhenBannerTableEmpty() throws Exception {
        assertThat(bannerRepository.count()).isZero();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = executor.submit(() -> tx.executeWithoutResult(status -> {
                bannerLockRepository.findByIdForUpdate(BannerLock.SINGLETON_ID);
                lockHeld.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(lockHeld.await(10, TimeUnit.SECONDS), "트랜잭션 1이 가드 행 락을 획득해야 한다");

            Throwable thrown = null;
            try {
                tx.executeWithoutResult(status -> {
                    // SESSION 변수는 롤백으로 되돌아가지 않고 풀 커넥션이 재사용되므로 원래 값을 저장했다 복원한다.
                    Object original = entityManager.createNativeQuery("SELECT @@innodb_lock_wait_timeout").getSingleResult();
                    try {
                        entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = 1").executeUpdate();
                        bannerLockRepository.findByIdForUpdate(BannerLock.SINGLETON_ID);
                    } finally {
                        entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = " + original).executeUpdate();
                    }
                });
            } catch (Throwable t) {
                thrown = t;
            } finally {
                release.countDown();
            }
            assertNotNull(thrown, "가드 행 재조회는 락 대기 타임아웃으로 실패해야 한다(@Lock 선언 누락 회귀 의심)");
            holder.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("빈 테이블에서 12개 동시 등록: 정확히 상한(10)까지만 성공하고 나머지는 409, ord는 0..9 중복 없음, 실패한 요청의 파일은 남지 않는다")
    void concurrentCreateOnEmptyTable_respectsLimitAndDistinctOrd() throws Exception {
        assertThat(bannerRepository.count()).isZero();

        List<Object> results = runTogether(12, () -> bannerService.createBanner(request("동시-" + System.nanoTime())));

        long success = results.stream().filter(r -> !(r instanceof Throwable)).count();
        long conflicts = results.stream().filter(r -> r instanceof ConflictException).count();
        assertThat(results.stream().filter(r -> r instanceof Throwable && !(r instanceof ConflictException)).toList())
                .as("충돌 외 예외(교착 등)가 없어야 한다").isEmpty();
        assertThat(success).isEqualTo(BannerService.MAX_BANNERS);
        assertThat(conflicts).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT ord FROM banner ORDER BY ord", Integer.class))
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        for (String key : jdbc.queryForList("SELECT storage_key FROM banner", String.class)) {
            assertThat(fileStorage.load(key, BannerService.STORAGE_NAMESPACE)).isNotEmpty();
        }
    }

    @Test
    @DisplayName("상한 직전(9개)에서 2개 동시 등록: 정확히 하나만 성공한다")
    void concurrentCreateAtLimitMinusOne_exactlyOneWins() throws Exception {
        for (int i = 0; i < BannerService.MAX_BANNERS - 1; i++) {
            bannerService.createBanner(request("사전-" + i));
        }

        List<Object> results = runTogether(2, () -> bannerService.createBanner(request("경합-" + System.nanoTime())));

        assertThat(results.stream().filter(r -> !(r instanceof Throwable)).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r instanceof ConflictException).count()).isEqualTo(1);
        assertThat(bannerRepository.count()).isEqualTo(BannerService.MAX_BANNERS);
    }

    @Test
    @DisplayName("마지막 배너 삭제와 동시 등록: 둘 다 성공하고 ord가 중복되지 않으며 최종 1개가 남는다")
    void deleteLastAndCreateConcurrently_noDuplicateOrd() throws Exception {
        Long onlyId = bannerService.createBanner(request("마지막")).getId();

        List<Object> results = new ArrayList<>(runTogether(2, new Callable<>() {
            private final java.util.concurrent.atomic.AtomicInteger turn = new java.util.concurrent.atomic.AtomicInteger();

            @Override
            public Object call() throws Exception {
                return turn.getAndIncrement() == 0
                        ? bannerService.deleteBanner(onlyId)
                        : bannerService.createBanner(request("새로"));
            }
        }));

        assertThat(results.stream().filter(r -> r instanceof Throwable).toList()).isEmpty();
        assertThat(bannerRepository.count()).isEqualTo(1);
        assertThat(bannerRepository.findById(onlyId)).isEmpty();
    }

    @Test
    @DisplayName("순서 저장과 동시 등록: 직렬화되어 순서 저장은 등록 전 집합(성공) 또는 등록 후 집합과 불일치(409) 중 하나로 끝나고 ord는 중복되지 않는다")
    void saveOrderAndCreateConcurrently_serialized() throws Exception {
        Long a = bannerService.createBanner(request("A")).getId();
        Long b = bannerService.createBanner(request("B")).getId();

        List<Object> results = runTogether(2, new Callable<>() {
            private final java.util.concurrent.atomic.AtomicInteger turn = new java.util.concurrent.atomic.AtomicInteger();

            @Override
            public Object call() throws Exception {
                return turn.getAndIncrement() == 0
                        ? bannerService.saveOrder(new BannerOrderRequest(List.of(b, a)))
                        : bannerService.createBanner(request("C"));
            }
        });

        assertThat(results.stream().filter(r -> r instanceof Throwable && !(r instanceof ConflictException)).toList()).isEmpty();
        List<Integer> ords = jdbc.queryForList("SELECT ord FROM banner ORDER BY ord", Integer.class);
        assertThat(ords).doesNotHaveDuplicates();
        assertThat(bannerRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("순서 저장과 동시 단건 수정: 수정이 커밋되기 전에 시작된 순서 저장이 수정 결과(숨김)를 이전 값으로 되돌리지 않는다")
    void saveOrderAndUpdateConcurrently_doesNotRevertMetadata() throws Exception {
        Long a = bannerService.createBanner(request("A")).getId();
        Long b = bannerService.createBanner(request("B")).getId();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> updater = executor.submit(() -> tx.executeWithoutResult(status -> {
                bannerService.updateBanner(a, BannerUpdateRequest.builder().title("A").useYn(false).build());
                updated.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(updated.await(10, TimeUnit.SECONDS));

            Future<?> orderer = executor.submit(() -> bannerService.saveOrder(new BannerOrderRequest(List.of(b, a))));
            Thread.sleep(500);   // 행 잠금이 없다면 순서 저장이 커밋 전 스냅샷을 읽고 진행할 시간을 준다
            release.countDown();
            updater.get(15, TimeUnit.SECONDS);
            orderer.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }

        assertThat(jdbc.queryForObject("SELECT use_yn FROM banner WHERE id = ?", Boolean.class, a)).isFalse();
        assertThat(jdbc.queryForList("SELECT id FROM banner ORDER BY ord", Long.class)).containsExactly(b, a);
    }
}
