package com.cms.admin.site;

import com.cms.admin.menu.Menu;
import com.cms.admin.menu.MenuRepository;
import com.cms.admin.menu.dto.request.MenuCreateRequest;
import com.cms.admin.menu.dto.request.MenuUpdateRequest;
import com.cms.admin.menu.service.MenuService;
import com.cms.admin.site.dto.request.SiteCreateRequest;
import com.cms.admin.site.dto.request.SiteUpdateRequest;
import com.cms.admin.site.service.SiteService;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.support.CmsTestApplication;
import com.cms.support.LockWaitObserver;
import com.cms.support.MariaDbContainerSupport;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사이트 행 하나가 그 사이트의 쓰기 문이라는 잠금 규약(PLAN-site-menu.md D4)을 실제 MariaDB로 증명한다 — 라운드 1~3 리뷰에서 지적된 시나리오 전부:
 * 같은 사이트 부모 삭제 ↔ 자식 재활성화 교착 없음, 빈 사이트 동시 첫 생성(같은/다른 사이트), 교차 사이트 부모 양방향 동시 요청의 교착 없음,
 * 정책 강화 ↔ 외부 URL 쓰기 경합의 불변식, 사이트 문이 다른 사이트를 막지 않음, 쓰기 진입점의 READ_COMMITTED.
 *
 * <p>스레드에서 서비스를 직접 부르는 시험이라 {@code @Transactional}을 붙이지 않는다. 만든 행은 {@link #cleanUp()}이 지운다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class SiteMenuConcurrencyIntegrationTest extends MariaDbContainerSupport {

    private static final String KEY = "smc-";
    private static final int READ_COMMITTED = TransactionDefinition.ISOLATION_READ_COMMITTED;

    @Autowired MenuService menuService;
    @Autowired SiteService siteService;
    @Autowired MenuRepository menuRepository;
    @Autowired SiteRepository siteRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcConnectionDetails connectionDetails;

    private int sequence;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM menu WHERE menu_name LIKE 'SMC_%'");
        jdbc.update("DELETE FROM site WHERE site_key LIKE ?", KEY + "%");
    }

    private Site newSite(boolean allowExternal) {
        String key = KEY + (++sequence) + "-" + (System.nanoTime() % 100000);
        Long id = siteService.createSite(SiteCreateRequest.builder().siteKey(key).siteName("SMC").allowExternalLink(allowExternal).build()).getId();
        return siteRepository.findById(id).orElseThrow();
    }

    private Menu insertMenu(long siteId, String name, Long upMenuNo, boolean useYn, int ord) {
        LocalDateTime now = LocalDateTime.now();
        return menuRepository.save(Menu.builder().siteId(siteId).menuName("SMC_" + name).useYn(useYn).ord(ord)
                .upMenuNo(upMenuNo).createDate(now).updateDate(now).build());
    }

    private MenuCreateRequest root(long siteId, String name) {
        return MenuCreateRequest.builder().siteId(siteId).menuName("SMC_" + name).build();
    }

    /** 두 작업을 배리어로 동시에 출발시키고 각자의 결과(예외 포함)를 돌려준다. 교착·타임아웃은 get 시간 초과로 드러난다. */
    private List<Throwable> runTogether(Callable<?> first, Callable<?> second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Callable<?> task : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<Throwable> failures = new ArrayList<>();
            for (Future<?> future : futures) {
                try {
                    future.get(20, TimeUnit.SECONDS);
                    failures.add(null);
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
            return failures;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertNoDatabaseFailure(Throwable failure) {
        if (failure == null) {
            return;
        }
        // 업무 예외(409·400·404)는 정상 거절이다. 교착(1213)·락 대기 초과 같은 DB 예외나 그 밖의 예외는 허용하지 않는다.
        assertThat(failure).isInstanceOfAny(ConflictException.class, InvalidRequestException.class, ResourceNotFoundException.class);
    }

    // ── 같은 사이트의 부모·자식 ──

    @Test
    @DisplayName("같은 사이트에서 부모 영구삭제 ↔ 자식 재활성화를 동시에 보내도 교착 없이 끝나고 고아·활성 자식 위반이 남지 않는다")
    void parentDeleteVersusChildReactivate_noDeadlock() throws Exception {
        for (int round = 0; round < 8; round++) {
            Site site = newSite(true);
            Menu parent = insertMenu(site.getId(), "p" + round, null, false, 0);
            Menu child = insertMenu(site.getId(), "c" + round, parent.getMenuNo(), false, 0);

            List<Throwable> results = runTogether(
                    () -> menuService.deleteMenu(parent.getMenuNo()),
                    () -> menuService.updateMenu(child.getMenuNo(), MenuUpdateRequest.builder().useYn(true).build()));

            results.forEach(SiteMenuConcurrencyIntegrationTest::assertNoDatabaseFailure);
            // 삭제는 자식이 있어 거절된다(409). 재활성화는 부모가 비활성이라 거절된다(400). 둘 다 거절돼도 데이터는 그대로여야 한다.
            assertThat(menuRepository.findById(parent.getMenuNo())).as("자식이 있으면 부모는 지워지지 않는다").isPresent();
            Menu finalChild = menuRepository.findById(child.getMenuNo()).orElseThrow();
            assertThat(finalChild.getUseYn()).as("비활성 부모 아래 활성 자식 금지").isFalse();
            cleanUp();
        }
    }

    // ── 빈 사이트 ──

    @Test
    @DisplayName("빈 사이트에 동시에 첫 최상위 메뉴 둘을 만들어도 직렬화되어 둘 다 성공하고 ord가 겹치지 않는다")
    void concurrentFirstCreates_inSameEmptySite() throws Exception {
        for (int round = 0; round < 6; round++) {
            Site site = newSite(true);
            List<Throwable> results = runTogether(
                    () -> menuService.createMenu(root(site.getId(), "a")),
                    () -> menuService.createMenu(root(site.getId(), "b")));

            assertThat(results).containsOnlyNulls();
            List<Integer> ords = jdbc.queryForList("SELECT ord FROM menu WHERE site_id = ? ORDER BY ord", Integer.class, site.getId());
            assertThat(ords).containsExactly(0, 1);
            cleanUp();
        }
    }

    @Test
    @DisplayName("서로 다른 빈 사이트에 동시에 첫 메뉴를 만들어도 갭 락 순환(교착) 없이 둘 다 성공한다")
    void concurrentFirstCreates_inDifferentEmptySites() throws Exception {
        for (int round = 0; round < 10; round++) {
            Site first = newSite(true);
            Site second = newSite(true);
            List<Throwable> results = runTogether(
                    () -> menuService.createMenu(root(first.getId(), "a")),
                    () -> menuService.createMenu(root(second.getId(), "b")));

            assertThat(results).as("round " + round).containsOnlyNulls();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM menu WHERE site_id = ?", Integer.class, first.getId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM menu WHERE site_id = ?", Integer.class, second.getId())).isEqualTo(1);
            cleanUp();
        }
    }

    // ── 교차 사이트 ──

    @Test
    @DisplayName("서로 상대 사이트의 메뉴를 부모로 지정한 요청을 동시에 보내도 교착 없이 둘 다 404로 거절된다")
    void bidirectionalCrossSiteParents_neitherDeadlocks() throws Exception {
        for (int round = 0; round < 8; round++) {
            Site siteA = newSite(true);
            Site siteB = newSite(true);
            Menu aRoot = insertMenu(siteA.getId(), "a", null, true, 0);
            Menu bRoot = insertMenu(siteB.getId(), "b", null, true, 0);

            List<Throwable> results = runTogether(
                    () -> menuService.createMenu(MenuCreateRequest.builder().siteId(siteA.getId()).menuName("SMC_ax").upMenuNo(bRoot.getMenuNo()).build()),
                    () -> menuService.createMenu(MenuCreateRequest.builder().siteId(siteB.getId()).menuName("SMC_bx").upMenuNo(aRoot.getMenuNo()).build()));

            assertThat(results).allSatisfy(failure -> assertThat(failure).isInstanceOf(ResourceNotFoundException.class));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM menu WHERE menu_name IN ('SMC_ax', 'SMC_bx')", Integer.class)).isZero();
            cleanUp();
        }
    }

    // ── 정책 강화 ↔ 외부 URL ──

    @Test
    @DisplayName("외부 링크 허용 해제 ↔ 외부 URL 메뉴 생성이 경합해도 '정책 불가인데 외부 URL 메뉴가 있는' 상태는 생기지 않는다")
    void tighteningVersusExternalCreate_keepsInvariant() throws Exception {
        for (int round = 0; round < 12; round++) {
            Site site = newSite(true);

            List<Throwable> results = runTogether(
                    () -> siteService.updateSite(site.getId(), SiteUpdateRequest.builder().allowExternalLink(false).build()),
                    () -> menuService.createMenu(MenuCreateRequest.builder().siteId(site.getId()).menuName("SMC_ext")
                            .menuUrl("https://example.com/x").build()));

            results.forEach(SiteMenuConcurrencyIntegrationTest::assertNoDatabaseFailure);
            boolean external = Boolean.TRUE.equals(jdbc.queryForObject("SELECT allow_external_link FROM site WHERE id = ?", Boolean.class, site.getId()));
            int externalMenus = jdbc.queryForObject("SELECT COUNT(*) FROM menu WHERE site_id = ? AND menu_url LIKE 'http%'", Integer.class, site.getId());
            assertThat(!external && externalMenus > 0).as("round %d: 불변식 위반(허용=%s, 외부 메뉴=%d)", round, external, externalMenus).isFalse();
            cleanUp();
        }
    }

    // ── 사이트 문의 범위 ──

    @Test
    @DisplayName("사이트 행을 쥔 트랜잭션은 같은 사이트의 메뉴 쓰기만 락 대기시키고, 다른 사이트의 쓰기는 기다리지 않는다")
    void siteGate_blocksOnlyItsOwnSite() throws Exception {
        Site gated = newSite(true);
        Site other = newSite(true);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(READ_COMMITTED);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = executor.submit(() -> tx.executeWithoutResult(status -> {
                siteRepository.findByIdForUpdate(gated.getId()).orElseThrow();
                holderConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                held.countDown();
                await(release);
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();

            // 다른 사이트의 쓰기는 문에 막히지 않는다
            Future<?> otherWrite = executor.submit(() -> menuService.createMenu(root(other.getId(), "free")));
            otherWrite.get(10, TimeUnit.SECONDS);

            // 같은 사이트의 쓰기는 문에서 락 대기한다
            Future<?> gatedWrite = executor.submit(() -> menuService.createMenu(root(gated.getId(), "blocked")));
            new LockWaitObserver(connectionDetails.getJdbcUrl(), connectionDetails.getPassword())
                    .awaitWaiterFor(holderConnection.get(), Duration.ofSeconds(8));
            assertThat(gatedWrite.isDone()).as("문을 쥔 동안 같은 사이트 쓰기는 끝나지 않는다").isFalse();

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            gatedWrite.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM menu WHERE site_id = ?", Integer.class, gated.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM menu WHERE site_id = ?", Integer.class, other.getId())).isEqualTo(1);
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

    // ── 격리 수준 계약 ──

    @Test
    @DisplayName("메뉴·사이트 쓰기 진입점은 실제로 READ_COMMITTED 트랜잭션에서 실행된다 (사이트 잠금 쿼리 시점에 관측)")
    void writeEntryPoints_runAtReadCommitted() {
        Site site = newSite(true);
        Menu menu = insertMenu(site.getId(), "iso", null, false, 0);
        List<Integer> observed = new CopyOnWriteArrayList<>();
        Advised siteProxy = (Advised) siteRepository;
        MethodInterceptor probe = invocation -> {
            if (invocation.getMethod().getName().equals("findByIdForUpdate")) {
                observed.add(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            }
            return invocation.proceed();
        };
        siteProxy.addAdvice(0, probe);
        try {
            menuService.createMenu(root(site.getId(), "iso-create"));
            menuService.updateMenu(menu.getMenuNo(), MenuUpdateRequest.builder().menuName("SMC_iso2").build());
            menuService.deleteMenu(menu.getMenuNo());
            siteService.updateSite(site.getId(), SiteUpdateRequest.builder().siteName("SMC 이름").build());
            siteService.deleteSite(site.getId()); // 메뉴(iso-create)가 남아 409 — 격리 수준 관측만 한다
        } catch (ConflictException expected) {
            // 사이트 삭제는 메뉴가 남아 거절된다. 잠금 쿼리 시점의 격리 수준은 이미 기록됐다.
        } finally {
            siteProxy.removeAdvice(probe);
        }

        assertThat(observed).as("createMenu·updateMenu·deleteMenu·updateSite·deleteSite 각각 사이트 잠금").hasSize(5);
        assertThat(observed).containsOnly(READ_COMMITTED);
    }
}
