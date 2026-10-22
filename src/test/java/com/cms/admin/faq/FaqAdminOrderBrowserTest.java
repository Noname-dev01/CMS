package com.cms.admin.faq;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FAQ 관리 화면의 순서 편집 JS를 <b>실제 브라우저</b>로 고정한다(PLAN-faq.md 쟁점 13). 네이티브 드래그·▲▼가 서버를 호출하지 않는 초안만 바꾸고,
 * [순서 반영]·[취소]·낡은 화면 409·요청 중 잠금(R3-1)·상세 로딩 중 폼 잠금(R4-2)·쓰기 성공 뒤 재조회 실패 복구(R4-1)가 계획대로 동작하는지 본다.
 * 서버 규칙은 {@code FaqApiIntegrationTest}가 맡는다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FaqAdminOrderBrowserTest extends BrowserTestSupport {

    @LocalServerPort int port;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;

    private Member admin;
    private BrowserContext context;
    private Page page;

    private long c1;
    private long c2;
    private long a;
    private long b;
    private long d;
    private long x;

    @BeforeEach
    void setUp() {
        cleanFaq();
        admin = TestMembers.save(memberRepository, "fb-admin", Role.ROLE_ADMIN);
        enableFormLogin(jdbc, admin);
        context = newContext();
        page = context.newPage();
        loginAsAdmin(page, port, admin);

        c1 = category("카테고리1", 0);
        c2 = category("카테고리2", 1);
        a = faq(c1, "질문A", 0);
        b = faq(c1, "질문B", 1);
        d = faq(c1, "질문D", 2);
        x = faq(c2, "질문X", 0);
    }

    @AfterEach
    void tearDown() {
        context.close();
        cleanFaq();
        TestMembers.delete(jdbc, List.of(admin.getId()));
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

    private void openManage() {
        page.navigate("http://localhost:" + port + "/admin/faq/manage");
        waitForLoaded();
    }

    private void waitForLoaded() {
        page.waitForFunction("() => document.querySelectorAll('.faq-cat').length > 0 && !document.getElementById('faqTree').textContent.includes('불러오는 중')");
    }

    private List<Long> viewFaqOrder(long categoryId) {
        @SuppressWarnings("unchecked")
        List<Object> ids = (List<Object>) page.evaluate("catId => Array.from(document.querySelectorAll('.faq-row[data-cat=\"' + catId + '\"]')).map(r => Number(r.dataset.faqId))",
                String.valueOf(categoryId));
        return ids.stream().map(v -> ((Number) v).longValue()).toList();
    }

    private List<Long> viewCategoryOrder() {
        @SuppressWarnings("unchecked")
        List<Object> ids = (List<Object>) page.evaluate("() => Array.from(document.querySelectorAll('.faq-cat')).map(r => Number(r.dataset.catId))");
        return ids.stream().map(v -> ((Number) v).longValue()).toList();
    }

    private List<Long> dbFaqOrder(long categoryId) {
        return jdbc.queryForList("SELECT id FROM faq WHERE category_id = ? ORDER BY ord, id", Long.class, categoryId);
    }

    private long version() {
        return jdbc.queryForObject("SELECT version FROM faq_lock WHERE id = 1", Long.class);
    }

    /** {@code <fieldset disabled>} 자체는 Playwright의 isDisabled()가 폼 컨트롤로 보지 않아 DOM 속성을 직접 읽는다. */
    private boolean fieldsetDisabled(String id) {
        return (Boolean) page.evaluate("id => document.getElementById(id).disabled", id);
    }

    private boolean draftBarVisible() {
        return (Boolean) page.evaluate("() => !document.getElementById('draftBar').classList.contains('d-none')");
    }

    private void waitForSuccess(String text) {
        page.waitForFunction("text => { const e = document.getElementById('pageSuccess');"
                + " return e && !e.classList.contains('d-none') && e.textContent.includes(text); }", text);
    }

    private void waitForError(String text) {
        page.waitForFunction("text => { const e = document.getElementById('pageAlert');"
                + " return e && !e.classList.contains('d-none') && e.textContent.includes(text); }", text);
    }

    /** 모달 상태(data-state)가 원하는 값이 될 때까지 기다린다. 요소의 보임 여부는 열림 초기에 거짓으로 통과하므로 쓰지 않는다. */
    private void waitModalState(String state) {
        page.waitForFunction("s => document.getElementById('faqModal').dataset.state === s", state);
    }

    /** [새 카테고리]를 눌러 모달이 완전히 열릴 때까지 기다린다. */
    private void openCategoryModal() {
        page.click("#btnCatNew");
        waitModalState("open");
    }

    /** 모달이 떠 있는 동안의 오류는 모달 안(modalAlert)에 보인다. */
    private void waitForModalError(String text) {
        page.waitForFunction("text => { const e = document.getElementById('modalAlert');"
                + " return e && !e.classList.contains('d-none') && e.textContent.includes(text); }", text);
    }

    private void waitUntilIdle() {
        page.waitForFunction("() => !document.getElementById('btnReload').disabled");
    }

    private String handle(long faqId) {
        return ".faq-row[data-faq-id='" + faqId + "'] .faq-handle";
    }

    private String row(long faqId) {
        return ".faq-row[data-faq-id='" + faqId + "']";
    }

    @Test
    @DisplayName("FAQ를 끌어 놓으면 서버 호출 없이 초안만 바뀌고(DB 불변), [순서 반영]을 눌러야 저장된다 — 끌어 내리면 대상 위치에 들어간다")
    void dragFaq_makesDraft_thenApply() {
        openManage();
        long before = version();

        page.dragAndDrop(handle(a), row(d));

        assertThat(viewFaqOrder(c1)).as("초안 순서").containsExactly(b, d, a);
        assertThat(draftBarVisible()).isTrue();
        assertThat(dbFaqOrder(c1)).as("저장 전에는 DB 불변").containsExactly(a, b, d);
        assertThat(version()).isEqualTo(before);

        page.click("#btnApplyOrder");
        waitForSuccess("순서를 반영했습니다");
        waitUntilIdle();

        assertThat(dbFaqOrder(c1)).containsExactly(b, d, a);
        assertThat(version()).isEqualTo(before + 1);
        assertThat(viewFaqOrder(c1)).containsExactly(b, d, a);
        assertThat(draftBarVisible()).isFalse();
    }

    @Test
    @DisplayName("카테고리를 끌어 순서를 바꿔 반영하면 카테고리 순서가 저장되고, 다른 카테고리의 FAQ 위에는 놓이지 않는다")
    void dragCategory_andCrossCategoryDropIsIgnored() {
        openManage();

        // 다른 카테고리의 FAQ 행으로는 놓을 수 없다
        page.dragAndDrop(handle(a), row(x));
        assertThat(draftBarVisible()).as("카테고리 간 FAQ 이동 드롭은 무시").isFalse();
        assertThat(viewFaqOrder(c1)).containsExactly(a, b, d);

        page.dragAndDrop(".faq-cat[data-cat-id='" + c2 + "'] .faq-cat-head .faq-handle", ".faq-cat[data-cat-id='" + c1 + "']");
        assertThat(viewCategoryOrder()).containsExactly(c2, c1);

        page.click("#btnApplyOrder");
        waitForSuccess("순서를 반영했습니다");
        waitUntilIdle();

        assertThat(jdbc.queryForList("SELECT id FROM faq_category ORDER BY ord", Long.class)).containsExactly(c2, c1);
    }

    @Test
    @DisplayName("▲▼ 버튼도 같은 초안을 만들고, 양 끝에서는 비활성이며, [취소]는 서버 순서로 되돌린다")
    void arrowButtons_andCancel() {
        openManage();

        assertThat(page.locator(row(a) + " button[aria-label='FAQ 위로']").isDisabled()).as("맨 위는 ▲ 비활성").isTrue();
        assertThat(page.locator(row(d) + " button[aria-label='FAQ 아래로']").isDisabled()).as("맨 아래는 ▼ 비활성").isTrue();

        page.click(row(a) + " button[aria-label='FAQ 아래로']");
        assertThat(viewFaqOrder(c1)).containsExactly(b, a, d);
        assertThat(draftBarVisible()).isTrue();

        // 초안이 있는 동안 폼은 잠긴다
        assertThat(fieldsetDisabled("catFieldset")).isTrue();
        assertThat(fieldsetDisabled("faqFieldset")).isTrue();
        assertThat(page.locator("#btnReload").isDisabled()).isTrue();

        page.click("#btnCancelOrder");

        assertThat(viewFaqOrder(c1)).containsExactly(a, b, d);
        assertThat(draftBarVisible()).isFalse();
        assertThat(fieldsetDisabled("faqFieldset")).isFalse();
        assertThat(dbFaqOrder(c1)).containsExactly(a, b, d);
    }

    @Test
    @DisplayName("다른 관리자가 구조를 바꾼 뒤 낡은 초안을 반영하면 409 안내와 함께 초안이 버려지고 최신 목록이 보인다")
    void staleDraft_409_discardsDraftAndReloads() {
        openManage();
        page.click(row(a) + " button[aria-label='FAQ 아래로']");
        assertThat(draftBarVisible()).isTrue();

        // 다른 관리자가 FAQ를 하나 지우고 구조 버전을 올렸다
        jdbc.update("DELETE FROM faq WHERE id = ?", d);
        jdbc.update("UPDATE faq_lock SET version = version + 1");

        page.click("#btnApplyOrder");
        waitForError("변경되었습니다");
        waitUntilIdle();

        assertThat(draftBarVisible()).as("초안은 버려진다").isFalse();
        assertThat(viewFaqOrder(c1)).as("최신 서버 목록").containsExactly(a, b);
        assertThat(dbFaqOrder(c1)).as("낡은 화면이 저장하지 못했다").containsExactly(a, b);
    }

    @Test
    @DisplayName("[순서 반영] 응답을 기다리는 동안에는 드래그·▲▼가 무시되고(핸들러 안에서도), 응답이 오면 요청에 없던 변경이 생기지 않는다")
    void whileApplying_dragIsIgnored() {
        AtomicReference<Route> held = new AtomicReference<>();
        page.route("**/admin/api/faqs/order", held::set);
        openManage();
        page.click(row(a) + " button[aria-label='FAQ 아래로']");

        page.click("#btnApplyOrder");
        page.waitForCondition(() -> held.get() != null);
        assertThat(held.get()).as("반영 요청이 가로채졌다").isNotNull();

        // 요청 대기 중 — 끌기·▲▼ 모두 무시된다
        page.dragAndDrop(handle(d), row(b));
        page.click(row(d) + " button[aria-label='FAQ 위로']", new Page.ClickOptions().setForce(true));
        assertThat(viewFaqOrder(c1)).as("대기 중 조작은 무시").containsExactly(b, a, d);
        assertThat(page.locator("#btnApplyOrder").isDisabled()).isTrue();

        held.get().resume();
        waitForSuccess("순서를 반영했습니다");
        waitUntilIdle();

        assertThat(dbFaqOrder(c1)).containsExactly(b, a, d);
        assertThat(viewFaqOrder(c1)).containsExactly(b, a, d);
        assertThat(draftBarVisible()).isFalse();
    }

    @Test
    @DisplayName("FAQ 상세를 불러오는 동안에는 폼과 편집기가 통째로 잠기고, 응답이 오면 질문·답변이 채워지며 다시 편집할 수 있다")
    void whileLoadingDetail_formAndEditorAreLocked() {
        AtomicReference<Route> held = new AtomicReference<>();
        page.route("**/admin/api/faqs/" + b, held::set);
        openManage();

        page.click(row(b) + " .faq-row-question");
        page.waitForCondition(() -> held.get() != null);
        assertThat(held.get()).as("상세 요청이 가로채졌다").isNotNull();

        assertThat(fieldsetDisabled("faqFieldset")).as("상세 로딩 중 폼 잠금").isTrue();
        assertThat(page.getAttribute("#faqAnswerEditor .ql-editor", "contenteditable")).as("편집기 잠금").isEqualTo("false");

        held.get().resume();
        page.waitForFunction("() => !document.getElementById('faqFieldset').disabled");

        assertThat(page.inputValue("#faqQuestion")).isEqualTo("질문B");
        assertThat(page.textContent("#faqAnswerEditor .ql-editor")).contains("답");
        assertThat(page.getAttribute("#faqAnswerEditor .ql-editor", "contenteditable")).isEqualTo("true");
        assertThat(page.textContent("#faqFormTitle")).contains("#" + b);
    }

    @Test
    @DisplayName("등록은 성공했는데 목록 재조회만 실패하면 '저장은 완료됐지만 …' 안내와 [새로고침]만 남고(쓰기 잠금), 새로고침하면 복구되며 생성은 1건뿐이다")
    void writeSucceeds_reloadFails_recoversWithoutDuplicate() {
        AtomicBoolean failNextList = new AtomicBoolean();
        page.route("**/admin/api/faqs", route -> {
            if ("GET".equals(route.request().method()) && failNextList.getAndSet(false)) {
                route.abort();
            } else {
                route.resume();
            }
        });
        openManage();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM faq_category", Integer.class);

        failNextList.set(true);
        openCategoryModal();
        page.fill("#catName", "새카테고리");
        page.click("#btnCatSave");
        waitForError("저장은 완료됐지만");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM faq_category", Integer.class)).as("쓰기는 이미 커밋").isEqualTo(before + 1);
        assertThat(fieldsetDisabled("catFieldset")).as("낡은 화면이라 쓰기 잠금").isTrue();
        assertThat(page.locator("#btnReload").isDisabled()).as("새로고침은 허용").isFalse();

        page.click("#btnReload");
        page.waitForFunction("() => !document.getElementById('catFieldset').disabled");

        assertThat(page.textContent("#faqTree")).contains("새카테고리");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM faq_category", Integer.class)).as("재시도로 중복 생성되지 않았다").isEqualTo(before + 1);
    }

    @Test
    @DisplayName("카테고리를 바꾼 채 저장했다가 400을 받아도 폼의 카테고리 선택이 저장된 값으로 되돌아가지 않아, 고쳐서 다시 저장하면 이동이 이뤄진다")
    void categorySelection_survivesRerender_afterRejectedSave() {
        openManage();
        page.click(row(b) + " .faq-row-question");
        page.waitForFunction("() => !document.getElementById('faqFieldset').disabled && document.getElementById('faqQuestion').value !== ''");
        assertThat(page.inputValue("#faqCategory")).as("상세를 채울 때는 저장된 카테고리").isEqualTo(String.valueOf(c1));

        page.selectOption("#faqCategory", String.valueOf(c2));
        page.fill("#faqAnswerEditor .ql-editor", "");           // 보이는 내용이 없는 답변 → 서버 400
        page.click("#btnFaqSave");
        waitForModalError("본문은 공백일 수 없습니다");
        page.waitForFunction("() => !document.getElementById('faqFieldset').disabled");

        assertThat(page.inputValue("#faqCategory")).as("거절된 저장 뒤에도 선택이 유지된다").isEqualTo(String.valueOf(c2));
        assertThat(jdbc.queryForObject("SELECT category_id FROM faq WHERE id = ?", Long.class, b)).as("거절됐으니 이동하지 않았다").isEqualTo(c1);

        page.fill("#faqAnswerEditor .ql-editor", "고친 답변");
        page.click("#btnFaqSave");
        waitForSuccess("FAQ를 저장했습니다");
        waitUntilIdle();

        assertThat(jdbc.queryForObject("SELECT category_id FROM faq WHERE id = ?", Long.class, b)).as("다시 저장하면 이동한다").isEqualTo(c2);
        assertThat(dbFaqOrder(c2)).containsExactly(x, b);
        assertThat(dbFaqOrder(c1)).as("원래 카테고리는 재번호").containsExactly(a, d);
    }

    @Test
    @DisplayName("성공(201) 응답의 본문이 깨져 읽지 못해도 성공 처리가 예외 없이 끝나 목록을 다시 읽고 화면이 잠기지 않는다")
    void successWithUnreadableBody_doesNotLockScreen() {
        page.route("**/admin/api/faq-categories", route -> {
            if ("POST".equals(route.request().method())) {
                route.fulfill(new Route.FulfillOptions().setStatus(201).setContentType("application/json").setBody("{broken"));
            } else {
                route.resume();
            }
        });
        openManage();

        openCategoryModal();
        page.fill("#catName", "본문유실");
        page.click("#btnCatSave");
        waitForSuccess("카테고리를 등록했습니다");
        waitUntilIdle();

        assertThat(fieldsetDisabled("catFieldset")).as("영구 잠금이 아니다").isFalse();
        assertThat(page.locator("#btnReload").isDisabled()).isFalse();
    }

    @Test
    @DisplayName("쓰기 응답을 잃으면(네트워크 오류) 결과 불명 안내가 뜨고 폼은 재전송되지 않으며 새로고침으로 확인할 수 있다")
    void writeResponseLost_showsUnknownResult() {
        AtomicBoolean abortNextPost = new AtomicBoolean();
        page.route("**/admin/api/faq-categories", route -> {
            if ("POST".equals(route.request().method()) && abortNextPost.getAndSet(false)) {
                route.abort();
            } else {
                route.resume();
            }
        });
        openManage();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM faq_category", Integer.class);

        abortNextPost.set(true);
        openCategoryModal();
        page.fill("#catName", "응답유실");
        page.click("#btnCatSave");
        waitForModalError("저장 여부를 확인할 수 없습니다");

        assertThat(fieldsetDisabled("catFieldset")).as("자동 재전송 방지 — 폼 잠금").isTrue();
        assertThat(page.inputValue("#catName")).as("입력은 보존").isEqualTo("응답유실");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM faq_category", Integer.class)).isEqualTo(before);

        // 폼이 잠긴 결과 불명 상태에서도 입력이 남아 있으면 닫을 때 미저장 확인을 거친다(PLAN-admin-form-dirty-close.md 설계 8)
        List<String> dialogs = new java.util.concurrent.CopyOnWriteArrayList<>();
        page.onDialog(dialog -> {
            dialogs.add(dialog.message());
            dialog.accept();
        });
        page.click("#btnModalClose");                       // 모달이 열려 있는 동안 [새로고침]은 배경 뒤에 있다 — 닫은 뒤(입력은 사라진다) 새로고침한다
        waitModalState("closed");
        assertThat(dialogs).as("잠긴 폼이어도 남은 입력은 닫을 때 확인한다").hasSize(1);
        assertThat(dialogs.get(0)).contains("저장하지 않은 변경이 있습니다");
        page.click("#btnReload");
        page.waitForFunction("() => !document.getElementById('catFieldset').disabled");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM faq_category", Integer.class)).as("재전송 없이 복구").isEqualTo(before);
    }
}
