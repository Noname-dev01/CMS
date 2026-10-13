package com.cms.admin.site;

import com.cms.admin.menu.dto.request.MenuCreateRequest;
import com.cms.admin.menu.service.MenuService;
import com.cms.admin.site.dto.request.SiteCreateRequest;
import com.cms.admin.site.dto.request.SiteUpdateRequest;
import com.cms.admin.site.service.SiteService;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기본 공개 사이트 변경의 잠금 계약(PLAN-site-menu.md PR ② P2-5, 리뷰 라운드 4)을 실제 MariaDB로 증명한다: 모든 site 행을 id 오름차순으로 잠가
 * 기본 변경끼리·비활성화·메뉴 쓰기와 교착 없이 직렬화되고, 어떤 순서로 끝나도 <b>기본 표시는 정확히 1개이고 그 사이트는 활성 공개 사이트</b>다.
 * 스레드에서 서비스를 직접 부르므로 {@code @Transactional}을 붙이지 않는다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class SiteDefaultConcurrencyIntegrationTest extends MariaDbContainerSupport {

    private static final String KEY = "sdc-";
    private static final int ROUNDS = 10;

    @Autowired SiteService siteService;
    @Autowired MenuService menuService;
    @Autowired SiteRepository siteRepository;
    @Autowired JdbcTemplate jdbc;

    private int sequence;

    @BeforeEach
    void setUp() {
        resetDefault();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM menu WHERE menu_name LIKE 'SDC_%'");
        jdbc.update("DELETE FROM site WHERE site_key LIKE ?", KEY + "%");
        resetDefault();
    }

    private void resetDefault() {
        jdbc.update("UPDATE site SET default_yn = CASE WHEN id = 2 THEN 1 ELSE 0 END, use_yn = 1 WHERE id <= 2");
    }

    private Long newSite() {
        String key = KEY + (++sequence) + "-" + (System.nanoTime() % 100000);
        return siteService.createSite(SiteCreateRequest.builder().siteKey(key).siteName("SDC").build()).getId();
    }

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

    private static void assertBusinessOutcomeOnly(Throwable failure) {
        if (failure == null) {
            return;
        }
        // 업무 예외는 정상 거절이다. 교착(1213)·락 대기 초과 같은 DB 예외나 그 밖의 예외는 허용하지 않는다.
        assertThat(failure).isInstanceOfAny(ConflictException.class, InvalidRequestException.class, ResourceNotFoundException.class);
    }

    private void assertSingleActiveDefault() {
        Long defaults = jdbc.queryForObject("SELECT COUNT(*) FROM site WHERE default_yn = 1", Long.class);
        Long healthy = jdbc.queryForObject("SELECT COUNT(*) FROM site WHERE default_yn = 1 AND use_yn = 1 AND site_type = 'PUBLIC'", Long.class);
        assertThat(defaults).as("기본 표시는 정확히 1개").isEqualTo(1L);
        assertThat(healthy).as("그 사이트는 활성 공개 사이트").isEqualTo(1L);
    }

    private SiteUpdateRequest makeDefault() {
        return SiteUpdateRequest.builder().defaultSite(true).build();
    }

    @Test
    @DisplayName("서로 다른 두 사이트를 동시에 기본으로 지정해도 교착 없이 직렬화되고 기본은 정확히 하나다")
    void twoConcurrentMakeDefault_serialized() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Long a = newSite();
            Long b = newSite();

            List<Throwable> results = runTogether(() -> siteService.updateSite(a, makeDefault()), () -> siteService.updateSite(b, makeDefault()));

            results.forEach(SiteDefaultConcurrencyIntegrationTest::assertBusinessOutcomeOnly);
            assertThat(results).as("둘 다 성공해야 한다(업무 거절 사유가 없다)").containsOnlyNulls();
            assertSingleActiveDefault();
            Long winner = jdbc.queryForObject("SELECT id FROM site WHERE default_yn = 1", Long.class);
            assertThat(winner).isIn(a, b);
        }
    }

    @Test
    @DisplayName("기본 지정 ↔ 같은 사이트 비활성화를 동시에 보내도 '기본이면서 비활성'인 상태는 만들어지지 않는다")
    void makeDefaultVersusDisableSameSite_neverDefaultAndInactive() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Long site = newSite();

            List<Throwable> results = runTogether(
                    () -> siteService.updateSite(site, makeDefault()),
                    () -> siteService.updateSite(site, SiteUpdateRequest.builder().useYn(false).build()));

            results.forEach(SiteDefaultConcurrencyIntegrationTest::assertBusinessOutcomeOnly);
            assertSingleActiveDefault();
        }
    }

    @Test
    @DisplayName("현재 기본 사이트의 비활성화 ↔ 다른 사이트로의 기본 이동을 동시에 보내도 기본은 항상 활성 사이트 하나다")
    void disableCurrentDefaultVersusMoveDefault() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Long current = newSite();
            Long target = newSite();
            siteService.updateSite(current, makeDefault());

            List<Throwable> results = runTogether(
                    () -> siteService.updateSite(target, makeDefault()),
                    () -> siteService.updateSite(current, SiteUpdateRequest.builder().useYn(false).build()));

            results.forEach(SiteDefaultConcurrencyIntegrationTest::assertBusinessOutcomeOnly);
            assertSingleActiveDefault();
            // 이동이 먼저면 current 비활성화가 성공하고, 비활성화가 먼저면(기본이라 409) current가 활성으로 남는다 — 어느 쪽이든 target은 기본이 된다.
            assertThat(jdbc.queryForObject("SELECT default_yn FROM site WHERE id = ?", Boolean.class, target)).isTrue();
        }
    }

    @Test
    @DisplayName("기본 사이트 이동 ↔ 다른 사이트의 메뉴 쓰기는 교착 없이 둘 다 성공한다 (모든 site 행 잠금과 메뉴 쓰기의 사이트 문이 순환하지 않는다)")
    void makeDefaultVersusMenuWrites_noDeadlock() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Long a = newSite();
            Long b = newSite();
            String menuName = "SDC_" + round;

            List<Throwable> results = runTogether(
                    () -> siteService.updateSite(a, makeDefault()),
                    () -> menuService.createMenu(MenuCreateRequest.builder().siteId(b).menuName(menuName).build()));

            results.forEach(SiteDefaultConcurrencyIntegrationTest::assertBusinessOutcomeOnly);
            assertThat(results).containsOnlyNulls();
            assertSingleActiveDefault();
        }
    }

    @Test
    @DisplayName("기본 사이트를 바꾸면 이전 기본은 해제된다 — 직렬 실행에서도 정확히 하나만 남는다")
    void sequentialMove() {
        Long a = newSite();
        siteService.updateSite(a, makeDefault());
        assertSingleActiveDefault();
        assertThat(siteRepository.findById(2L).orElseThrow().isDefaultYn()).isFalse();

        siteService.updateSite(2L, makeDefault());
        assertSingleActiveDefault();
        assertThat(siteRepository.findById(a).orElseThrow().isDefaultYn()).isFalse();
    }
}
