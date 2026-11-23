package com.cms.admin.menu;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.BrowserTestSupport;
import com.cms.support.CmsTestApplication;
import com.cms.support.TestMembers;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.BoundingBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메뉴 구조 편집 화면의 동작을 <b>실제 브라우저</b>로 고정하는 특성 시험(PLAN-react-admin-menu.md C1·F2~F5).
 *
 * <p>React 전환 전에 옛 화면(jstree)에서 먼저 통과시킨 시험이다(새 화면에만 있는 포인터 드래그 시험 5건과 {@link #menuNameIsRenderedAsText} 제외).
 * 트리 노드 찾기와 이동은 {@link #treeLabel}·{@link #moveMenu} 도우미로만 하고, 트리 구현을 바꿀 때는 도우미만 바꾼다(단언은 DOM id·문구·DB·요청 본문).
 * 시험 데이터는 전용 공개 사이트(키 {@code smt-})와 이름 접두사 {@code SMT_} 메뉴로 격리한다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MenuStructureScreenBrowserTest extends BrowserTestSupport {

    private static final String KEY = "smt-";
    private static final String DRAFT_ONE = "반영 대기 중 1건(이동한 메뉴) — [반영]을 눌러야 저장됩니다";
    private static final String NO_DRAFT = "변경 없음";
    private static final ObjectMapper JSON = new ObjectMapper();

    enum Position { BEFORE, AFTER, INSIDE }

    @LocalServerPort int port;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;

    private Member admin;
    private BrowserContext context;
    private Page page;
    private long siteId;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "smt-admin", Role.ROLE_ADMIN);
        enableFormLogin(jdbc, admin);
        context = newContext();
        page = context.newPage();
        loginAsAdmin(page, port, admin);
        siteId = insertSite("main");
    }

    @AfterEach
    void tearDown() {
        context.close();
        jdbc.update("DELETE FROM menu WHERE menu_name LIKE 'SMT\\_%'");
        jdbc.update("DELETE FROM site WHERE site_key LIKE ?", KEY + "%");
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE action_type LIKE 'MENU_%'");
    }

    // ── 트리 구현에 기대는 도우미(전환 때 이 둘만 바꾼다) ─────────────────────────

    /** 트리에서 이름이 정확히 {@code name}인 노드의 라벨(클릭하면 선택된다). */
    private Locator treeLabel(String name) {
        return page.locator("#menuTree .menu-node-label")
                .filter(new Locator.FilterOptions().setHasText(exactText(name)));
    }

    /** 글자 그대로 일치하는 정규식. Playwright가 원문을 JS 정규식으로 넘기므로 JS에 없는 {@code Pattern.quote}의 이스케이프를 쓰지 않는다. 줄바꿈은 공백류로 맞춘다. */
    private static Pattern exactText(String text) {
        String escaped = text.replaceAll("[\\\\^$.|?*+()\\[\\]{}]", "\\\\$0").replaceAll("\\s+", "\\\\s+");
        return Pattern.compile("^\\s*" + escaped + "\\s*$");
    }

    /** 놓을 자리를 판정하는 행(화면의 판정 기준과 같은 요소 — 좌표는 이 요소 기준이다). */
    private Locator treeRow(String name) {
        return treeLabel(name).locator("xpath=..");
    }

    /** {@code source} 메뉴를 {@code target} 메뉴의 앞·뒤·안(마지막 자식)으로 옮긴다. 화면의 허용 규칙이 거부하면 아무것도 바뀌지 않는다. */
    private void moveMenu(String source, String target, Position position) {
        Locator row = treeRow(target);
        BoundingBox box = row.boundingBox();
        double ratio = switch (position) { case BEFORE -> 0.125; case INSIDE -> 0.5; case AFTER -> 0.875; };
        treeLabel(source).dragTo(row, new Locator.DragToOptions().setTargetPosition(60, box.height * ratio));
    }

    /** 트리에 보이는 메뉴 이름(깊이 우선 문서 순서). */
    private List<String> treeNames() {
        return page.locator("#menuTree .menu-node-label").allTextContents().stream().map(String::strip).toList();
    }

    // ── 데이터·화면 진입 ─────────────────────────────────────────────

    private long insertSite(String suffix) {
        jdbc.update("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, create_date, update_date) "
                + "VALUES (?, ?, 'PUBLIC', 0, 1, ?, ?)", KEY + suffix, "SMT " + suffix, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject("SELECT id FROM site WHERE site_key = ?", Long.class, KEY + suffix);
    }

    private long insertMenu(long site, String name, Long parent, int ord, boolean active) {
        jdbc.update("INSERT INTO menu (menu_name, up_menu_no, use_yn, ord, site_id, create_date, update_date) VALUES (?, ?, ?, ?, ?, ?, ?)",
                name, parent, active, ord, site, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject("SELECT MAX(menu_no) FROM menu WHERE menu_name = ?", Long.class, name);
    }

    private long insertMenu(String name, Long parent, int ord) {
        return insertMenu(siteId, name, parent, ord, true);
    }

    /** 메뉴 화면을 열고 시험 사이트로 바꿔 {@code firstName} 노드가 그려질 때까지 기다린다. */
    private void openOnSite(long site, String firstName) {
        page.navigate("http://localhost:" + port + "/admin/menu/manage");
        page.waitForFunction("() => !document.getElementById('siteSelect').disabled");
        selectSite(site, firstName);
    }

    private void selectSite(long site, String firstName) {
        page.selectOption("#siteSelect", String.valueOf(site));
        treeLabel(firstName).waitFor();
        page.waitForFunction("() => !document.getElementById('siteSelect').disabled");
    }

    private void selectMenu(String name) {
        treeLabel(name).click();
        page.waitForFunction("name => document.getElementById('formModeLabel').textContent === '메뉴 수정: ' + name", name);
    }

    private String draftStatus() {
        return page.textContent("#draftStatus").strip();
    }

    private Long upMenuNo(long menuNo) {
        return jdbc.queryForObject("SELECT up_menu_no FROM menu WHERE menu_no = ?", Long.class, menuNo);
    }

    private static JsonNode json(Request request) {
        return JSON.readTree(request.postData());
    }

    // ── 드래그 초안 ──────────────────────────────────────────────

    @Test
    @DisplayName("같은 부모 안에서 옮기면 반영 대기 1건이 되고, 초안이 있는 동안 저장·셀렉트가 잠긴다(긴 이름 노드 기준)")
    void reorderWithinParentMakesDraft() {
        String longName = "SMT_" + "긴이름".repeat(30);
        insertMenu("SMT_A", null, 0);
        insertMenu(longName, null, 1);
        insertMenu("SMT_C", null, 2);
        openOnSite(siteId, "SMT_A");

        moveMenu("SMT_C", longName, Position.BEFORE);

        assertThat(treeNames()).containsExactly("SMT_A", "SMT_C", longName);
        assertThat(draftStatus()).isEqualTo(DRAFT_ONE);
        assertThat(page.getAttribute("#draftBar", "class")).contains("dirty");
        assertThat(page.isEnabled("#btnApply")).isTrue();
        assertThat(page.isEnabled("#btnCancel")).isTrue();
        assertThat(page.isDisabled("#btnSave")).as("[저장]").isTrue();
        assertThat(page.isDisabled("#btnNewTop")).as("[새 최상위]").isTrue();
        assertThat(page.isDisabled("#includeInactiveToggle")).as("비활성 포함 토글").isTrue();
        assertThat(page.isDisabled("#siteSelect")).as("사이트 셀렉트").isTrue();
        assertThat(page.isDisabled("#menuName")).as("폼 입력").isTrue();
    }

    @Test
    @DisplayName("자기 자손 아래로 옮기기(순환)와 4단이 되는 이동은 초안이 되지 않고, 3단까지는 허용된다")
    void cycleAndDepthViolationsAreNotDrafted() {
        long p = insertMenu("SMT_P", null, 0);
        long q = insertMenu("SMT_Q", p, 0);
        insertMenu("SMT_R", q, 0);
        insertMenu("SMT_X", null, 1);
        openOnSite(siteId, "SMT_R");

        moveMenu("SMT_P", "SMT_R", Position.INSIDE);
        assertThat(draftStatus()).as("순환").isEqualTo(NO_DRAFT);
        moveMenu("SMT_X", "SMT_R", Position.INSIDE);
        assertThat(draftStatus()).as("4단").isEqualTo(NO_DRAFT);
        assertThat(treeNames()).containsExactly("SMT_P", "SMT_Q", "SMT_R", "SMT_X");

        moveMenu("SMT_X", "SMT_Q", Position.INSIDE);
        assertThat(draftStatus()).as("3단은 허용").isEqualTo(DRAFT_ONE);
        assertThat(treeNames()).containsExactly("SMT_P", "SMT_Q", "SMT_R", "SMT_X");
    }

    @Test
    @DisplayName("[반영]은 전체 구조를 base 필드와 함께 보내고, 다시 읽은 화면에 성공 문구를 보인다")
    void applySendsFullStructureAndReloadsWithSuccess() {
        long a = insertMenu("SMT_A", null, 0);
        long b = insertMenu("SMT_B", null, 1);
        openOnSite(siteId, "SMT_A");

        moveMenu("SMT_B", "SMT_A", Position.INSIDE);
        Request put = page.waitForRequest(r -> r.url().endsWith("/admin/api/menus/structure") && "PUT".equals(r.method()),
                () -> page.click("#btnApply"));

        JsonNode body = json(put);
        assertThat(body.get("siteId").asLong()).isEqualTo(siteId);
        assertThat(body.get("menus")).hasSize(2);
        JsonNode first = body.get("menus").get(0);
        JsonNode second = body.get("menus").get(1);
        assertThat(first.get("menuNo").asLong()).isEqualTo(a);
        assertThat(first.has("baseUpMenuNo")).as("최상위도 base 필드를 명시한다").isTrue();
        assertThat(first.get("baseUpMenuNo").isNull()).isTrue();
        assertThat(first.get("baseOrd").asInt()).isZero();
        assertThat(first.get("upMenuNo").isNull()).isTrue();
        assertThat(second.get("menuNo").asLong()).isEqualTo(b);
        assertThat(second.get("baseUpMenuNo").isNull()).isTrue();
        assertThat(second.get("baseOrd").asInt()).isEqualTo(1);
        assertThat(second.get("upMenuNo").asLong()).isEqualTo(a);

        page.waitForFunction("() => document.getElementById('treeSuccess').textContent === '메뉴 구조를 반영했습니다(1건 이동).'");
        assertThat(upMenuNo(b)).isEqualTo(a);
        assertThat(draftStatus()).isEqualTo(NO_DRAFT);
    }

    @Test
    @DisplayName("[반영]이 409면 초안을 버리고 서버 구조로 다시 그린다")
    void applyConflictDiscardsDraft() {
        insertMenu("SMT_A", null, 0);
        insertMenu("SMT_B", null, 1);
        openOnSite(siteId, "SMT_A");
        page.route("**/admin/api/menus/structure", route -> route.fulfill(new Route.FulfillOptions().setStatus(409)
                .setContentType("application/json").setBody("{\"message\":\"다른 관리자가 먼저 바꿨습니다.\"}")));

        moveMenu("SMT_B", "SMT_A", Position.BEFORE);
        page.click("#btnApply");

        page.waitForFunction("() => document.getElementById('treeAlert').textContent"
                + " === '다른 관리자가 먼저 바꿨습니다. 서버의 최신 구조로 새로고침했습니다.'");
        page.waitForFunction("() => document.getElementById('draftStatus').textContent.trim() === '변경 없음'");
        treeLabel("SMT_B").waitFor();
        assertThat(treeNames()).containsExactly("SMT_A", "SMT_B");
        assertThat(page.isEnabled("#btnSave")).isTrue();
    }

    @Test
    @DisplayName("[반영]이 400이면 초안을 남겨 고치거나 취소할 수 있게 한다")
    void applyValidationErrorKeepsDraft() {
        insertMenu("SMT_A", null, 0);
        insertMenu("SMT_B", null, 1);
        openOnSite(siteId, "SMT_A");
        page.route("**/admin/api/menus/structure", route -> route.fulfill(new Route.FulfillOptions().setStatus(400)
                .setContentType("application/json").setBody("{\"message\":\"구조 규칙 위반\"}")));

        moveMenu("SMT_B", "SMT_A", Position.BEFORE);
        page.click("#btnApply");

        page.waitForFunction("() => document.getElementById('treeAlert').textContent === '구조 규칙 위반'");
        page.waitForFunction("() => !document.getElementById('btnApply').disabled");
        assertThat(draftStatus()).isEqualTo(DRAFT_ONE);
        assertThat(treeNames()).containsExactly("SMT_B", "SMT_A");
    }

    @Test
    @DisplayName("[취소]는 초안을 버리고 서버 구조로 되돌린다(서버는 바뀌지 않는다)")
    void cancelRestoresServerStructure() {
        insertMenu("SMT_A", null, 0);
        long b = insertMenu("SMT_B", null, 1);
        openOnSite(siteId, "SMT_A");

        moveMenu("SMT_B", "SMT_A", Position.INSIDE);
        assertThat(draftStatus()).isEqualTo(DRAFT_ONE);
        page.click("#btnCancel");

        page.waitForFunction("() => document.getElementById('draftStatus').textContent.trim() === '변경 없음'");
        treeLabel("SMT_B").waitFor();
        assertThat(treeNames()).containsExactly("SMT_A", "SMT_B");
        assertThat(upMenuNo(b)).isNull();
    }

    @Test
    @DisplayName("'비활성 포함'을 끄면 안내가 바뀌고 드래그로 초안을 만들 수 없다")
    void inactiveScopeDisablesDrag() {
        insertMenu("SMT_A", null, 0);
        insertMenu("SMT_B", null, 1);
        openOnSite(siteId, "SMT_A");

        page.uncheck("#includeInactiveToggle", new Page.UncheckOptions().setForce(true));
        page.waitForFunction("() => document.getElementById('treeHint').textContent.includes('\"비활성 포함\"을 켜면')");
        treeLabel("SMT_B").waitFor();
        moveMenu("SMT_B", "SMT_A", Position.BEFORE);

        assertThat(draftStatus()).isEqualTo(NO_DRAFT);
        assertThat(treeNames()).containsExactly("SMT_A", "SMT_B");
    }

    // ── 포인터 드래그(새 화면) ─────────────────────────────────────────

    private void insertManyMenus() {
        for (int n = 0; n < 60; n++) {
            insertMenu("SMT_%02d".formatted(n), null, n);
        }
    }

    /** {@code name} 라벨을 마우스로 잡아 화면 아래 가장자리에 멈춘다(자동 스크롤 구간). */
    private void dragToBottomEdge(String name) {
        BoundingBox label = treeLabel(name).boundingBox();
        page.mouse().move(label.x + 10, label.y + label.height / 2);
        page.mouse().down();
        page.mouse().move(label.x + 60, page.viewportSize().height - 15, new com.microsoft.playwright.Mouse.MoveOptions().setSteps(5));
    }

    private static final String SCROLL_TOP = "() => document.scrollingElement.scrollTop + document.getElementById('content-wrapper').scrollTop";

    private void assertScrollStopped() {
        Object before = page.evaluate(SCROLL_TOP);
        page.evaluate("() => new Promise(done => { let n = 5; const tick = () => (--n ? requestAnimationFrame(tick) : done()); requestAnimationFrame(tick); })");
        assertThat(page.evaluate(SCROLL_TOP)).as("자동 스크롤이 멈췄다").isEqualTo(before);
    }

    @Test
    @DisplayName("포인터를 가장자리에 멈춰 자동 스크롤하는 동안에도 표시된 자리와 놓은 결과가 같고, Escape로 취소하면 스크롤이 멈춘다")
    void autoScrollKeepsMarkAndResultInSync() {
        insertManyMenus();
        openOnSite(siteId, "SMT_00");
        // 앱의 pointerup 처리보다 먼저 표시된 자리와 포인터 아래의 실제 행을 기록한다
        page.evaluate("() => window.addEventListener('pointerup', e => { const row = document.querySelector('[class*=menu-drop-]');"
                + " const under = document.elementFromPoint(e.clientX, e.clientY).closest('.menu-node-row');"
                + " window.__mark = row && { text: row.textContent.trim(), cls: row.className, under: under && under.textContent.trim() }; }, true)");

        dragToBottomEdge("SMT_00");
        page.waitForFunction(SCROLL_TOP + " > 100");
        page.mouse().up();

        assertThat(page.evaluate("() => window.__mark")).as("놓는 순간 표시된 자리").isNotNull();
        String target = (String) page.evaluate("() => window.__mark.text");
        assertThat(page.evaluate("() => window.__mark.under")).as("표시된 자리는 지금 포인터 아래의 행이다").isEqualTo(target);
        boolean before = (Boolean) page.evaluate("() => window.__mark.cls.includes('menu-drop-before')");
        List<String> names = treeNames();
        assertThat(names.indexOf("SMT_00")).isEqualTo(names.indexOf(target) + (before ? -1 : 1));
        assertThat(draftStatus()).isEqualTo(DRAFT_ONE);

        page.click("#btnCancel");
        page.waitForFunction("() => document.getElementById('draftStatus').textContent.trim() === '변경 없음'");
        page.evaluate("() => { document.scrollingElement.scrollTop = 0; document.getElementById('content-wrapper').scrollTop = 0; }");
        dragToBottomEdge("SMT_00");
        page.waitForFunction(SCROLL_TOP + " > 50");
        page.keyboard().press("Escape");
        assertScrollStopped();
        page.mouse().up();
        assertThat(draftStatus()).isEqualTo(NO_DRAFT);
    }

    @Test
    @DisplayName("드래그 중 포인터를 멈춘 채 스크롤해도(휠 등) 표시된 자리는 지금 포인터 아래의 행을 따라간다")
    void manualScrollKeepsMarkUnderPointer() {
        insertManyMenus();
        openOnSite(siteId, "SMT_00");
        BoundingBox label = treeLabel("SMT_00").boundingBox();
        page.mouse().move(label.x + 10, label.y + label.height / 2);
        page.mouse().down();
        page.mouse().move(label.x + 60, label.y + 100, new com.microsoft.playwright.Mouse.MoveOptions().setSteps(5));
        page.waitForFunction("() => document.querySelector('[class*=menu-drop-]')");

        // 스크롤만 일으키고 포인터 이벤트는 만들지 않는다(휠 뒤 브라우저가 합성하는 마우스 이동에 기대지 않는다)
        page.evaluate("() => { document.scrollingElement.scrollTop += 60; document.getElementById('content-wrapper').scrollTop += 200; }");
        page.waitForFunction("([x, y]) => { const row = document.querySelector('[class*=menu-drop-]');"
                + " return row && row === document.elementFromPoint(x, y).closest('.menu-node-row'); }", List.of(label.x + 60, label.y + 100));
        page.mouse().up();
        assertThat(draftStatus()).isEqualTo(DRAFT_ONE);
    }

    @Test
    @DisplayName("드래그 중 창이 비활성화되면 취소된다 — 초안은 그대로고 스크롤이 멈추며 새 드래그를 할 수 있다")
    void windowBlurCancelsDragAndAllowsNewOne() {
        insertManyMenus();
        openOnSite(siteId, "SMT_00");

        dragToBottomEdge("SMT_00");
        page.waitForFunction(SCROLL_TOP + " > 50");
        page.evaluate("() => window.dispatchEvent(new Event('blur'))");
        assertScrollStopped();
        page.mouse().up();
        assertThat(draftStatus()).isEqualTo(NO_DRAFT);
        assertThat(page.isVisible(".menu-drag-ghost")).isFalse();

        moveMenu("SMT_01", "SMT_00", Position.BEFORE);
        assertThat(draftStatus()).isEqualTo(DRAFT_ONE);
    }

    @Test
    @DisplayName("드래그로 놓은 자리의 click은 선택으로 이어지지 않고, 다음 클릭은 선택된다")
    void dropDoesNotSelect() {
        insertMenu("SMT_A", null, 0);
        insertMenu("SMT_B", null, 1);
        openOnSite(siteId, "SMT_A");
        BoundingBox label = treeLabel("SMT_A").boundingBox();
        page.mouse().move(label.x + 5, label.y + 5);
        page.mouse().down();
        page.mouse().move(label.x + 30, label.y + 8, new com.microsoft.playwright.Mouse.MoveOptions().setSteps(4));
        page.mouse().up();
        assertThat(page.locator("#menuTree .is-selected").count()).isZero();

        selectMenu("SMT_A");
        assertThat(page.locator("#menuTree .is-selected").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("실제 터치 포인터(CDP)로 라벨을 끌어 다른 메뉴 안에 놓으면 초안이 된다")
    void touchPointerDragMakesDraft() {
        insertMenu("SMT_A", null, 0);
        insertMenu("SMT_B", null, 1);
        context.close();
        context = newContext(new Browser.NewContextOptions().setHasTouch(true));
        page = context.newPage();
        loginAsAdmin(page, port, admin);
        openOnSite(siteId, "SMT_A");

        BoundingBox from = treeLabel("SMT_B").boundingBox();
        BoundingBox to = treeRow("SMT_A").boundingBox();
        double x = from.x + 10;
        double startY = from.y + from.height / 2;
        double endY = to.y + to.height / 2;
        CDPSession cdp = context.newCDPSession(page);
        touch(cdp, "touchStart", x, startY);
        for (int step = 1; step <= 5; step++) {
            touch(cdp, "touchMove", x + 50 * step / 5.0, startY + (endY - startY) * step / 5.0);
        }
        touch(cdp, "touchEnd", null, null);

        page.waitForFunction("() => document.getElementById('draftStatus').textContent.includes('1건')");
        page.click("#btnApply");
        page.waitForFunction("() => document.getElementById('treeSuccess').textContent.includes('1건 이동')");
        assertThat(jdbc.queryForObject("SELECT p.menu_name FROM menu c JOIN menu p ON p.menu_no = c.up_menu_no WHERE c.menu_name = 'SMT_B'", String.class))
                .isEqualTo("SMT_A");
    }

    private static void touch(CDPSession cdp, String type, Double x, Double y) {
        JsonArray points = new JsonArray();
        if (x != null) {
            JsonObject point = new JsonObject();
            point.addProperty("x", x);
            point.addProperty("y", y);
            points.add(point);
        }
        JsonObject params = new JsonObject();
        params.addProperty("type", type);
        params.add("touchPoints", points);
        cdp.send("Input.dispatchTouchEvent", params);
    }

    // ── 저장·비활성화·영구삭제·아이콘 ─────────────────────────────────

    @Test
    @DisplayName("[비활성화]는 확인 뒤 PATCH {useYn:false}만 보낸다 — 저장하지 않은 폼 값을 싣지 않는다")
    void deactivateSendsOnlyUseYnFalse() {
        long a = insertMenu("SMT_A", null, 0);
        openOnSite(siteId, "SMT_A");
        selectMenu("SMT_A");
        page.fill("#menuName", "SMT_저장안함");
        page.onDialog(dialog -> dialog.accept());

        Request patch = page.waitForRequest(r -> r.url().endsWith("/admin/api/menus/" + a) && "PATCH".equals(r.method()),
                () -> page.click("#btnDeactivate"));

        assertThat(json(patch).toString()).isEqualTo("{\"useYn\":false}");
        page.waitForFunction("() => document.getElementById('formModeLabel').textContent === '메뉴를 선택하거나 추가 버튼을 눌러주세요.'");
        assertThat(jdbc.queryForObject("SELECT use_yn FROM menu WHERE menu_no = ?", Boolean.class, a)).isFalse();
        assertThat(jdbc.queryForObject("SELECT menu_name FROM menu WHERE menu_no = ?", String.class, a)).isEqualTo("SMT_A");
    }

    @Test
    @DisplayName("영구삭제는 이스케이프한 확인 문자열을 정확히 입력해야 켜지고, 성공하면 트리에서 사라지며 알림이 뜬다")
    void permanentDeleteRequiresExactEscapedName() {
        insertMenu("SMT_A", null, 0);
        long gone = insertMenu(siteId, "SMT_줄\n바꿈", null, 1, false);
        openOnSite(siteId, "SMT_A");
        treeLabel("SMT_줄\n바꿈").click();
        page.waitForFunction("() => !document.getElementById('btnPermanentDelete').classList.contains('d-none')");
        assertThat(page.isVisible("#btnDeactivate")).as("비활성 메뉴에는 [비활성화]가 없다").isFalse();

        page.click("#btnPermanentDelete");
        page.waitForFunction("() => document.getElementById('permanentDeleteModal').classList.contains('show')");
        assertThat(page.textContent("#permanentDeleteConfirmText")).isEqualTo("SMT_줄\\n바꿈");
        assertThat(page.isDisabled("#btnPermanentDeleteConfirm")).isTrue();
        page.fill("#permanentDeleteInput", "SMT_줄바꿈");
        assertThat(page.isDisabled("#btnPermanentDeleteConfirm")).as("다른 문자열").isTrue();
        page.fill("#permanentDeleteInput", "SMT_줄\\n바꿈");
        assertThat(page.isEnabled("#btnPermanentDeleteConfirm")).isTrue();

        page.click("#btnPermanentDeleteConfirm");
        page.waitForFunction("() => document.getElementById('treeSuccess').textContent === '메뉴를 영구삭제했습니다.'");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM menu WHERE menu_no = ?", Integer.class, gone)).isZero();
        assertThat(treeNames()).containsExactly("SMT_A");
    }

    @Test
    @DisplayName("아이콘 선택기에서 검색해 고르면 숨은 값·라벨이 바뀌고 모달이 닫힌다")
    void iconPickerSetsIcon() {
        insertMenu("SMT_A", null, 0);
        openOnSite(siteId, "SMT_A");
        page.click("#btnNewTop");
        page.click("#btnIconPick");
        page.waitForFunction("() => document.getElementById('iconPickerModal').classList.contains('show')");
        page.fill("#iconSearch", "rocket");
        assertThat(page.locator("#iconGrid .icon-item").count()).isEqualTo(1);

        page.click("#iconGrid .icon-item");

        page.waitForFunction("() => !document.getElementById('iconPickerModal').classList.contains('show')");
        assertThat(page.inputValue("#menuIcon")).isEqualTo("fas fa-fw fa-rocket");
        assertThat(page.textContent("#menuIconLabel")).isEqualTo("rocket");
        assertThat(page.locator("#menuIconPreview i.fa-rocket").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("메뉴명은 글자 그대로 보인다 — HTML로 해석하지 않는다")
    void menuNameIsRenderedAsText() {
        insertMenu("SMT_<b>x</b>", null, 0);
        page.navigate("http://localhost:" + port + "/admin/menu/manage");
        page.waitForFunction("() => !document.getElementById('siteSelect').disabled");
        page.selectOption("#siteSelect", String.valueOf(siteId));
        page.waitForFunction("() => document.getElementById('menuTree').textContent.includes('SMT_')");

        assertThat(treeNames()).containsExactly("SMT_<b>x</b>");
        assertThat(page.locator("#menuTree b").count()).isZero();
    }

    // ── 사이트 전환·늦은 응답 ──────────────────────────────────────────

    @Test
    @DisplayName("사이트 전환 조회가 실패하면 셀렉트·편집 대상을 이전 사이트로 되돌린다")
    void siteSwitchFailureRevertsSelection() {
        insertMenu("SMT_A", null, 0);
        long other = insertSite("other");
        insertMenu(other, "SMT_O", null, 0, true);
        openOnSite(siteId, "SMT_A");
        page.route(Pattern.compile(".*/admin/api/menus/tree\\?siteId=" + other + "&.*"), route -> route.fulfill(new Route.FulfillOptions()
                .setStatus(500).setContentType("application/json").setBody("{\"message\":\"강제 실패\"}")));

        page.selectOption("#siteSelect", String.valueOf(other));

        page.waitForFunction("() => document.getElementById('treeAlert').textContent === '강제 실패 이전 사이트로 되돌렸습니다.'");
        page.waitForFunction("() => !document.getElementById('siteSelect').disabled");
        assertThat(page.inputValue("#siteSelect")).isEqualTo(String.valueOf(siteId));
        assertThat(treeNames()).containsExactly("SMT_A");
        assertThat(page.isEnabled("#btnNewTop")).isTrue();
    }

    @Test
    @DisplayName("늦게 온 A 상세 응답은 그 뒤 선택한 B의 폼을 덮지 않고, [저장]은 B를 고친다")
    void lateDetailDoesNotOverrideLaterSelection() {
        long a = insertMenu("SMT_A", null, 0);
        long b = insertMenu("SMT_B", null, 1);
        openOnSite(siteId, "SMT_A");
        holdFirstResponse(page, "/admin/api/menus/" + a + "$");

        treeLabel("SMT_A").click();
        selectMenu("SMT_B");
        releaseHeldResponse(page);

        assertThat(page.textContent("#formModeLabel")).isEqualTo("메뉴 수정: SMT_B");
        assertThat(page.inputValue("#menuName")).isEqualTo("SMT_B");
        Request patch = page.waitForRequest(r -> "PATCH".equals(r.method()), () -> page.click("#btnSave"));
        assertThat(patch.url()).endsWith("/admin/api/menus/" + b);
    }

    @Test
    @DisplayName("늦게 온 상세 응답은 사이트 전환 뒤의 빈 폼을 옛 사이트 메뉴로 채우지 않는다")
    void lateDetailDoesNotSurviveSiteSwitch() {
        long a = insertMenu("SMT_A", null, 0);
        long other = insertSite("switch");
        insertMenu(other, "SMT_O", null, 0, true);
        openOnSite(siteId, "SMT_A");
        holdFirstResponse(page, "/admin/api/menus/" + a + "$");

        treeLabel("SMT_A").click();
        selectSite(other, "SMT_O");
        releaseHeldResponse(page);

        assertThat(page.inputValue("#menuName")).isEmpty();
        assertThat(page.textContent("#formModeLabel")).isEqualTo("메뉴를 선택하거나 추가 버튼을 눌러주세요.");
        assertThat(page.isHidden("#btnDeactivate")).isTrue();
    }

    @Test
    @DisplayName("늦게 온 상세 응답은 [새 최상위]로 들어간 생성 모드를 편집 모드로 되돌리지 않는다")
    void lateDetailDoesNotLeaveCreateMode() {
        long a = insertMenu("SMT_A", null, 0);
        openOnSite(siteId, "SMT_A");
        holdFirstResponse(page, "/admin/api/menus/" + a + "$");

        treeLabel("SMT_A").click();
        page.click("#btnNewTop");
        releaseHeldResponse(page);

        assertThat(page.textContent("#formModeLabel")).isEqualTo("새 메뉴 추가 (최상위)");
        assertThat(page.inputValue("#menuName")).isEmpty();
    }

    @Test
    @DisplayName("상세 응답 전에 누른 [하위 추가]도 트리에서 고른 메뉴를 부모로 쓴다")
    void childCreateUsesTreeSelectionDuringPendingDetail() {
        long a = insertMenu("SMT_A", null, 0);
        openOnSite(siteId, "SMT_A");
        holdFirstResponse(page, "/admin/api/menus/" + a + "$");

        treeLabel("SMT_A").click();
        page.click("#btnNewChild");
        releaseHeldResponse(page);

        assertThat(page.textContent("#formModeLabel")).isEqualTo("새 메뉴 추가 (상위: SMT_A)");
        page.fill("#menuName", "SMT_child");
        page.fill("#menuUrl", "/faq");
        Request post = page.waitForRequest(r -> r.url().endsWith("/admin/api/menus") && "POST".equals(r.method()),
                () -> page.click("#btnSave"));
        assertThat(json(post).get("upMenuNo").asLong()).isEqualTo(a);
        assertThat(json(post).get("siteId").asLong()).isEqualTo(siteId);
        treeLabel("SMT_child").waitFor();
    }
}
