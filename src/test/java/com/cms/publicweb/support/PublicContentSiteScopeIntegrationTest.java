package com.cms.publicweb.support;

import com.cms.common.storage.FileStorage;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.MediaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 사이트별 콘텐츠의 공개 노출 경계(PLAN-site-menu.md PR ③): 공통(site_id NULL)은 모든 사이트에, 전용은 그 사이트에만 보이고 다른 사이트에는 비노출·없음과
 * <b>같은 응답</b>(페이지는 404)이다. 사이트 {@code main}(기본, 시드)·{@code alpha}·{@code beta}를 두고 홈(배너·팝업·푸터)·FAQ·정적 페이지(현재·개정 목록·
 * 개정)를 교차 확인한다. 행은 JDBC로 직접 만들고 시험이 끝나면 지운다(사이트 삭제 FK가 RESTRICT라 콘텐츠를 먼저 지운다).
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PublicContentSiteScopeIntegrationTest extends MariaDbContainerSupport {

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileStorage fileStorage;

    private String ip;
    private long alpha;
    private long beta;

    @BeforeEach
    void setUp() {
        int n = IP_SEQUENCE.getAndIncrement();
        ip = "10.97." + (n / 250) + "." + (n % 250 + 1);
        cleanAll();
        alpha = site("alpha", true);
        beta = site("beta", true);
    }

    @AfterEach
    void cleanUp() {
        cleanAll();
    }

    private void cleanAll() {
        jdbc.update("DELETE FROM faq");
        jdbc.update("DELETE FROM faq_category");
        jdbc.update("DELETE FROM site_page_revision");
        jdbc.update("DELETE FROM site_page");
        jdbc.update("DELETE FROM popup");
        jdbc.update("DELETE FROM banner");
        MediaTestSupport.cleanLibrary(jdbc, fileStorage);
        jdbc.update("DELETE FROM site WHERE id > 2");
    }

    private long site(String key, boolean use) {
        jdbc.update("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, default_yn) VALUES (?, ?, 'PUBLIC', 0, ?, 0)", key, key, use);
        return jdbc.queryForObject("SELECT id FROM site WHERE site_key = ?", Long.class, key);
    }

    private RequestPostProcessor fromIp() {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private MvcResult getPath(String path) throws Exception {
        return mockMvc.perform(get(path).with(fromIp())).andReturn();
    }

    private String html(String path) throws Exception {
        MvcResult result = getPath(path);
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private long banner(String title, Long siteId, int ord) {
        jdbc.update("INSERT INTO banner (title, use_yn, ord, site_id, create_date, update_date) VALUES (?, 1, ?, ?, NOW(), NOW())", title, ord, siteId);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM banner", Long.class);
        MediaTestSupport.attachImage(jdbc, "BANNER", id, "scope-" + title + "-" + id + ".png", 10);
        return id;
    }

    private void popup(String title, Long siteId) {
        jdbc.update("INSERT INTO popup (title, popup_type, content_html, pos_x, pos_y, width, use_yn, revision, site_id) VALUES (?, 'HTML', '<p>x</p>', 10, 10, 300, 1, 0, ?)", title, siteId);
    }

    private long category(String name, Long siteId, int ord) {
        jdbc.update("INSERT INTO faq_category (name, ord, use_yn, site_id, create_date, update_date) VALUES (?, ?, 1, ?, ?, ?)",
                name, ord, siteId, LocalDateTime.now(), LocalDateTime.now());
        long id = jdbc.queryForObject("SELECT MAX(id) FROM faq_category", Long.class);
        jdbc.update("INSERT INTO faq (category_id, question, answer_html, ord, use_yn, create_date, update_date) VALUES (?, ?, '<p>답</p>', 0, 1, ?, ?)",
                id, name + "-질문", LocalDateTime.now(), LocalDateTime.now());
        return id;
    }

    private void page(String slug, Long siteId, boolean footer) {
        jdbc.update("INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn, site_id, create_date, update_date) VALUES (?, 2, 1, 1, ?, ?, NOW(), NOW())",
                slug, footer, siteId);
        long id = jdbc.queryForObject("SELECT id FROM site_page WHERE slug = ?", Long.class, slug);
        for (int i = 1; i <= 2; i++) {
            jdbc.update("INSERT INTO site_page_revision (page_id, revision_no, title, content, create_date) VALUES (?, ?, ?, ?, NOW())",
                    id, i, "제목-" + slug + "-" + i, "<p>본문 " + i + "</p>");
        }
    }

    @Test
    @DisplayName("홈: 배너·팝업·푸터는 공통 + 그 사이트 전용만 — 다른 사이트 전용은 나오지 않고, 기본 사이트(main)에는 공통만 나온다")
    void homeShowsCommonPlusOwnOnly() throws Exception {
        long common = banner("공통배너", null, 0);
        long forAlpha = banner("알파배너", alpha, 1);
        long forBeta = banner("베타배너", beta, 2);
        popup("공통팝업", null);
        popup("알파팝업", alpha);
        popup("베타팝업", beta);
        page("terms-all", null, true);
        page("terms-alpha", alpha, true);
        page("terms-beta", beta, true);

        String inAlpha = html("/alpha");
        assertThat(inAlpha).contains("공통배너", "알파배너", "공통팝업", "알파팝업", "terms-all", "terms-alpha")
                .doesNotContain("베타배너", "베타팝업", "terms-beta");
        assertThat(inAlpha).contains("/banners/" + common + "/image", "/banners/" + forAlpha + "/image")
                .doesNotContain("/banners/" + forBeta + "/image");

        String inBeta = html("/beta");
        assertThat(inBeta).contains("공통배너", "베타배너", "공통팝업", "베타팝업", "terms-all", "terms-beta")
                .doesNotContain("알파배너", "알파팝업", "terms-alpha");

        String inMain = html("/main");
        assertThat(inMain).contains("공통배너", "공통팝업", "terms-all")
                .doesNotContain("알파배너", "베타배너", "알파팝업", "베타팝업", "terms-alpha", "terms-beta");
    }

    @Test
    @DisplayName("FAQ: 카테고리 소속이 공통이거나 그 사이트인 것만 — FAQ 항목은 카테고리를 따른다")
    void faqFollowsCategoryScope() throws Exception {
        category("공통카테고리", null, 0);
        category("알파카테고리", alpha, 1);
        category("베타카테고리", beta, 2);

        assertThat(html("/alpha/faq")).contains("공통카테고리", "알파카테고리-질문").doesNotContain("베타카테고리");
        assertThat(html("/beta/faq")).contains("공통카테고리", "베타카테고리-질문").doesNotContain("알파카테고리");
        assertThat(html("/main/faq")).contains("공통카테고리").doesNotContain("알파카테고리", "베타카테고리");
    }

    @Test
    @DisplayName("정적 페이지: 다른 사이트 전용 페이지는 현재·개정 목록·특정 개정이 모두 없는 슬러그와 같은 404이고, 공통은 어느 사이트에서나 200")
    void pageIsolation() throws Exception {
        page("only-alpha", alpha, false);
        page("everyone", null, false);

        assertThat(getPath("/alpha/pages/only-alpha").getResponse().getStatus()).isEqualTo(200);
        assertThat(getPath("/alpha/pages/only-alpha/revisions").getResponse().getStatus()).isEqualTo(200);
        assertThat(getPath("/alpha/pages/only-alpha/revisions/1").getResponse().getStatus()).isEqualTo(200);

        int missing = getPath("/beta/pages/does-not-exist").getResponse().getStatus();
        assertThat(missing).isEqualTo(404);
        for (String path : new String[]{"/beta/pages/only-alpha", "/beta/pages/only-alpha/revisions", "/beta/pages/only-alpha/revisions/1",
                "/main/pages/only-alpha"}) {
            MvcResult other = getPath(path);
            assertThat(other.getResponse().getStatus()).as(path).isEqualTo(missing);
            assertThat(other.getResponse().getContentAsString()).as(path + " 본문에 슬러그·제목이 드러나지 않는다")
                    .doesNotContain("only-alpha").doesNotContain("제목-only-alpha");
        }

        for (String site : new String[]{"/alpha", "/beta", "/main"}) {
            assertThat(getPath(site + "/pages/everyone").getResponse().getStatus()).as(site).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("비활성 사이트는 접두어 자체가 404이고, 그 전용 콘텐츠는 다른 사이트에 나오지 않는다")
    void inactiveSiteContentStaysHidden() throws Exception {
        long gamma = site("gamma", false);
        banner("감마배너", gamma, 0);
        page("only-gamma", gamma, true);

        assertThat(getPath("/gamma").getResponse().getStatus()).isEqualTo(404);
        assertThat(html("/alpha")).doesNotContain("감마배너", "only-gamma");
        assertThat(html("/main")).doesNotContain("감마배너", "only-gamma");
    }
}
