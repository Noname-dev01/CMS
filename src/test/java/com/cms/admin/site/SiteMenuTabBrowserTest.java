package com.cms.admin.site;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.BrowserTestSupport;
import com.cms.support.CmsTestApplication;
import com.cms.support.TestMembers;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메뉴 구조 탭의 사이트 선택 상태 전이를 <b>실제 브라우저</b>로 고정한다(PLAN-site-menu.md D7, 코드 리뷰 R1). MockMvc는 화면 JS를 실행하지 못해
 * 이 결함군(저장 직후 셀렉트가 잠긴 채 남음, 지연된 사이트 목록 응답이 현재 선택을 되돌림)을 잡지 못한다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SiteMenuTabBrowserTest extends BrowserTestSupport {

    private static final String KEY = "smb-";

    @LocalServerPort int port;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;

    private Member admin;
    private BrowserContext context;
    private Page page;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "ui-admin", Role.ROLE_ADMIN);
        enableFormLogin(jdbc, admin);
        context = newContext();
        page = context.newPage();
        loginAsAdmin(page, port, admin);
    }

    @AfterEach
    void tearDown() {
        context.close();
        jdbc.update("DELETE FROM menu WHERE menu_name LIKE 'SMB_%'");
        jdbc.update("DELETE FROM site WHERE site_key LIKE ?", KEY + "%");
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE action_type LIKE 'MENU_%' OR action_type LIKE 'SITE_%'");
    }

    private long insertSite(String suffix) {
        jdbc.update("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, create_date, update_date) "
                + "VALUES (?, ?, 'PUBLIC', 1, 1, ?, ?)", KEY + suffix, "SMB " + suffix, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject("SELECT id FROM site WHERE site_key = ?", Long.class, KEY + suffix);
    }

    private void insertMenu(long siteId, String name, int ord) {
        jdbc.update("INSERT INTO menu (menu_name, use_yn, ord, site_id, create_date, update_date) VALUES (?, 1, ?, ?, ?, ?)",
                "SMB_" + name, ord, siteId, LocalDateTime.now(), LocalDateTime.now());
    }

    private void openMenuTab() {
        page.navigate("http://localhost:" + port + "/admin/site-page/manage#menu");
        // 첫 트리가 그려지고 셀렉트가 풀릴 때까지(초기에는 전환 중 잠금)
        page.waitForFunction("() => !document.getElementById('siteSelect').disabled"
                + " && document.querySelectorAll('#menuTree .jstree-anchor').length > 0");
    }

    private void selectSiteAndWaitTree(long siteId, String firstMenuText) {
        page.selectOption("#siteSelect", String.valueOf(siteId));
        page.waitForFunction("text => Array.from(document.querySelectorAll('#menuTree .jstree-anchor'))"
                + ".some(a => a.textContent.trim() === text) && !document.getElementById('siteSelect').disabled", firstMenuText);
    }

    @Test
    @DisplayName("메뉴를 저장한 직후에도 사이트 셀렉트가 다시 켜진다 — busy가 풀릴 때 UI를 갱신하지 않아 disabled로 남던 결함")
    void siteSelectIsEnabledAfterSave() {
        long siteId = insertSite("save");
        insertMenu(siteId, "s-0", 0);
        openMenuTab();
        selectSiteAndWaitTree(siteId, "SMB_s-0");

        page.click("#btnNewTop");
        page.fill("#menuName", "SMB_s-1");
        page.fill("#menuUrl", "/faq");
        page.click("#btnSave");
        page.waitForFunction("() => Array.from(document.querySelectorAll('#menuTree .jstree-anchor')).some(a => a.textContent.trim() === 'SMB_s-1')");

        // 저장·재조회가 끝난 뒤 셀렉트가 풀려야 한다(풀리지 않으면 시간 초과로 실패)
        page.waitForFunction("() => !document.getElementById('siteSelect').disabled");
        assertThat(jdbc.queryForObject("SELECT site_id FROM menu WHERE menu_name = 'SMB_s-1'", Long.class)).isEqualTo(siteId);
    }

    @Test
    @DisplayName("선택 사이트가 아닌 사이트가 삭제된 알림의 지연된 목록 응답이 도착해도, 그 사이 전환한 사이트의 선택·트리는 유지된다")
    void delayedSitesResponseDoesNotRevertCurrentSelection() {
        long siteA = insertSite("a");
        long siteB = insertSite("b");
        insertMenu(siteB, "b-1", 0);
        insertMenu(siteB, "b-2", 1);
        insertMenu(siteA, "a-1", 0);
        openMenuTab();
        selectSiteAndWaitTree(siteA, "SMB_a-1");

        // 사이트 목록 요청을 붙잡아 둔다(응답이 늦게 도착하는 상황)
        AtomicReference<Route> held = new AtomicReference<>();
        page.route("**/admin/api/sites", route -> {
            if (!held.compareAndSet(null, route)) {
                route.resume();
            }
        });
        jdbc.update("DELETE FROM menu WHERE site_id = ?", siteA);
        jdbc.update("DELETE FROM site WHERE id = ?", siteA);
        page.evaluate("() => window.dispatchEvent(new CustomEvent('content:sites-changed'))");
        page.waitForCondition(() -> held.get() != null);

        // 응답을 기다리는 사이 사용자가 살아 있는 사이트 B로 전환한다
        selectSiteAndWaitTree(siteB, "SMB_b-1");
        held.get().resume();
        page.waitForFunction("() => !document.getElementById('siteSelect').textContent.includes('SMB a')");

        assertThat(page.inputValue("#siteSelect")).as("선택은 B로 남는다(옛 선택 A의 삭제로 관리자 사이트로 되돌리지 않는다)")
                .isEqualTo(String.valueOf(siteB));
        assertThat(page.locator("#menuTree .jstree-anchor").allTextContents()).extracting(String::trim)
                .containsExactly("SMB_b-1", "SMB_b-2");
        assertThat(page.textContent("#treeWarning")).as("삭제 안내(관리자 사이트로 이동)가 뜨지 않는다").doesNotContain("삭제되어");
    }

    @Test
    @DisplayName("사이트 삭제 뒤 목록 재조회가 끝나기 전에는 사이트 폼 잠금이 풀리지 않는다 — 풀리면 그 사이 시작한 새 저장이 입력을 날린다")
    void siteFormStaysLockedUntilDeleteRefreshFinishes() {
        long siteId = insertSite("del");
        page.onDialog(dialog -> dialog.accept());
        page.navigate("http://localhost:" + port + "/admin/site-page/manage#site");
        page.waitForSelector("#siteTableBody tr:has-text('" + KEY + "del')");
        page.click("#siteTableBody tr:has-text('" + KEY + "del')");
        page.waitForFunction("() => !document.getElementById('siteBtnDelete').classList.contains('d-none')");

        // 삭제 응답 뒤의 목록 재조회(GET)를 붙잡아 둔다
        AtomicReference<Route> held = new AtomicReference<>();
        page.route("**/admin/api/sites", route -> {
            if (!held.compareAndSet(null, route)) {
                route.resume();
            }
        });
        page.click("#siteBtnDelete");
        page.waitForCondition(() -> held.get() != null);
        // 삭제 DELETE는 이미 끝났다 — 목록 응답이 오기 전에도 새 작성·저장은 막혀 있어야 한다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site WHERE id = ?", Integer.class, siteId)).isZero();
        assertThat(page.isDisabled("#siteBtnNew")).as("[새 공개 사이트]").isTrue();
        assertThat(page.isDisabled("#siteBtnSave")).as("[저장]").isTrue();

        held.get().resume();
        page.waitForFunction("() => !document.getElementById('siteBtnNew').disabled");
    }

    @Test
    @DisplayName("저장은 성공했는데 뒤이은 목록 재조회가 실패해도 폼은 저장 전 값으로 되돌아가지 않고, 다시 저장해도 방금 바꾼 설정을 되돌리지 않는다")
    void listRefreshFailureAfterSaveKeepsSavedValues() {
        long siteId = insertSite("fail");
        page.navigate("http://localhost:" + port + "/admin/site-page/manage#site");
        page.waitForSelector("#siteTableBody tr:has-text('" + KEY + "fail')");
        page.click("#siteTableBody tr:has-text('" + KEY + "fail')");
        page.waitForFunction("() => document.getElementById('siteNameInput').value === 'SMB fail'");

        // 목록 조회(GET)만 실패시킨다 — 저장(PATCH /admin/api/sites/{id})은 그대로 나간다
        page.route("**/admin/api/sites", route -> route.fulfill(new Route.FulfillOptions().setStatus(500)
                .setContentType("application/json").setBody("{\"message\":\"강제 실패\"}")));
        page.fill("#siteNameInput", "SMB 새 이름");
        page.uncheck("#siteAllowExternal", new Page.UncheckOptions().setForce(true));
        page.click("#siteBtnSave");
        page.waitForFunction("() => document.getElementById('siteFormSuccess').textContent.includes('저장했습니다')");

        assertThat(page.inputValue("#siteNameInput")).as("폼은 저장된 값").isEqualTo("SMB 새 이름");
        assertThat(page.isChecked("#siteAllowExternal")).as("외부 링크 허용도 저장된 값(해제)").isFalse();

        // 다른 필드를 고쳐 다시 저장해도 방금 해제한 외부 링크 허용이 되살아나지 않는다
        page.fill("#siteNameInput", "SMB 두번째");
        page.click("#siteBtnSave");
        page.waitForFunction("() => document.getElementById('siteNameInput').value === 'SMB 두번째'");
        page.waitForFunction("() => !document.getElementById('siteBtnSave').disabled");
        assertThat(jdbc.queryForObject("SELECT allow_external_link FROM site WHERE id = ?", Boolean.class, siteId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT site_name FROM site WHERE id = ?", String.class, siteId)).isEqualTo("SMB 두번째");
    }
}
