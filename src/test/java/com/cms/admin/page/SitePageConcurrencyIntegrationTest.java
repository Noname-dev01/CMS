package com.cms.admin.page;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 정적 페이지 동시성 시험(PLAN-static-page.md 쟁점 10) — 실제 MariaDB에서 동시에 같은 낡은 version으로 저장하면 정확히 하나만 성공하고 개정 번호가
 * 중복·건너뜀 없이 이어지는지, 같은 슬러그 동시 등록의 패자가 409인지, 수정과 삭제가 겹치면 직렬화되는지 본다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class SitePageConcurrencyIntegrationTest extends MariaDbContainerSupport {

    private static final int THREADS = 8;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired ObjectMapper objectMapper;

    private Member admin;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "spc-admin", Role.ROLE_ADMIN);
        cleanPages();
    }

    @AfterEach
    void cleanUp() {
        cleanPages();
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE action_type LIKE 'SITE_PAGE_%'");
    }

    private void cleanPages() {
        jdbc.update("DELETE FROM site_page_revision");
        jdbc.update("DELETE FROM site_page");
    }

    private RequestPostProcessor asAdmin() {
        return TestMembers.asMember(admin);
    }

    private long fixturePage(String slug) {
        jdbc.update("INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn, create_date, update_date) VALUES (?, 1, 1, 1, 0, NOW(), NOW())", slug);
        long id = jdbc.queryForObject("SELECT id FROM site_page WHERE slug = ?", Long.class, slug);
        jdbc.update("INSERT INTO site_page_revision (page_id, revision_no, title, content, create_date) VALUES (?, 1, '원본', '<p>원본</p>', NOW())", id);
        return id;
    }

    /** 모든 호출을 같은 순간에 출발시켜 상태 코드를 모은다. */
    private List<Integer> runConcurrently(List<Callable<Integer>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Integer> update(long id, String title, int version) {
        return () -> mockMvc.perform(put("/admin/api/site-pages/{id}", id).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                        "title", title, "content", "<p>" + title + "</p>", "contentFormat", "HTML",
                        "useYn", true, "footerYn", false, "siteScope", "COMMON", "version", version))))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("같은 version으로 서로 다른 본문을 동시에 저장하면 정확히 하나만 200이고 나머지는 409 — 개정은 1건만 늘고 번호가 중복되지 않는다")
    void concurrentSameVersionBodyUpdates() throws Exception {
        long id = fixturePage("spc-body");
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(update(id, "동시 제목 " + i, 1));
        }

        List<Integer> statuses = runConcurrently(calls);

        assertThat(statuses.stream().filter(s -> s == 200).count()).as(statuses.toString()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).as(statuses.toString()).isEqualTo(THREADS - 1);
        assertThat(jdbc.queryForList("SELECT revision_no FROM site_page_revision WHERE page_id = ? ORDER BY revision_no", Integer.class, id))
                .containsExactly(1, 2);
        assertThat(jdbc.queryForObject("SELECT version FROM site_page WHERE id = ?", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT current_revision_no FROM site_page WHERE id = ?", Integer.class, id)).isEqualTo(2);
    }

    @Test
    @DisplayName("메타만 바꾸는 저장과 본문 저장이 같은 version으로 겹치면 하나만 성공한다 — 어느 쪽이 이겨도 상태가 일관된다")
    void concurrentMetaAndBodyUpdates() throws Exception {
        long id = fixturePage("spc-meta");
        List<Callable<Integer>> calls = new ArrayList<>();
        calls.add(() -> mockMvc.perform(put("/admin/api/site-pages/{id}", id).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"원본\",\"content\":\"<p>원본</p>\",\"contentFormat\":\"HTML\",\"useYn\":false,\"footerYn\":false,\"siteScope\":\"COMMON\",\"version\":1}"))
                .andReturn().getResponse().getStatus());
        calls.add(update(id, "본문 변경", 1));

        List<Integer> statuses = runConcurrently(calls);

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("SELECT version FROM site_page WHERE id = ?", Integer.class, id)).isEqualTo(2);
        boolean hidden = !jdbc.queryForObject("SELECT use_yn FROM site_page WHERE id = ?", Boolean.class, id);
        int revisions = jdbc.queryForObject("SELECT COUNT(*) FROM site_page_revision WHERE page_id = ?", Integer.class, id);
        assertThat(hidden ? revisions == 1 : revisions == 2).as("메타가 이기면 개정 1건·비노출, 본문이 이기면 개정 2건·노출").isTrue();
    }

    @Test
    @DisplayName("같은 슬러그를 동시에 등록하면 정확히 하나만 201이고 나머지는 409 — 페이지는 1건")
    void concurrentCreateSameSlug() throws Exception {
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            int n = i;
            calls.add(() -> mockMvc.perform(post("/admin/api/site-pages").with(asAdmin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"slug\":\"spc-create\",\"title\":\"제목 " + n + "\",\"content\":\"<p>본문 " + n + "</p>\",\"contentFormat\":\"HTML\"}"))
                    .andReturn().getResponse().getStatus());
        }

        List<Integer> statuses = runConcurrently(calls);

        assertThat(statuses.stream().filter(s -> s == 201).count()).as(statuses.toString()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).as(statuses.toString()).isEqualTo(THREADS - 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site_page WHERE slug = 'spc-create'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site_page_revision", Integer.class)).as("패자는 개정을 남기지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("같은 version의 수정과 삭제가 겹치면 직렬화된다 — (수정 200, 삭제 409) 또는 (삭제 204, 수정 404)이고 고아 개정이 남지 않는다")
    void concurrentUpdateAndDelete() throws Exception {
        long id = fixturePage("spc-del");
        List<Callable<Integer>> calls = new ArrayList<>();
        calls.add(update(id, "수정", 1));
        calls.add(() -> mockMvc.perform(delete("/admin/api/site-pages/{id}", id).param("version", "1").with(asAdmin()).with(csrf()))
                .andReturn().getResponse().getStatus());

        List<Integer> statuses = runConcurrently(calls);

        boolean updateFirst = statuses.get(0) == 200 && statuses.get(1) == 409;
        boolean deleteFirst = statuses.get(0) == 404 && statuses.get(1) == 204;
        assertThat(updateFirst || deleteFirst).as(statuses.toString()).isTrue();
        if (updateFirst) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site_page WHERE id = ?", Integer.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site_page_revision WHERE page_id = ?", Integer.class, id)).isEqualTo(2);
        } else {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site_page WHERE id = ?", Integer.class, id)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site_page_revision WHERE page_id = ?", Integer.class, id)).isZero();
        }
    }
}
