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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사이트 설정 화면(ADMIN 전용)의 등록·수정 모달(PLAN-admin-form-modal.md)을 <b>실제 브라우저</b>로 고정한다 — MockMvc는 화면 JS·Bootstrap 모달을 재현하지 못한다:
 * ① 배경 클릭·ESC로 닫히지 않는다 ② 저장이 {@code click}이 아니라 폼 {@code submit}이라 브라우저 기본 검증(버튼·Enter 모두)이 요청 전에 막고 페이지 이동도 없다
 * ③ 미저장 입력이 있으면 닫기 전에 확인한다(취소하면 유지) ④ 저장 중에는 닫을 수 없다 ⑤ 등록·삭제는 모달을 닫고 목록·안내에 반영한다.
 * 기본 지정과 목록 재조회 실패 보정은 {@code SiteDefaultBrowserTest}·{@code SiteMenuScreenBrowserTest}가 맡는다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SiteModalBrowserTest extends BrowserTestSupport {

    private static final String KEY = "smm-";

    @LocalServerPort int port;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;

    private Member admin;
    private BrowserContext context;
    private Page page;

    @BeforeEach
    void setUp() {
        cleanSites();
        admin = TestMembers.save(memberRepository, "smm-admin", Role.ROLE_ADMIN);
        enableFormLogin(jdbc, admin);
        context = newContext();
        page = context.newPage();
        loginAsAdmin(page, port, admin);
    }

    @AfterEach
    void tearDown() {
        context.close();
        cleanSites();
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE action_type LIKE 'SITE_%'");
    }

    private void cleanSites() {
        jdbc.update("DELETE FROM site WHERE site_key LIKE ?", KEY + "%");
    }

    private void insertSite(String suffix) {
        jdbc.update("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, default_yn, create_date, update_date) "
                + "VALUES (?, ?, 'PUBLIC', 0, 1, 0, ?, ?)", KEY + suffix, "SMM " + suffix, LocalDateTime.now(), LocalDateTime.now());
    }

    private void open() {
        page.navigate("http://localhost:" + port + "/admin/site/manage");
        page.waitForFunction("() => document.querySelectorAll('#siteTableBody tr').length >= 2");
    }

    /** 모달 상태(data-state)가 원하는 값이 될 때까지 기다린다. 요소의 보임 여부는 열림 초기에 거짓으로 통과하므로 쓰지 않는다. */
    private void waitModalState(String state) {
        page.waitForFunction("s => document.getElementById('siteModal').dataset.state === s", state);
    }

    private void openRow(String suffix) {
        page.click("#siteTableBody tr:has-text('" + KEY + suffix + "')");
        waitModalState("open");
    }

    private void waitForText(String elementId, String text) {
        page.waitForFunction("args => { const e = document.getElementById(args[0]);"
                + " return e && !e.classList.contains('d-none') && e.textContent.includes(args[1]); }", List.of(elementId, text));
    }

    @Test
    @DisplayName("모달은 배경 클릭·ESC로 닫히지 않고 ×로만 닫힌다")
    void staticBackdropClosesOnlyByButtons() {
        insertSite("static");
        open();
        openRow("static");

        page.keyboard().press("Escape");
        page.mouse().click(5, 5);
        page.waitForTimeout(500);
        assertThat(page.locator("#siteModal.show").count()).as("ESC·배경 클릭으로는 닫히지 않는다").isEqualTo(1);

        page.click("#siteBtnCloseX");
        waitModalState("closed");
    }

    @Test
    @DisplayName("키·이름을 비운 채 [저장]이나 Enter로 제출하면 브라우저 검증이 막아 요청이 나가지 않고 페이지 이동도 없다")
    void emptyFieldsAreBlockedByBrowserValidationForButtonAndEnter() {
        open();
        String url = page.url();
        AtomicInteger writes = new AtomicInteger();
        page.onRequest(request -> {
            if (request.url().contains("/admin/api/sites") && !"GET".equals(request.method())) {
                writes.incrementAndGet();
            }
        });
        page.click("#siteBtnNew");
        waitModalState("open");

        page.click("#siteBtnSave");                          // 푸터 버튼이 form 속성으로 폼에 연결돼 있어야 검증이 동작한다
        // 부정 단언(요청 없음)만으로는 버튼이 폼에 연결되지 않은 경우도 통과하므로, 브라우저가 첫 무효 입력으로 포커스를 옮긴 것을 증거로 삼는다
        assertThat(page.evaluate("() => document.activeElement && document.activeElement.id")).isEqualTo("siteKeyInput");

        page.fill("#siteKeyInput", KEY + "enter");
        page.press("#siteKeyInput", "Enter");                // 암묵 제출 — 이름이 비어 있어 막힌다
        assertThat(page.evaluate("() => document.activeElement && document.activeElement.id")).isEqualTo("siteNameInput");

        page.waitForTimeout(500);
        assertThat(writes.get()).as("검증에 걸려 쓰기 요청이 나가지 않았다").isZero();
        assertThat(page.url()).as("기본 폼 제출로 페이지가 이동하지 않았다").isEqualTo(url);
        assertThat(page.locator("#siteModal.show").count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site WHERE site_key = ?", Integer.class, KEY + "enter")).isZero();
    }

    @Test
    @DisplayName("등록하면 모달이 닫히고 목록에 나타나며 페이지 상단에 안내가 보인다")
    void createClosesTheModalAndShowsNotice() {
        open();
        page.click("#siteBtnNew");
        waitModalState("open");
        page.fill("#siteKeyInput", KEY + "new");
        page.fill("#siteNameInput", "SMM 새 사이트");
        page.click("#siteBtnSave");

        waitForText("siteFormSuccess", "저장했습니다");
        waitModalState("closed");
        page.waitForSelector("#siteTableBody tr:has-text('" + KEY + "new')");
        assertThat(jdbc.queryForObject("SELECT site_name FROM site WHERE site_key = ?", String.class, KEY + "new")).isEqualTo("SMM 새 사이트");
    }

    @Test
    @DisplayName("미저장 입력이 있으면 닫기 전에 확인하고, 취소하면 모달이 유지되며 확인하면 닫힌다")
    void dirtyCloseAsksForConfirmation() {
        insertSite("dirty");
        open();
        openRow("dirty");
        page.fill("#siteNameInput", "SMM 바꾸는 중");

        page.onceDialog(dialog -> dialog.dismiss());         // 취소 — 닫힘이 막혀야 한다
        page.click("#siteBtnClose");
        page.waitForTimeout(500);
        assertThat(page.locator("#siteModal.show").count()).as("확인을 취소하면 모달이 유지된다").isEqualTo(1);
        assertThat(page.inputValue("#siteNameInput")).isEqualTo("SMM 바꾸는 중");
        waitModalState("open");

        page.onceDialog(dialog -> dialog.accept());          // 확인 — 닫힌다
        page.click("#siteBtnClose");
        waitModalState("closed");
        assertThat(jdbc.queryForObject("SELECT site_name FROM site WHERE site_key = ?", String.class, KEY + "dirty")).as("저장되지 않았다").isEqualTo("SMM dirty");
    }

    @Test
    @DisplayName("저장 요청이 진행 중인 동안에는 ×·[닫기]가 비활성이고, 응답이 오면 모달이 닫힌다")
    void closeIsDisabledWhileSaving() {
        insertSite("slow");
        open();
        openRow("slow");
        page.fill("#siteNameInput", "SMM 느린 저장");

        AtomicReference<Route> held = new AtomicReference<>();
        page.route("**/admin/api/sites/*", route -> {
            if (!held.compareAndSet(null, route)) {
                route.resume();
            }
        });
        page.click("#siteBtnSave");
        page.waitForCondition(() -> held.get() != null);

        assertThat(page.isDisabled("#siteBtnClose")).as("[닫기]").isTrue();
        assertThat(page.isDisabled("#siteBtnCloseX")).as("×").isTrue();

        held.get().resume();
        waitForText("siteFormSuccess", "저장했습니다");
        waitModalState("closed");
    }

    @Test
    @DisplayName("삭제하면 모달이 닫히고 목록에서 사라지며 안내가 보인다")
    void deleteClosesTheModal() {
        insertSite("del");
        open();
        page.onDialog(dialog -> dialog.accept());            // confirm()
        openRow("del");
        page.click("#siteBtnDelete");

        waitForText("siteFormSuccess", "사이트를 삭제했습니다");
        waitModalState("closed");
        page.waitForFunction("() => !document.getElementById('siteTableBody').textContent.includes('" + KEY + "del')");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site WHERE site_key = ?", Integer.class, KEY + "del")).isZero();
    }
}
