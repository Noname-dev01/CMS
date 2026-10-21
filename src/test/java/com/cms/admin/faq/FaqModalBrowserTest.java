package com.cms.admin.faq;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionCache;
import com.cms.support.BrowserTestSupport;
import com.cms.support.CmsTestApplication;
import com.cms.support.TestMembers;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.SelectOption;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FAQ 관리 화면의 항목(카테고리·FAQ) 등록·수정 모달(PLAN-admin-form-modal-faq.md)을 <b>실제 브라우저</b>로 고정한다 — MockMvc는 화면 JS·Bootstrap 모달을 재현하지 못한다.
 * 순서 편집(드래그·▲▼·[반영])은 {@code FaqAdminOrderBrowserTest}가 맡고, 이 시험은 모달 고유 동작만 본다:
 * 닫기 정책·브라우저 검증·등록/삭제 뒤 닫힘·409 모달 유지와 입력 보존·재조회 지연·사라진 항목·사라진 카테고리 선택·상세 실패/조회 중 닫기·권한·순서 초안과의 상호 배제·
 * 미저장 입력이 있으면 ×·[닫기]가 확인을 거치고(PLAN-admin-form-dirty-close.md) 변경이 없거나 프로그램이 닫을 때는 확인창이 뜨지 않는다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FaqModalBrowserTest extends BrowserTestSupport {

    @LocalServerPort int port;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PermissionCache cache;

    private Member admin;
    private Member manager;
    private BrowserContext context;
    private Page page;

    private long c1;
    private long c2;
    private long a;
    private long b;

    @BeforeEach
    void setUp() {
        cleanFaq();
        admin = TestMembers.save(memberRepository, "fm-admin", Role.ROLE_ADMIN);
        manager = TestMembers.save(memberRepository, "fm-manager", Role.ROLE_MANAGER);
        enableFormLogin(jdbc, admin);
        enableFormLogin(jdbc, manager);
        context = newContext();
        page = context.newPage();

        c1 = category("카테고리1", 0);
        c2 = category("카테고리2", 1);
        a = faq(c1, "질문A", 0);
        b = faq(c1, "질문B", 1);
    }

    @AfterEach
    void tearDown() {
        context.close();
        cleanFaq();
        TestMembers.delete(jdbc, List.of(admin.getId(), manager.getId()));
        cache.invalidate();
        jdbc.update("DELETE FROM admin_action_log WHERE action_type LIKE 'FAQ_%'");
    }

    private void cleanFaq() {
        jdbc.update("DELETE FROM faq");
        jdbc.update("DELETE FROM faq_category");
        jdbc.update("UPDATE faq_lock SET version = 0");
    }

    private long category(String name, int ord) {
        jdbc.update("INSERT INTO faq_category (name, ord, use_yn, create_date, update_date) VALUES (?, ?, 1, ?, ?)",
                name, ord, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject("SELECT MAX(id) FROM faq_category", Long.class);
    }

    private long faq(long categoryId, String question, int ord) {
        jdbc.update("INSERT INTO faq (category_id, question, answer_html, ord, use_yn, create_date, update_date) VALUES (?, ?, '<p>답</p>', ?, 1, ?, ?)",
                categoryId, question, ord, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject("SELECT MAX(id) FROM faq", Long.class);
    }

    private void grant(String... actions) {
        jdbc.update("DELETE FROM member_permission WHERE member_id = ?", manager.getId());
        for (String action : actions) {
            jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, 'FAQ', ?)", manager.getId(), action);
        }
        cache.invalidate();
    }

    private void open(Member member) {
        loginAsAdmin(page, port, member);
        page.navigate("http://localhost:" + port + "/admin/faq/manage");
        page.waitForFunction("() => document.querySelectorAll('.faq-cat').length > 0 && !document.getElementById('faqTree').textContent.includes('불러오는 중')");
    }

    /** 모달 상태(data-state)가 원하는 값이 될 때까지 기다린다. 요소의 보임 여부는 열림 초기에 거짓으로 통과하므로 쓰지 않는다. */
    private void waitModalState(String state) {
        page.waitForFunction("s => document.getElementById('faqModal').dataset.state === s", state);
    }

    private void openCategoryModal() {
        page.click("#btnCatNew");
        waitModalState("open");
    }

    private void openFaqModal() {
        page.click("#btnFaqNew");
        waitModalState("open");
    }

    private void openCategoryEdit(long id) {
        page.click(".faq-cat[data-cat-id='" + id + "'] .faq-cat-name");
        waitModalState("open");
        page.waitForFunction("id => document.getElementById('catFormTitle').textContent.includes('#' + id)", String.valueOf(id));
    }

    /** FAQ 행을 눌러 모달이 완전히 열리고 상세가 폼에 채워질 때까지 기다린다. */
    private void openFaqEdit(long id) {
        page.click(".faq-row[data-faq-id='" + id + "'] .faq-row-question");
        waitModalState("open");
        page.waitForFunction("id => document.getElementById('faqFormTitle').textContent.includes('#' + id)"
                + " && !document.getElementById('faqFieldset').disabled", String.valueOf(id));
    }

    private void waitForText(String elementId, String text) {
        page.waitForFunction("args => { const e = document.getElementById(args[0]);"
                + " return e && !e.classList.contains('d-none') && e.textContent.includes(args[1]); }", List.of(elementId, text));
    }

    private void waitUntilIdle() {
        page.waitForFunction("() => !document.getElementById('btnReload').disabled");
    }

    private void typeAnswer(String text) {
        page.click("#faqAnswerEditor .ql-editor");
        page.keyboard().type(text);
    }

    private final List<String> dialogMessages = new CopyOnWriteArrayList<>();
    private final AtomicBoolean acceptDialogs = new AtomicBoolean(false);

    /** 이 시험이 뜬 확인창을 세고 {@link #acceptDialogs}에 따라 수락/취소한다. 확인창을 세는 핸들러도 반드시 수락 또는 취소해야 한다. */
    private void trackDialogs() {
        page.onDialog(dialog -> {
            dialogMessages.add(dialog.message());
            if (acceptDialogs.get()) {
                dialog.accept();
            } else {
                dialog.dismiss();
            }
        });
    }

    private long faqWithAnswer(long categoryId, String question, String answerHtml, int ord) {
        jdbc.update("INSERT INTO faq (category_id, question, answer_html, ord, use_yn, create_date, update_date) VALUES (?, ?, ?, ?, 1, ?, ?)",
                categoryId, question, answerHtml, ord, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject("SELECT MAX(id) FROM faq", Long.class);
    }

    @Test
    @DisplayName("모달은 배경 클릭·ESC로 닫히지 않고 ×로만 닫힌다")
    void staticBackdropClosesOnlyByButtons() {
        trackDialogs();
        open(admin);
        openCategoryModal();

        page.keyboard().press("Escape");
        page.mouse().click(5, 5);
        page.waitForTimeout(500);
        assertThat(page.locator("#faqModal.show").count()).as("ESC·배경 클릭으로는 닫히지 않는다").isEqualTo(1);

        page.click("#btnModalCloseX");
        waitModalState("closed");
        assertThat(dialogMessages).as("입력 없는 폼은 확인 없이 닫힌다").isEmpty();
    }

    @Test
    @DisplayName("이름·질문을 비운 채 저장하면 브라우저 검증이 막아 요청이 나가지 않고 모달이 유지된다(카테고리·FAQ 모두)")
    void emptyFieldsAreBlockedByBrowserValidation() {
        trackDialogs();
        open(admin);
        AtomicInteger writes = new AtomicInteger();
        page.onRequest(request -> {
            if (request.url().contains("/admin/api/faq") && !"GET".equals(request.method())) {
                writes.incrementAndGet();
            }
        });

        openCategoryModal();
        page.click("#btnCatSave");                           // 푸터 버튼이 form 속성으로 폼에 연결돼 있어야 검증이 동작한다
        // 부정 단언(요청 없음)만으로는 버튼이 폼에 연결되지 않은 경우도 통과하므로, 브라우저가 첫 무효 입력으로 포커스를 옮긴 것을 증거로 삼는다
        assertThat(page.evaluate("() => document.activeElement && document.activeElement.id")).isEqualTo("catName");
        page.click("#btnModalClose");
        waitModalState("closed");

        openFaqModal();
        page.click("#btnFaqSave");
        assertThat(page.evaluate("() => document.activeElement && document.activeElement.id")).isEqualTo("faqQuestion");
        page.waitForTimeout(500);
        assertThat(writes.get()).as("검증에 걸려 쓰기 요청이 나가지 않았다").isZero();
        assertThat(page.locator("#faqModal.show").count()).isEqualTo(1);
        assertThat(dialogMessages).as("빈 폼은 확인 없이 닫혔다").isEmpty();
    }

    @Test
    @DisplayName("카테고리·FAQ를 등록하면 모달이 닫히고 트리에 나타나며, 삭제하면 모달이 닫히고 사라진다")
    void createAndDeleteCloseTheModal() {
        open(admin);
        openCategoryModal();
        page.fill("#catName", "새카테고리");
        page.click("#btnCatSave");
        waitForText("pageSuccess", "카테고리를 등록했습니다");
        waitModalState("closed");
        page.waitForFunction("() => document.getElementById('faqTree').textContent.includes('새카테고리')");
        waitUntilIdle();

        openFaqModal();
        page.selectOption("#faqCategory", String.valueOf(c2));
        page.fill("#faqQuestion", "새 질문");
        typeAnswer("새 답변");
        page.click("#btnFaqSave");
        waitForText("pageSuccess", "FAQ를 등록했습니다");
        waitModalState("closed");
        page.waitForFunction("() => document.getElementById('faqTree').textContent.includes('새 질문')");
        waitUntilIdle();
        assertThat(jdbc.queryForObject("SELECT category_id FROM faq WHERE question = '새 질문'", Long.class)).isEqualTo(c2);

        page.onDialog(dialog -> dialog.accept());            // confirm()
        long created = jdbc.queryForObject("SELECT id FROM faq WHERE question = '새 질문'", Long.class);
        openFaqEdit(created);
        page.click("#btnFaqDelete");
        waitForText("pageSuccess", "FAQ를 삭제했습니다");
        waitModalState("closed");
        page.waitForFunction("() => !document.getElementById('faqTree').textContent.includes('새 질문')");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM faq WHERE id = ?", Integer.class, created)).isZero();
    }

    @Test
    @DisplayName("낡은 화면 저장(카테고리 PUT 409)은 모달을 닫지 않고 입력을 보존하며, 닫고 다시 열어 최신 내용으로 저장하면 성공한다")
    void staleCategorySaveKeepsTheModalAndInput() {
        trackDialogs();
        acceptDialogs.set(true);
        open(admin);
        openCategoryEdit(c1);

        jdbc.update("UPDATE faq_category SET version = version + 1 WHERE id = ?", c1);   // 다른 관리자가 먼저 저장했다
        page.fill("#catName", "내 수정");
        page.click("#btnCatSave");

        waitForText("modalAlert", "입력한 내용은 그대로");
        assertThat(page.locator("#faqModal.show").count()).as("409여도 모달은 유지된다").isEqualTo(1);
        assertThat(page.inputValue("#catName")).as("입력이 보존된다").isEqualTo("내 수정");
        assertThat(jdbc.queryForObject("SELECT name FROM faq_category WHERE id = ?", String.class, c1)).isEqualTo("카테고리1");
        waitUntilIdle();

        page.click("#btnModalClose");                        // 보존된 입력이 남아 있으므로 닫을 때 확인한다
        waitModalState("closed");
        assertThat(dialogMessages).as("409 뒤 남은 입력은 닫을 때 확인한다").hasSize(1);
        openCategoryEdit(c1);                                // 최신 version을 새로 읽는다
        page.fill("#catName", "내 수정 2");
        page.click("#btnCatSave");
        waitForText("pageSuccess", "카테고리를 저장했습니다");
        waitModalState("closed");
        assertThat(dialogMessages).as("저장 성공 닫기는 확인하지 않는다").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT name FROM faq_category WHERE id = ?", String.class, c1)).isEqualTo("내 수정 2");
    }

    @Test
    @DisplayName("409 뒤 목록 재조회가 지연돼도 ×·[닫기]가 활성이고 입력이 남으며, 재조회가 끝나기 전에는 새 모달이 열리지 않는다")
    void conflictWithHeldReloadStillAllowsClose() {
        trackDialogs();
        acceptDialogs.set(true);
        open(admin);
        openCategoryEdit(c1);
        AtomicReference<Route> heldList = new AtomicReference<>();
        page.route(url -> url.endsWith("/admin/api/faqs"), route -> {
            if ("GET".equals(route.request().method()) && heldList.compareAndSet(null, route)) {
                return;                                      // 409 뒤 재조회(GET)를 붙잡는다
            }
            route.resume();
        });

        jdbc.update("UPDATE faq_category SET version = version + 1 WHERE id = ?", c1);
        page.fill("#catName", "붙잡힌 수정");
        page.click("#btnCatSave");
        page.waitForCondition(() -> heldList.get() != null);
        page.waitForFunction("() => !document.getElementById('modalAlert').classList.contains('d-none')");   // 서버의 409 메시지가 재조회 전에 모달 안에 먼저 보인다

        assertThat(page.isDisabled("#btnModalClose")).as("재조회가 지연돼도 [닫기]는 활성").isFalse();
        assertThat(page.isDisabled("#btnModalCloseX")).as("×도 활성").isFalse();
        assertThat(page.inputValue("#catName")).as("입력이 남는다").isEqualTo("붙잡힌 수정");

        page.click("#btnModalClose");                        // 재조회가 지연돼도 닫을 수 있고, 남은 입력은 확인한다
        waitModalState("closed");
        assertThat(dialogMessages).hasSize(1);
        page.click(".faq-cat[data-cat-id='" + c2 + "'] .faq-cat-name");   // busy 중이라 새 모달은 열리지 않는다
        page.waitForTimeout(500);
        assertThat(page.locator("#faqModal").getAttribute("data-state")).as("재조회가 끝나기 전에는 열리지 않는다").isEqualTo("closed");

        heldList.get().resume();
        waitUntilIdle();
        openCategoryEdit(c2);                                // 재조회가 끝나면 열린다
    }

    @Test
    @DisplayName("열어 둔 항목이 사라진 트리가 도착해도 폼은 생성 모드로 비워지지 않고 안내만 보인다")
    void vanishedItemKeepsTheFormAndShowsNotice() {
        open(admin);
        openFaqEdit(b);
        page.fill("#faqQuestion", "고치는 중");

        jdbc.update("DELETE FROM faq WHERE id = ?", b);      // 다른 관리자가 지웠다
        page.evaluate("() => reload()");                     // 목록 재조회가 지금 도착한 것과 같다

        waitForText("modalAlert", "삭제되었거나");
        assertThat(page.inputValue("#faqQuestion")).as("입력이 그대로 남는다").isEqualTo("고치는 중");
        assertThat(page.textContent("#faqFormTitle")).as("생성 모드(새 FAQ)로 바뀌지 않는다").contains("#" + b);
    }

    @Test
    @DisplayName("상세 조회가 즉시 404로 실패해도(열림 전환 중) 모달이 결국 닫히고, 재조회가 끝난 뒤에도 페이지 상단 안내가 남는다")
    void immediateDetailFailureClosesTheModalAndKeepsTheNotice() {
        trackDialogs();
        open(admin);
        // 상세 조회(GET /admin/api/faqs/{id})가 열림 전환(약 300ms)보다 훨씬 빨리 404로 끝나도록 즉시 해결되는 응답으로 바꾼다 —
        // 서버 왕복은 전환이 끝난 뒤에 도착할 수 있어 "전환 중 hide() 무시"를 결정적으로 재현하지 못한다.
        page.evaluate("() => { const orig = window.fetch; window.fetch = function (url, options) {"
                + " return /\\/admin\\/api\\/faqs\\/\\d+$/.test(String(url)) && !(options && options.method)"
                + " ? Promise.resolve(new Response('{}', { status: 404 })) : orig(url, options); }; }");

        page.click(".faq-row[data-faq-id='" + a + "'] .faq-row-question");
        // 모달 요소의 "숨김" 대기는 쓰지 않는다 — 열림 전환 초기에는 요소가 아직 display:none이라 닫히지 않았는데도 바로 통과한다.
        waitModalState("closed");                            // 전환 중 hide()가 무시되면 open에서 멈춰 시간 초과로 실패한다
        waitForText("pageAlert", "불러오지 못했습니다");
        waitUntilIdle();                                     // 404 뒤 재조회까지 끝났다
        page.waitForTimeout(300);
        assertThat(page.locator("#pageAlert").isVisible()).as("재조회가 안내를 지우지 않았다").isTrue();
        assertThat(dialogMessages).as("프로그램이 닫는 경로(상세 조회 실패)는 미저장 확인을 거치지 않는다").isEmpty();
    }

    @Test
    @DisplayName("상세 조회 중 모달을 닫아도 다음 수정 진입이 막히지 않고, 늦게 온 응답이 새 폼을 덮어쓰지 않는다")
    void closingWhileLoadingDetailDoesNotLockOrClobber() {
        trackDialogs();
        open(admin);
        // 첫 FAQ의 상세 조회(GET /admin/api/faqs/{id})만 1.5초 지연시킨다
        page.evaluate("() => { window.__origFetch = window.fetch;"
                + " window.fetch = function (url, options) {"
                + "   const slow = /\\/admin\\/api\\/faqs\\/" + a + "$/.test(String(url)) && !(options && options.method);"
                + "   return slow ? new Promise(function (resolve) { setTimeout(function () { resolve(window.__origFetch(url, options)); }, 1500); })"
                + "               : window.__origFetch(url, options); }; }");
        page.click(".faq-row[data-faq-id='" + a + "'] .faq-row-question");
        waitModalState("open");
        page.click("#btnModalClose");                        // 상세가 오기 전에 닫는다(상세 조회 중에는 닫을 수 있다)
        waitModalState("closed");

        // 닫은 뒤 다른 FAQ를 열면 새 상세 조회가 시작되고 편집이 가능하다(loadingDetail 영구 잠금 회귀)
        openFaqEdit(b);
        assertThat(page.isDisabled("#faqQuestion")).as("두 번째 폼은 편집 가능").isFalse();

        page.waitForTimeout(2000);                           // 첫 응답이 늦게 도착한다
        assertThat(page.textContent("#faqFormTitle")).as("늦게 온 첫 응답이 폼을 덮지 않았다").contains("#" + b);
        assertThat(page.inputValue("#faqQuestion")).isEqualTo("질문B");
        assertThat(dialogMessages).as("상세 조회 중에는 입력이 없어 닫아도 확인하지 않는다").isEmpty();
    }

    @Test
    @DisplayName("READ 전용 MANAGER는 항목을 열어 볼 수 있고 저장·삭제 버튼과 [새 …]는 보이지 않는다")
    void readOnlyManagerCanOpenButNotSave() {
        grant("READ");
        open(manager);

        assertThat(page.locator("#btnCatNew").isVisible()).as("CREATE 권한이 없으면 [새 카테고리] 숨김").isFalse();
        assertThat(page.locator("#btnFaqNew").isVisible()).as("[새 FAQ] 숨김").isFalse();
        openFaqEdit(a);
        assertThat(page.locator("#btnFaqSave").isVisible()).as("UPDATE 권한이 없으면 저장 숨김").isFalse();
        assertThat(page.locator("#btnFaqDelete").isVisible()).as("DELETE 권한이 없으면 삭제 숨김").isFalse();
    }

    @Test
    @DisplayName("순서 초안이 있는 동안에는 [새 …]가 비활성이고 항목을 눌러도 모달이 열리지 않는다")
    void draftBlocksOpeningTheModal() {
        open(admin);
        page.click(".faq-row[data-faq-id='" + a + "'] button[aria-label='FAQ 아래로']");   // 순서 초안을 만든다
        assertThat(page.isDisabled("#btnCatNew")).isTrue();
        assertThat(page.isDisabled("#btnFaqNew")).isTrue();

        page.click(".faq-row[data-faq-id='" + b + "'] .faq-row-question");
        page.waitForTimeout(500);
        assertThat(page.locator("#faqModal").getAttribute("data-state")).as("초안이 있으면 열리지 않는다").isEqualTo("closed");

        page.click("#btnCancelOrder");
        openFaqEdit(b);                                      // 초안을 취소하면 열린다
    }

    @Test
    @DisplayName("수정 중 선택했던 카테고리가 삭제된 409: 선택이 '삭제된 카테고리' 임시 옵션으로 유지되고, 그 상태의 저장은 요청 없이 막히며, 닫고 다시 열면 저장된다")
    void deletedCategorySelectionIsPreservedInEditMode() {
        long only = category("곧 지워질 카테고리", 2);
        long moved = faq(only, "이동될 질문", 0);
        trackDialogs();
        acceptDialogs.set(true);
        open(admin);
        openFaqEdit(moved);

        // 다른 관리자가 항목을 c2로 옮기고 빈 카테고리를 지웠다 — 열려 있는 폼은 옛 소속(only)을 기준으로 한다
        jdbc.update("UPDATE faq SET category_id = ? WHERE id = ?", c2, moved);
        jdbc.update("DELETE FROM faq_category WHERE id = ?", only);
        jdbc.update("UPDATE faq_lock SET version = version + 1");
        page.fill("#faqQuestion", "내 수정");
        page.click("#btnFaqSave");

        waitForText("modalAlert", "입력한 내용은 그대로");
        waitUntilIdle();
        assertThat(page.inputValue("#faqCategory")).as("선택이 다른 카테고리로 바뀌지 않았다").isEqualTo(String.valueOf(only));
        assertThat(page.locator("#faqCategory option[data-missing='true']").count()).as("삭제된 카테고리 임시 옵션").isEqualTo(1);
        assertThat(page.inputValue("#faqQuestion")).isEqualTo("내 수정");

        AtomicInteger writes = new AtomicInteger();
        page.onRequest(request -> {
            if ("PUT".equals(request.method()) && request.url().contains("/admin/api/faqs/")) {
                writes.incrementAndGet();
            }
        });
        page.click("#btnFaqSave");
        waitForText("modalAlert", "닫은 뒤 이 FAQ를 다시 선택");
        page.waitForTimeout(300);
        assertThat(writes.get()).as("삭제된 카테고리로는 요청 없이 막힌다").isZero();

        page.click("#btnModalClose");                        // 보존된 입력이 남아 있으므로 닫을 때 확인한다
        waitModalState("closed");
        assertThat(dialogMessages).hasSize(1);
        openFaqEdit(moved);                                  // 최신 소속(c2)으로 다시 읽는다
        assertThat(page.inputValue("#faqCategory")).isEqualTo(String.valueOf(c2));
        page.fill("#faqQuestion", "내 수정 2");
        page.click("#btnFaqSave");
        waitForText("pageSuccess", "FAQ를 저장했습니다");
        waitModalState("closed");
        assertThat(jdbc.queryForObject("SELECT question FROM faq WHERE id = ?", String.class, moved)).isEqualTo("내 수정 2");
    }

    @Test
    @DisplayName("등록 중 선택했던 카테고리가 삭제되면 임시 옵션으로 선택이 유지되고 제출이 요청 없이 막히며, 존재하는 다른 카테고리를 고르면 저장된다")
    void deletedCategorySelectionIsPreservedInCreateMode() {
        long temp = category("곧 지워질 카테고리", 2);
        open(admin);
        openFaqModal();
        page.selectOption("#faqCategory", String.valueOf(temp));
        page.fill("#faqQuestion", "새 질문");
        typeAnswer("새 답변");

        jdbc.update("DELETE FROM faq_category WHERE id = ?", temp);   // 다른 관리자가 지웠다
        page.evaluate("() => reload()");
        page.waitForFunction("() => document.querySelectorAll('#faqCategory option[data-missing=\"true\"]').length === 1");
        assertThat(page.inputValue("#faqCategory")).as("다른 카테고리가 자동 선택되지 않는다").isEqualTo(String.valueOf(temp));

        AtomicInteger writes = new AtomicInteger();
        page.onRequest(request -> {
            if ("POST".equals(request.method()) && request.url().endsWith("/admin/api/faqs")) {
                writes.incrementAndGet();
            }
        });
        page.click("#btnFaqSave");
        waitForText("modalAlert", "존재하는 다른 카테고리를 선택");
        page.waitForTimeout(300);
        assertThat(writes.get()).as("삭제된 카테고리로는 요청 없이 막힌다").isZero();
        assertThat(page.inputValue("#faqQuestion")).as("입력은 유지된다").isEqualTo("새 질문");

        page.selectOption("#faqCategory", String.valueOf(c1));
        page.click("#btnFaqSave");
        waitForText("pageSuccess", "FAQ를 등록했습니다");
        waitModalState("closed");
        assertThat(jdbc.queryForObject("SELECT category_id FROM faq WHERE question = '새 질문'", Long.class)).isEqualTo(c1);
    }

    @Test
    @DisplayName("미저장 입력이 있으면 ×·[닫기]가 확인하고(카테고리·FAQ 모두), 취소하면 모달과 입력이 유지되며 수락하면 닫힌다")
    void dirtyCloseAsksForConfirmation() {
        trackDialogs();
        open(admin);

        openCategoryModal();
        page.fill("#catName", "typed-category");
        acceptDialogs.set(false);
        page.click("#btnModalClose");                        // 취소 — 닫힘이 막혀야 한다
        page.waitForTimeout(500);
        assertThat(dialogMessages).hasSize(1);
        assertThat(dialogMessages.get(0)).contains("저장하지 않은 변경이 있습니다");
        assertThat(page.locator("#faqModal.show").count()).as("취소하면 모달이 유지된다").isEqualTo(1);
        assertThat(page.inputValue("#catName")).as("입력이 보존된다").isEqualTo("typed-category");
        page.click("#btnModalCloseX");                       // × 도 같은 확인을 거친다
        page.waitForTimeout(500);
        assertThat(dialogMessages).hasSize(2);
        acceptDialogs.set(true);
        page.click("#btnModalClose");                        // 수락 — 닫힌다
        waitModalState("closed");
        assertThat(dialogMessages).hasSize(3);

        openFaqModal();
        page.fill("#faqQuestion", "typed-question");
        acceptDialogs.set(false);
        page.click("#btnModalCloseX");
        page.waitForTimeout(500);
        assertThat(dialogMessages).hasSize(4);
        assertThat(page.inputValue("#faqQuestion")).as("입력이 보존된다").isEqualTo("typed-question");
        acceptDialogs.set(true);
        page.click("#btnModalCloseX");
        waitModalState("closed");
        assertThat(dialogMessages).hasSize(5);

        openCategoryModal();                                 // 닫힌 뒤 다시 열면 이전 입력도 이전 기준값도 남지 않는다
        assertThat(page.inputValue("#catName")).isEmpty();
        page.click("#btnModalClose");
        waitModalState("closed");
        assertThat(dialogMessages).as("입력 없이 닫으면 확인하지 않는다").hasSize(5);
    }

    @Test
    @DisplayName("변경이 없으면 확인하지 않는다 — 입력 없는 등록, 정규화되는 답변의 수정 모달, 바꿨다 되돌린 수정(카테고리·FAQ 모두)")
    void unchangedFormClosesWithoutConfirmation() {
        // 공백 연속·nbsp·끝 빈 문단은 편집기가 로드할 때 정규화하는 대표 입력이다 — 기준값이 정규화 이후여야 거짓 양성이 없다
        long normalized = faqWithAnswer(c1, "정규화", "<p>두  칸&nbsp;&nbsp;공백</p><ul><li>항목</li></ul><p><br></p>", 2);
        trackDialogs();
        open(admin);

        openCategoryModal();
        page.click("#btnModalClose");
        waitModalState("closed");
        openFaqModal();
        page.click("#btnModalClose");
        waitModalState("closed");

        openCategoryEdit(c1);
        page.click("#btnModalCloseX");
        waitModalState("closed");
        openFaqEdit(normalized);
        page.click("#btnModalCloseX");
        waitModalState("closed");

        openCategoryEdit(c1);
        page.fill("#catName", "다른 이름");
        page.fill("#catName", "카테고리1");                  // 되돌리면 변경이 아니다
        page.click("#btnModalClose");
        waitModalState("closed");
        openFaqEdit(normalized);
        page.fill("#faqQuestion", "다른 질문");
        page.fill("#faqQuestion", "정규화");
        page.click("#btnModalClose");
        waitModalState("closed");

        assertThat(dialogMessages).as("변경이 없는 닫기는 확인창 0회").isEmpty();
    }

    @Test
    @DisplayName("한 종류를 닫고 다른 종류를 열어도 이전 기준값이 남지 않는다 — FAQ→카테고리 수정, 카테고리→FAQ 수정, 더러운 FAQ를 닫은 뒤의 카테고리 수정")
    void baselineDoesNotLeakAcrossKinds() {
        trackDialogs();
        open(admin);

        openFaqModal();                                      // FAQ 모드로 한 번 열었다 닫으면 modalMode가 'faq'로 남는다
        page.click("#btnModalClose");
        waitModalState("closed");
        openCategoryEdit(c1);                                // 필드를 채운 뒤 openModal("category")가 modalMode를 바꾼다 — 기준값은 kind로 정해져야 한다
        page.click("#btnModalClose");
        waitModalState("closed");

        openCategoryModal();
        page.click("#btnModalClose");
        waitModalState("closed");
        openFaqEdit(a);
        page.click("#btnModalClose");
        waitModalState("closed");
        assertThat(dialogMessages).as("종류를 오가도 무변경 닫기는 확인창 0회").isEmpty();

        acceptDialogs.set(true);
        openFaqModal();
        page.fill("#faqQuestion", "더러운 FAQ");
        page.click("#btnModalClose");                        // 수락하고 닫는다
        waitModalState("closed");
        assertThat(dialogMessages).hasSize(1);
        openCategoryEdit(c1);
        page.click("#btnModalClose");
        waitModalState("closed");
        assertThat(dialogMessages).as("더러운 FAQ를 버린 뒤 연 카테고리 수정은 깨끗하다").hasSize(1);

        // 반대 방향(거짓 음성): 직전 종류가 남아 있어도 새로 연 폼의 변경은 자기 종류의 기준값으로 감지돼야 한다
        openFaqModal();                                      // modalMode를 'faq'로 남기고 닫는다
        page.click("#btnModalClose");
        waitModalState("closed");
        openCategoryEdit(c1);
        page.fill("#catName", "바뀐 이름");
        page.click("#btnModalClose");
        waitModalState("closed");
        assertThat(dialogMessages).as("FAQ 직후 연 카테고리 수정의 변경은 확인한다").hasSize(2);

        openCategoryEdit(c1);                                // modalMode를 'category'로 남기고 닫는다
        page.click("#btnModalClose");
        waitModalState("closed");
        openFaqEdit(a);
        page.fill("#faqQuestion", "바뀐 질문");
        page.click("#btnModalClose");
        waitModalState("closed");
        assertThat(dialogMessages).as("카테고리 직후 연 FAQ 수정의 변경은 확인한다").hasSize(3);
    }

    @Test
    @DisplayName("카테고리의 이름·노출·표시 대상, FAQ의 카테고리·질문·노출·답변 중 하나라도 바뀌면 변경으로 본다")
    void everyInputCountsAsAChange() {
        trackDialogs();
        acceptDialogs.set(true);
        open(admin);
        assertThat((Integer) page.evaluate("() => document.getElementById('catSiteScope').options.length"))
                .as("표시 대상 시험에 필요한 사이트 옵션").isGreaterThan(1);

        List<String> categoryNames = List.of("이름", "노출", "표시 대상");
        List<Runnable> categoryActions = List.of(
                () -> page.fill("#catName", "x"),
                () -> page.uncheck("#catUse"),
                () -> page.selectOption("#catSiteScope", new SelectOption().setIndex(1)));
        for (int i = 0; i < categoryActions.size(); i++) {
            int before = dialogMessages.size();
            openCategoryModal();
            categoryActions.get(i).run();
            page.click("#btnModalClose");
            waitModalState("closed");
            assertThat(dialogMessages).as("카테고리 " + categoryNames.get(i) + " 변경은 닫을 때 확인한다").hasSize(before + 1);
        }

        List<String> faqNames = List.of("카테고리", "질문", "노출", "답변");
        List<Runnable> faqActions = List.of(
                () -> page.selectOption("#faqCategory", String.valueOf(c2)),
                () -> page.fill("#faqQuestion", "x"),
                () -> page.uncheck("#faqUse"),
                () -> typeAnswer("답변 입력"));
        for (int i = 0; i < faqActions.size(); i++) {
            int before = dialogMessages.size();
            openFaqModal();
            faqActions.get(i).run();
            page.click("#btnModalClose");
            waitModalState("closed");
            assertThat(dialogMessages).as("FAQ " + faqNames.get(i) + " 변경은 닫을 때 확인한다").hasSize(before + 1);
        }
    }

    @Test
    @DisplayName("수정 모달에서 답변만 바꿔도 변경으로 본다")
    void answerChangeInEditModeCountsAsAChange() {
        trackDialogs();
        acceptDialogs.set(true);
        open(admin);
        openFaqEdit(a);

        typeAnswer("추가 답변");                              // 다른 입력은 그대로 — 편집기 변경만으로 확인한다
        page.click("#btnModalClose");
        waitModalState("closed");

        assertThat(dialogMessages).hasSize(1);
    }

    @Test
    @DisplayName("저장·삭제 성공으로 닫을 때는 미저장 확인을 거치지 않는다(삭제 확인만 한 번씩 뜬다)")
    void programmaticClosesSkipTheDiscardConfirmation() {
        trackDialogs();
        acceptDialogs.set(true);
        open(admin);

        openCategoryModal();
        page.fill("#catName", "저장될 카테고리");
        page.click("#btnCatSave");
        waitForText("pageSuccess", "카테고리를 등록했습니다");
        waitModalState("closed");
        waitUntilIdle();
        assertThat(dialogMessages).as("카테고리 등록 성공 닫기").isEmpty();

        openFaqModal();
        page.fill("#faqQuestion", "저장될 질문");
        typeAnswer("저장될 답변");
        page.click("#btnFaqSave");
        waitForText("pageSuccess", "FAQ를 등록했습니다");
        waitModalState("closed");
        waitUntilIdle();
        assertThat(dialogMessages).as("FAQ 등록 성공 닫기").isEmpty();

        long saved = jdbc.queryForObject("SELECT id FROM faq WHERE question = '저장될 질문'", Long.class);
        openFaqEdit(saved);
        page.fill("#faqQuestion", "미저장 수정");              // 미저장 입력이 있어도 삭제 성공 닫기는 확인하지 않는다
        page.click("#btnFaqDelete");
        waitForText("pageSuccess", "FAQ를 삭제했습니다");
        waitModalState("closed");
        waitUntilIdle();
        assertThat(dialogMessages).as("삭제 확인 한 번뿐").hasSize(1);
        assertThat(dialogMessages.get(0)).contains("삭제하시겠습니까");

        long createdCategory = jdbc.queryForObject("SELECT id FROM faq_category WHERE name = '저장될 카테고리'", Long.class);
        openCategoryEdit(createdCategory);
        page.fill("#catName", "미저장 이름");
        page.click("#btnCatDelete");
        waitForText("pageSuccess", "카테고리를 삭제했습니다");
        waitModalState("closed");
        assertThat(dialogMessages).as("카테고리 삭제 확인 한 번 더").hasSize(2);
    }
}
