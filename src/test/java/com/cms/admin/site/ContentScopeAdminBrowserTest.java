package com.cms.admin.site;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.common.storage.FileStorage;
import com.cms.support.BrowserTestSupport;
import com.cms.support.CmsTestApplication;
import com.cms.support.MediaTestSupport;
import com.cms.support.TestMembers;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 콘텐츠 관리 화면의 표시 대상 선택(PLAN-site-menu.md PR ③ S6b·S8·v4~v5)을 <b>실제 브라우저</b>로 고정한다 — MockMvc는 화면 JS를 재현하지 못한다:
 * ① 대상 변경이 요청에 실리고(편집 스냅샷이 사용자 입력을 덮지 않는다) 저장 뒤 새 버전이 다음 저장의 기준이 된다
 * ② 다른 관리자가 대상을 바꾼 뒤 [새로고침]으로 목록만 갱신하고 옛 폼을 저장하면 거부되어 대상이 되돌려지지 않는다(목록 갱신이 검증 기준을 바꾸지 않는다)
 * ③ 문서를 연 뒤 생긴 사이트의 전용 항목은 "목록에 없음" 옵션으로 보존되어 저장해도 공통으로 바뀌지 않는다
 * ④ 정적 페이지의 공개 주소가 {@code /사이트키/pages/…}다 ⑤ FAQ 상세와 트리가 어긋나면 저장하지 않고 목록을 다시 읽는다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContentScopeAdminBrowserTest extends BrowserTestSupport {

    @LocalServerPort int port;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileStorage fileStorage;

    private Member admin;
    private BrowserContext context;
    private Page page;
    private long alpha;
    private long beta;

    @BeforeEach
    void setUp() {
        cleanContent();
        alpha = site("ui-alpha");
        beta = site("ui-beta");
        admin = TestMembers.save(memberRepository, "ui-scope-admin", Role.ROLE_ADMIN);
        enableFormLogin(jdbc, admin);
        context = newContext();
        page = context.newPage();
        loginAsAdmin(page, port, admin);
    }

    @AfterEach
    void tearDown() {
        context.close();
        cleanContent();
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE action_type LIKE 'BANNER_%' OR action_type LIKE 'FAQ_%' OR action_type LIKE 'SITE_PAGE_%'");
    }

    private void cleanContent() {
        jdbc.update("DELETE FROM faq");
        jdbc.update("DELETE FROM faq_category");
        jdbc.update("DELETE FROM site_page_revision");
        jdbc.update("DELETE FROM site_page");
        jdbc.update("DELETE FROM banner");
        MediaTestSupport.cleanLibrary(jdbc, fileStorage);
        jdbc.update("DELETE FROM site WHERE id > 2");
    }

    private long site(String key) {
        jdbc.update("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, default_yn) VALUES (?, ?, 'PUBLIC', 0, 1, 0)", key, key);
        return jdbc.queryForObject("SELECT id FROM site WHERE site_key = ?", Long.class, key);
    }

    private long banner(String title) {
        jdbc.update("INSERT INTO banner (title, use_yn, ord, create_date, update_date) VALUES (?, 1, 0, NOW(), NOW())", title);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM banner", Long.class);
        MediaTestSupport.attachImage(jdbc, "BANNER", id, "ui-scope-" + id + ".png", 10);
        return id;
    }

    private void open(String path, String listId) {
        page.navigate("http://localhost:" + port + path);
        page.waitForFunction("id => !document.getElementById(id).textContent.includes('불러오는 중')", listId);
    }

    /** 배너 행을 눌러 수정 모달이 완전히 열릴 때까지 기다린다. */
    private void openBannerRow(String title) {
        page.locator(".banner-row", new Page.LocatorOptions().setHasText(title)).click();
        page.waitForFunction("() => modalState === 'open'");   // 열림 전환(shown)이 끝나야 닫기 동작을 시험할 수 있다
    }

    /** 모달이 완전히 닫힐 때까지 기다린다(저장 성공 뒤 자동으로 닫힌다). */
    private void waitBannerModalClosed() {
        page.waitForSelector("#bannerModal", new Page.WaitForSelectorOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN));
    }

    private void waitForText(String elementId, String text) {
        page.waitForFunction("args => { const e = document.getElementById(args[0]);"
                + " return e && !e.classList.contains('d-none') && e.textContent.includes(args[1]); }", List.of(elementId, text));
    }

    // ===== 배너 =====

    @Test
    @DisplayName("배너: 선택 박스로 바꾼 대상이 요청에 실려 저장되고, 저장 뒤 모달을 닫았다 다시 열어 새 버전으로 이어서 저장할 수 있다")
    void bannerScopeChangeIsSentAndBaseAdvances() {
        long id = banner("scope-banner");
        open("/admin/banner/manage", "bannerList");
        openBannerRow("scope-banner");
        assertThat(page.locator("#bannerSiteScope").inputValue()).isEqualTo("COMMON");

        page.selectOption("#bannerSiteScope", String.valueOf(alpha));
        page.click("#btnSave");
        waitForText("pageSuccess", "배너를 저장했습니다");
        waitBannerModalClosed();
        assertThat(jdbc.queryForObject("SELECT site_id FROM banner WHERE id = ?", Long.class, id)).as("선택한 대상이 저장됐다").isEqualTo(alpha);
        assertThat(jdbc.queryForObject("SELECT version FROM banner WHERE id = ?", Integer.class, id)).isEqualTo(2);

        // 저장 뒤 모달이 닫힌다. 같은 행을 다시 열어 이어서 저장 — 목록의 새 version이 기준이 되어 409가 나지 않는다
        openBannerRow("scope-banner");
        assertThat(page.locator("#bannerSiteScope").inputValue()).isEqualTo(String.valueOf(alpha));
        page.selectOption("#bannerSiteScope", String.valueOf(beta));
        page.click("#btnSave");
        page.waitForFunction("() => document.getElementById('bannerList').textContent.includes('ui-beta')");
        assertThat(jdbc.queryForObject("SELECT site_id FROM banner WHERE id = ?", Long.class, id)).isEqualTo(beta);
        assertThat(jdbc.queryForObject("SELECT version FROM banner WHERE id = ?", Integer.class, id)).isEqualTo(3);
    }

    @Test
    @DisplayName("배너: 다른 관리자가 대상을 바꾼 뒤 옛 폼을 저장하면 거부되어(모달에 안내, 모달 유지) 대상이 되돌려지지 않고, 닫았다 다시 열어 불러오면 저장된다")
    void bannerStaleFormAfterListRefreshIsRejected() {
        long id = banner("stale-banner");
        open("/admin/banner/manage", "bannerList");
        openBannerRow("stale-banner");

        // 다른 관리자가 이 배너를 사이트 전용으로 바꿨다 — 열려 있는 폼은 옛 값·옛 version 그대로다
        jdbc.update("UPDATE banner SET site_id = ?, version = version + 1 WHERE id = ?", beta, id);

        page.fill("#bannerTitle", "stale-edit");
        page.click("#btnSave");
        waitForText("modalAlert", "먼저 수정");
        assertThat(page.locator("#bannerModal.show").count()).as("오류가 나도 모달은 유지되어 입력이 남는다").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT site_id FROM banner WHERE id = ?", Long.class, id)).as("공통으로 되돌려지지 않았다").isEqualTo(beta);
        assertThat(jdbc.queryForObject("SELECT title FROM banner WHERE id = ?", String.class, id)).isEqualTo("stale-banner");

        // 모달을 닫고 목록을 새로 읽은 뒤 행을 다시 열면 최신 값을 불러와 저장된다
        page.click("#btnModalClose");
        waitBannerModalClosed();
        page.click("#btnReload");
        page.waitForFunction("() => document.getElementById('bannerList').textContent.includes('ui-beta')");
        openBannerRow("stale-banner");
        assertThat(page.locator("#bannerSiteScope").inputValue()).isEqualTo(String.valueOf(beta));
        page.fill("#bannerTitle", "fresh-edit");
        page.click("#btnSave");
        waitForText("pageSuccess", "배너를 저장했습니다");
        assertThat(jdbc.queryForObject("SELECT title FROM banner WHERE id = ?", String.class, id)).isEqualTo("fresh-edit");
        assertThat(jdbc.queryForObject("SELECT site_id FROM banner WHERE id = ?", Long.class, id)).isEqualTo(beta);
    }

    @Test
    @DisplayName("배너: 문서를 연 뒤 생긴 사이트의 전용 배너는 '목록에 없음' 옵션으로 보존되어 저장해도 공통으로 바뀌지 않는다")
    void bannerOfSiteCreatedAfterPageLoadKeepsItsScope() {
        open("/admin/banner/manage", "bannerList");           // 이 시점의 옵션에는 새 사이트가 없다
        long gamma = site("ui-gamma");
        long id = banner("gamma-banner");
        jdbc.update("UPDATE banner SET site_id = ? WHERE id = ?", gamma, id);

        page.click("#btnReload");
        page.waitForFunction("() => document.getElementById('bannerList').textContent.includes('gamma-banner')");
        openBannerRow("gamma-banner");

        assertThat(page.locator("#bannerSiteScope").inputValue()).as("선택이 비워져 공통으로 보이지 않는다").isEqualTo(String.valueOf(gamma));
        assertThat(page.locator("#bannerSiteScope option:checked").textContent()).contains("목록에 없음");

        page.fill("#bannerTitle", "gamma-edited");
        page.click("#btnSave");
        waitForText("pageSuccess", "배너를 저장했습니다");
        assertThat(jdbc.queryForObject("SELECT site_id FROM banner WHERE id = ?", Long.class, id)).isEqualTo(gamma);
        assertThat(jdbc.queryForObject("SELECT title FROM banner WHERE id = ?", String.class, id)).isEqualTo("gamma-edited");
    }

    // ===== 정적 페이지 =====

    @Test
    @DisplayName("정적 페이지: 공개 주소는 전용이면 그 사이트 키, 공통이면 기본 공개 사이트 키(main)를 접두어로 만들고, 개정 링크도 같다")
    void pagePublicUrlUsesSitePrefix() {
        jdbc.update("INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn, site_id, create_date, update_date) VALUES ('ui-scoped', 1, 1, 1, 0, ?, NOW(), NOW())", alpha);
        jdbc.update("INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn, create_date, update_date) VALUES ('ui-common', 1, 1, 1, 0, NOW(), NOW())");
        for (String slug : new String[]{"ui-scoped", "ui-common"}) {
            long pageId = jdbc.queryForObject("SELECT id FROM site_page WHERE slug = ?", Long.class, slug);
            jdbc.update("INSERT INTO site_page_revision (page_id, revision_no, title, content, create_date) VALUES (?, 1, ?, '<p>x</p>', NOW())", pageId, slug);
        }
        open("/admin/site-page/manage", "pageList");

        page.locator(".page-row", new Page.LocatorOptions().setHasText("ui-scoped")).click();
        page.waitForFunction("() => document.getElementById('slugLink').textContent.includes('공개 주소')");
        assertThat(page.locator("#slugLink").textContent()).contains("/ui-alpha/pages/ui-scoped");
        assertThat(page.locator("#revisionList a").first().getAttribute("href")).isEqualTo("/ui-alpha/pages/ui-scoped/revisions/1");

        page.locator(".page-row", new Page.LocatorOptions().setHasText("ui-common")).click();
        page.waitForFunction("() => document.getElementById('slugLink').textContent.includes('ui-common')");
        assertThat(page.locator("#slugLink").textContent()).contains("/main/pages/ui-common");
        assertThat(page.locator("#revisionList a").first().getAttribute("href")).isEqualTo("/main/pages/ui-common/revisions/1");
    }

    // ===== FAQ =====

    @Test
    @DisplayName("FAQ: 트리를 읽은 뒤 다른 관리자가 항목을 새 전용 카테고리로 옮기면, 그 항목을 열 때 저장하지 않고 목록을 다시 읽는다(소속 복원 불가)")
    void faqDetailThatTreeCannotRestoreBlocksEdit() {
        jdbc.update("INSERT INTO faq_category (name, ord, use_yn, create_date, update_date) VALUES ('common-cat', 0, 1, NOW(), NOW())");
        long common = jdbc.queryForObject("SELECT MAX(id) FROM faq_category", Long.class);
        jdbc.update("INSERT INTO faq (category_id, question, answer_html, ord, use_yn, create_date, update_date) VALUES (?, 'moved-q', '<p>a</p>', 0, 1, NOW(), NOW())", common);
        long faq = jdbc.queryForObject("SELECT MAX(id) FROM faq", Long.class);

        open("/admin/faq/manage", "faqTree");
        page.waitForFunction("() => document.getElementById('faqTree').textContent.includes('moved-q')");

        // 다른 관리자가 새 전용 카테고리를 만들고 항목을 옮겼다 — 화면의 트리는 아직 옛 것
        jdbc.update("INSERT INTO faq_category (name, ord, use_yn, site_id, create_date, update_date) VALUES ('scoped-cat', 1, 1, ?, NOW(), NOW())", alpha);
        long scoped = jdbc.queryForObject("SELECT MAX(id) FROM faq_category", Long.class);
        jdbc.update("UPDATE faq SET category_id = ? WHERE id = ?", scoped, faq);

        page.locator(".faq-row-question", new Page.LocatorOptions().setHasText("moved-q")).click();
        waitForText("pageAlert", "화면 목록에 없습니다");
        page.waitForFunction("() => document.getElementById('faqTree').textContent.includes('scoped-cat')");   // 목록을 다시 읽었다
        assertThat(jdbc.queryForObject("SELECT category_id FROM faq WHERE id = ?", Long.class, faq)).as("항목은 옮겨진 전용 카테고리에 그대로").isEqualTo(scoped);
        assertThat(page.locator("#faqFormTitle").textContent()).as("편집 폼이 열리지 않았다").contains("새 FAQ");
    }
}
