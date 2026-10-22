package com.cms.admin.page;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionCache;
import com.cms.support.BrowserTestSupport;
import com.cms.support.CmsTestApplication;
import com.cms.support.TestMembers;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정적 페이지 관리 화면의 등록·수정 모달(PLAN-admin-form-modal-page.md)을 <b>실제 브라우저</b>로 고정한다 — MockMvc는 화면 JS·Bootstrap 모달을 재현하지 못한다:
 * ① 배경 클릭·ESC로 닫히지 않는다 ② 푸터 [등록/저장]이 폼에 연결되어 브라우저 검증이 요청 전에 막는다 ③ 등록·삭제는 모달을 닫고 목록에 반영한다
 * ④ 미저장 입력이 있으면 닫기 전에 확인한다(취소하면 유지) ⑤ 낡은 화면 409는 모달을 닫지 않고 입력을 보존하며, 목록 재조회가 지연돼도 닫을 수 있고
 * 닫았다 다시 연 뒤 시작한 새 요청의 잠금을 이전 요청의 늦은 해제가 풀지 못한다 ⑥ 상세 조회가 즉시 실패해도 모달이 닫히고, 조회 중 닫아도 다음 진입이 막히지 않는다
 * ⑦ 수정 권한 없이도 행을 열어 볼 수 있다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PageModalBrowserTest extends BrowserTestSupport {

    private static final String SLUG = "pm-";

    @LocalServerPort int port;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PermissionCache cache;

    private Member admin;
    private Member manager;
    private BrowserContext context;
    private Page page;

    @BeforeEach
    void setUp() {
        cleanPages();
        admin = TestMembers.save(memberRepository, "pm-admin", Role.ROLE_ADMIN);
        manager = TestMembers.save(memberRepository, "pm-manager", Role.ROLE_MANAGER);
        enableFormLogin(jdbc, admin);
        enableFormLogin(jdbc, manager);
        context = newContext();
        page = context.newPage();
    }

    @AfterEach
    void tearDown() {
        context.close();
        cleanPages();
        TestMembers.delete(jdbc, List.of(admin.getId(), manager.getId()));
        cache.invalidate();
        jdbc.update("DELETE FROM admin_action_log WHERE action_type LIKE 'SITE_PAGE_%'");
    }

    private void cleanPages() {
        jdbc.update("DELETE FROM site_page_revision WHERE page_id IN (SELECT id FROM site_page WHERE slug LIKE ?)", SLUG + "%");
        jdbc.update("DELETE FROM site_page WHERE slug LIKE ?", SLUG + "%");
    }

    /** 정적 페이지 하나(개정 1)를 DB에 직접 넣는다. */
    private long pageRow(String suffix, String title) {
        jdbc.update("INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn, create_date, update_date) VALUES (?, 1, 1, 1, 0, NOW(), NOW())",
                SLUG + suffix);
        long id = jdbc.queryForObject("SELECT id FROM site_page WHERE slug = ?", Long.class, SLUG + suffix);
        jdbc.update("INSERT INTO site_page_revision (page_id, revision_no, title, content, create_date) VALUES (?, 1, ?, '<p>본문</p>', NOW())", id, title);
        return id;
    }

    private String titleOf(long id) {
        return jdbc.queryForObject("SELECT title FROM site_page_revision WHERE page_id = ? ORDER BY revision_no DESC LIMIT 1", String.class, id);
    }

    private void grant(String... actions) {
        jdbc.update("DELETE FROM member_permission WHERE member_id = ?", manager.getId());
        for (String action : actions) {
            jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, 'SITE_PAGE', ?)", manager.getId(), action);
        }
        cache.invalidate();
    }

    private void open(Member member) {
        loginAsAdmin(page, port, member);
        page.navigate("http://localhost:" + port + "/admin/site-page/manage");
        page.waitForFunction("() => !document.getElementById('pageList').textContent.includes('불러오는 중')");
    }

    /** 모달 상태(data-state)가 원하는 값이 될 때까지 기다린다. 요소의 보임 여부는 열림 초기에 거짓으로 통과하므로 쓰지 않는다. */
    private void waitModalState(String state) {
        page.waitForFunction("s => document.getElementById('pageModal').dataset.state === s", state);
    }

    private Locator row(String suffix) {
        return page.locator("#pageList .page-row").filter(new Locator.FilterOptions().setHasText(SLUG + suffix));
    }

    /** 행을 눌러 모달이 완전히 열리고 상세가 폼에 채워질 때까지 기다린다. */
    private void openRow(String suffix, long id) {
        row(suffix).click();
        waitModalState("open");
        page.waitForFunction("id => document.getElementById('formTitle').textContent.includes('#' + id + ' 수정')"
                + " && document.getElementById('pageTitle').value !== ''", String.valueOf(id));
    }

    private void waitForText(String elementId, String text) {
        page.waitForFunction("args => { const e = document.getElementById(args[0]);"
                + " return e && !e.classList.contains('d-none') && e.textContent.includes(args[1]); }", List.of(elementId, text));
    }

    @Test
    @DisplayName("모달은 배경 클릭·ESC로 닫히지 않고 ×로만 닫힌다")
    void staticBackdropClosesOnlyByButtons() {
        long id = pageRow("static", "정적 시험");
        open(admin);
        openRow("static", id);
        countHidePrevented(page, "#pageModal");

        page.keyboard().press("Escape");
        page.mouse().click(5, 5);
        assertHidePrevented(page, 2);                          // 두 입력이 모두 Bootstrap에 닿아 거부됐다(양성 증거, 동기 발생)
        assertThat(page.locator("#pageModal.show").count()).as("ESC·배경 클릭으로는 닫히지 않는다").isEqualTo(1);

        page.click("#btnModalCloseX");
        waitModalState("closed");
    }

    @Test
    @DisplayName("슬러그·제목을 비운 채 [등록]하면 브라우저 검증이 막아 요청이 나가지 않고 모달이 유지된다")
    void emptyFieldsAreBlockedByBrowserValidation() {
        open(admin);
        AtomicInteger writes = new AtomicInteger();
        page.onRequest(request -> {
            if (request.url().contains("/admin/api/site-pages") && !"GET".equals(request.method())) {
                writes.incrementAndGet();
            }
        });
        page.click("#btnNew");
        waitModalState("open");
        page.click("#pageBtnSave");                          // 푸터 버튼이 form 속성으로 폼에 연결돼 있어야 검증이 동작한다

        // 부정 단언(요청 없음)만으로는 버튼이 폼에 연결되지 않은 경우도 통과하므로, 브라우저가 첫 무효 입력으로 포커스를 옮긴 것을 증거로 삼는다
        assertThat(page.evaluate("() => document.activeElement && document.activeElement.id")).isEqualTo("pageSlug");
        awaitRequestBarrier(page);
        assertThat(writes.get()).isZero();
        assertThat(page.locator("#pageModal.show").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("등록하면 모달이 닫히고 목록에 나타나며 안내가 보이고, 삭제하면 모달이 닫히고 목록에서 사라진다")
    void createAndDeleteCloseTheModal() {
        open(admin);
        page.click("#btnNew");
        waitModalState("open");
        page.fill("#pageSlug", SLUG + "new");
        page.fill("#pageTitle", "PM 새 페이지");
        page.click("#pageContentEditor .ql-editor");
        page.keyboard().type("새 본문");
        page.click("#pageBtnSave");

        waitForText("pageSuccess", "페이지를 등록했습니다");
        waitModalState("closed");
        page.waitForFunction("() => document.getElementById('pageList').textContent.includes('" + SLUG + "new')");
        long id = jdbc.queryForObject("SELECT id FROM site_page WHERE slug = ?", Long.class, SLUG + "new");
        assertThat(titleOf(id)).isEqualTo("PM 새 페이지");

        page.onDialog(dialog -> dialog.accept());          // confirm()
        openRow("new", id);
        page.click("#btnDelete");
        waitForText("pageSuccess", "페이지를 삭제했습니다");
        waitModalState("closed");
        page.waitForFunction("() => !document.getElementById('pageList').textContent.includes('" + SLUG + "new')");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site_page WHERE id = ?", Integer.class, id)).isZero();
    }

    @Test
    @DisplayName("미저장 입력이 있으면 닫기 전에 확인하고, 취소하면 모달이 유지되며 확인하면 닫힌다")
    void dirtyCloseAsksForConfirmation() {
        long id = pageRow("dirty", "원래 제목");
        open(admin);
        openRow("dirty", id);
        page.fill("#pageTitle", "바꾸는 중");

        AtomicInteger dismissed = new AtomicInteger();
        page.onceDialog(dialog -> {                          // 취소 — 닫힘이 막혀야 한다
            dismissed.incrementAndGet();
            dialog.dismiss();
        });
        page.click("#btnModalClose");
        // confirm()은 닫기 처리 안에서 동기적으로 뜨고 핸들러가 취소할 때까지 클릭이 끝나지 않는다 — 취소된 시점에 결과는 확정돼 있다
        page.waitForCondition(() -> dismissed.get() == 1);
        assertThat(page.locator("#pageModal.show").count()).as("확인을 취소하면 모달이 유지된다").isEqualTo(1);
        assertThat(page.inputValue("#pageTitle")).isEqualTo("바꾸는 중");
        waitModalState("open");

        page.onceDialog(dialog -> dialog.accept());          // 확인 — 닫힌다
        page.click("#btnModalClose");
        waitModalState("closed");
        assertThat(titleOf(id)).as("저장되지 않았다").isEqualTo("원래 제목");
    }

    @Test
    @DisplayName("낡은 화면 저장(PUT 409)은 모달을 닫지 않고 입력을 보존하며, 닫고 다시 열어 최신 내용으로 저장하면 성공한다")
    void staleSaveKeepsTheModalAndInput() {
        long id = pageRow("stale", "원래 제목");
        open(admin);
        openRow("stale", id);

        jdbc.update("UPDATE site_page SET version = version + 1 WHERE id = ?", id);   // 다른 관리자가 먼저 저장했다
        page.fill("#pageTitle", "내 수정");
        page.click("#pageBtnSave");

        waitForText("modalAlert", "입력한 내용은 그대로");
        assertThat(page.locator("#pageModal.show").count()).as("409여도 모달은 유지된다").isEqualTo(1);
        assertThat(page.inputValue("#pageTitle")).as("입력이 보존된다").isEqualTo("내 수정");
        assertThat(titleOf(id)).isEqualTo("원래 제목");

        page.onceDialog(dialog -> dialog.accept());          // 미저장 확인
        page.click("#btnModalClose");
        waitModalState("closed");
        openRow("stale", id);                                // 최신 version을 새로 읽는다
        page.fill("#pageTitle", "내 수정 2");
        page.click("#pageBtnSave");
        waitForText("pageSuccess", "페이지를 저장했습니다");
        waitModalState("closed");
        assertThat(titleOf(id)).isEqualTo("내 수정 2");
    }

    @Test
    @DisplayName("PUT 409 뒤 목록 재조회가 지연돼도 ×·[닫기]가 활성이고 입력이 남으며, 닫았다 다시 연 뒤 시작한 새 저장의 잠금을 이전 요청이 풀지 못한다")
    void putConflictDoesNotLockAndDoesNotReleaseTheNextLock() {
        long id = pageRow("race", "원래 제목");
        open(admin);
        openRow("race", id);
        AtomicReference<Route> heldList = new AtomicReference<>();
        AtomicReference<Route> heldWrite = new AtomicReference<>();
        AtomicBoolean holdWrites = new AtomicBoolean(false);
        holdRoutes(heldList, heldWrite, holdWrites);

        jdbc.update("UPDATE site_page SET version = version + 1 WHERE id = ?", id);
        page.fill("#pageTitle", "A의 수정");
        page.click("#pageBtnSave");
        waitForText("modalAlert", "입력한 내용은 그대로");
        page.waitForCondition(() -> heldList.get() != null);   // 409 뒤 목록 GET이 붙잡혀 있다

        assertThat(page.isDisabled("#btnModalClose")).as("재조회가 지연돼도 [닫기]는 활성").isFalse();
        assertThat(page.isDisabled("#btnModalCloseX")).as("×도 활성").isFalse();
        assertThat(page.inputValue("#pageTitle")).as("입력이 남는다").isEqualTo("A의 수정");

        page.onDialog(dialog -> dialog.accept());
        page.click("#btnModalClose");
        waitModalState("closed");
        openRow("race", id);                                  // 닫았다 다시 연다 — 최신 version
        page.fill("#pageTitle", "B의 수정");
        holdWrites.set(true);
        page.click("#pageBtnSave");                           // 새 저장 B를 시작해 붙잡아 둔다
        page.waitForCondition(() -> heldWrite.get() != null);

        resumeListAndWaitRendered(heldList.get());            // 이전 요청 A의 목록 GET이 이제 끝나고 그 후속까지 돌았다
        assertThat(page.isDisabled("#btnModalClose")).as("B가 진행 중이면 [닫기] 잠금이 유지된다").isTrue();
        assertThat(page.isDisabled("#btnModalCloseX")).as("× 잠금도 유지된다").isTrue();
        assertThat(page.isDisabled("#pageBtnSave")).as("저장 잠금도 유지된다").isTrue();

        heldWrite.get().resume();
        waitForText("pageSuccess", "페이지를 저장했습니다");
        waitModalState("closed");
        assertThat(titleOf(id)).isEqualTo("B의 수정");
    }

    @Test
    @DisplayName("DELETE 409 뒤 목록 재조회가 지연돼도 ×·[닫기]가 활성이고, 닫았다 다시 연 뒤 시작한 새 삭제의 잠금을 이전 요청이 풀지 못한다")
    void deleteConflictDoesNotLockAndDoesNotReleaseTheNextLock() {
        long id = pageRow("delrace", "삭제 경합");
        open(admin);
        page.onDialog(dialog -> dialog.accept());            // confirm()
        openRow("delrace", id);
        AtomicReference<Route> heldList = new AtomicReference<>();
        AtomicReference<Route> heldWrite = new AtomicReference<>();
        AtomicBoolean holdWrites = new AtomicBoolean(false);
        holdRoutes(heldList, heldWrite, holdWrites);

        jdbc.update("UPDATE site_page SET version = version + 1 WHERE id = ?", id);   // 낡은 version으로 삭제 → 409
        page.click("#btnDelete");
        page.waitForCondition(() -> heldList.get() != null);
        assertThat(page.locator("#modalAlert").isVisible()).as("409 안내가 모달 안에 보인다").isTrue();
        assertThat(page.isDisabled("#btnModalClose")).as("재조회가 지연돼도 [닫기]는 활성").isFalse();
        assertThat(page.isDisabled("#btnModalCloseX")).isFalse();

        page.click("#btnModalClose");
        waitModalState("closed");
        openRow("delrace", id);
        holdWrites.set(true);
        page.click("#btnDelete");                             // 새 삭제 B를 시작해 붙잡아 둔다
        page.waitForCondition(() -> heldWrite.get() != null);

        resumeListAndWaitRendered(heldList.get());
        assertThat(page.isDisabled("#btnModalClose")).as("B가 진행 중이면 [닫기] 잠금이 유지된다").isTrue();
        assertThat(page.isDisabled("#btnModalCloseX")).isTrue();
        assertThat(page.isDisabled("#btnDelete")).isTrue();

        heldWrite.get().resume();
        waitForText("pageSuccess", "페이지를 삭제했습니다");
        waitModalState("closed");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site_page WHERE id = ?", Integer.class, id)).isZero();
    }

    /**
     * 붙잡은 목록 GET을 풀고 그 응답이 목록에 그려질 때까지 기다린다. 표지 행을 넣어 두면 {@code renderList}의 {@code replaceChildren}이 지운다 —
     * 그 직후 마이크로태스크에서 호출자({@code await loadList()})의 후속이 끝난다. 표지가 사라진 뒤 한 태스크({@code setTimeout(0)})를 더 기다려 바로 다음
     * 태스크로 미뤄진 잠금 해제 회귀까지 잡는다(그보다 늦게 미뤄진 해제는 잡지 못한다 — PLAN-e2e-browser-speedup.md 구현 결과의 한계).
     */
    private void resumeListAndWaitRendered(Route heldList) {
        page.evaluate("() => { const tr = document.createElement('tr'); tr.id = '__stale-list-marker'; document.getElementById('pageList').appendChild(tr); }");
        heldList.resume();
        page.waitForFunction("() => document.getElementById('__stale-list-marker') === null");
        page.evaluate("() => new Promise(resolve => setTimeout(resolve, 0))");
    }

    /** 목록 GET은 첫 번째만, PUT·DELETE는 holdWrites가 켜진 뒤 첫 번째만 붙잡는다. */
    private void holdRoutes(AtomicReference<Route> heldList, AtomicReference<Route> heldWrite, AtomicBoolean holdWrites) {
        page.route(url -> url.contains("/admin/api/site-pages"), route -> {
            String method = route.request().method();
            boolean listGet = "GET".equals(method) && route.request().url().endsWith("/admin/api/site-pages");
            boolean write = "PUT".equals(method) || "DELETE".equals(method);
            if (listGet && heldList.compareAndSet(null, route)) {
                return;
            }
            if (write && holdWrites.get() && heldWrite.compareAndSet(null, route)) {
                return;
            }
            route.resume();
        });
    }

    @Test
    @DisplayName("상세 조회가 즉시 404로 실패해도(열림 전환 중) 모달이 결국 닫히고 페이지 상단에 안내가 보인다")
    void immediateDetailFailureStillClosesTheModal() {
        pageRow("ghost", "유령");
        open(admin);
        restoreModalTransitions(page);                       // 이 시험은 열림 전환 중의 응답이 조건이다 — 기본으로 꺼 둔 전환을 되살린다
        // 상세 조회(GET /admin/api/site-pages/{id})가 열림 전환(약 300ms)보다 훨씬 빨리 404로 끝나도록 즉시 해결되는 응답으로 바꾼다 —
        // 서버 왕복은 전환이 끝난 뒤에 도착할 수 있어 "전환 중 hide() 무시"를 결정적으로 재현하지 못한다.
        // json()도 즉시 해결되게 덮어써, 클릭부터 closeModal()까지가 마이크로태스크만으로 끝나 열림 전환 앞 구간(배경막 단계)에 고정되게 한다 —
        // 네이티브 본문 소비는 완료 시점이 보장되지 않는다(PLAN-modal-shown-race-test.md R1-1).
        page.evaluate("() => { const orig = window.fetch; window.fetch = function (url, options) {"
                + " if (!(/\\/admin\\/api\\/site-pages\\/\\d+$/.test(String(url)) && !(options && options.method))) { return orig(url, options); }"
                + " const r = new Response('{}', { status: 404 }); r.json = () => Promise.resolve({}); return Promise.resolve(r); }; }");

        row("ghost").click();
        waitForText("pageAlert", "불러오지 못했습니다");
        // 모달 요소의 "숨김" 대기는 쓰지 않는다 — 열림 전환 초기에는 요소가 아직 display:none이라 닫히지 않았는데도 바로 통과한다.
        waitModalState("closed");                            // 전환 중 hide()가 무시되면 open에서 멈춰 시간 초과로 실패한다
    }

    @Test
    @DisplayName("모달이 표시된 뒤·shown 전에 상세 조회가 404로 실패해도 미뤄 둔 닫기(pendingClose)로 모달이 닫히고 페이지 상단에 안내가 보인다")
    void detailFailureWhileModalShowingClosesThroughPendingClose() {
        pageRow("ghost-shown", "유령");
        open(admin);
        restoreModalTransitions(page);                       // 이 시험은 실제 전환 시간(약 300ms) 중의 응답이 조건이다
        failDetailWhenModalShows(page, "/admin/api/site-pages/\\d+$", "#pageModal");

        row("ghost-shown").click();
        waitModalState("closed");                            // 직접 hide()를 부르면 Bootstrap이 무시해 open에 멈춘다 — 시간 초과로 실패
        waitForText("pageAlert", "불러오지 못했습니다");
        assertClosedThroughPendingClose(page);
    }

    @Test
    @DisplayName("상세 조회 중 모달을 닫아도 다음 수정 진입이 막히지 않고, 늦게 온 응답이 새 폼을 덮어쓰지 않는다")
    void closingWhileLoadingDetailDoesNotLockOrClobber() {
        long first = pageRow("slow", "느린 페이지");
        long second = pageRow("quick", "빠른 페이지");
        open(admin);

        holdFirstResponse(page, "/admin/api/site-pages/" + first + "$");   // 첫 상세 응답을 두 번째 폼이 열릴 때까지 붙잡는다
        row("slow").click();
        waitModalState("open");
        page.click("#btnModalClose");                        // 상세가 오기 전에 닫는다(상세 조회 중에는 닫을 수 있다)
        waitModalState("closed");

        // 닫은 뒤 다른 행을 열면 새 상세 조회가 시작되고 편집이 가능하다(loadingDetail 영구 잠금 회귀)
        openRow("quick", second);
        assertThat(page.isDisabled("#pageBtnSave")).as("두 번째 폼은 편집 가능").isFalse();

        releaseHeldResponse(page);                           // 첫 응답이 이제야 도착해 처리까지 끝났다
        assertThat(page.textContent("#formTitle")).as("늦게 온 첫 응답이 폼을 덮지 않았다").contains("#" + second + " 수정");
        assertThat(page.inputValue("#pageTitle")).isEqualTo("빠른 페이지");
    }

    @Test
    @DisplayName("READ 전용 MANAGER는 행을 열어 볼 수 있고 저장은 막히며 [새 페이지]는 보이지 않는다")
    void readOnlyManagerCanOpenButNotSave() {
        long id = pageRow("readonly", "읽기 전용");
        grant("READ");
        open(manager);

        assertThat(page.locator("#btnNew").isVisible()).as("CREATE 권한이 없으면 [새 페이지] 숨김").isFalse();
        openRow("readonly", id);
        assertThat(page.locator("#pageBtnSave").isDisabled()).as("UPDATE 권한이 없으면 저장 비활성").isTrue();
        assertThat(page.locator("#btnDelete").isVisible()).as("DELETE 권한이 없으면 삭제 숨김").isFalse();
    }

    @Test
    @DisplayName("READ+DELETE MANAGER는 수정 권한 없이도 모달의 [삭제]에 도달한다")
    void readDeleteManagerReachesDelete() {
        long id = pageRow("deletable", "삭제 가능");
        grant("READ", "DELETE");
        open(manager);

        openRow("deletable", id);
        assertThat(page.locator("#btnDelete").isVisible()).isTrue();
        assertThat(page.locator("#pageBtnSave").isDisabled()).isTrue();
    }
}
