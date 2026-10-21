package com.cms.admin.popup;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionCache;
import com.cms.common.image.ImageFileValidatorTest;
import com.cms.common.storage.FileStorage;
import com.cms.support.BrowserTestSupport;
import com.cms.support.CmsTestApplication;
import com.cms.support.MediaTestSupport;
import com.cms.support.TestMembers;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.WaitForSelectorState;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 팝업 관리 화면의 등록·수정 모달(PLAN-admin-form-modal.md)을 <b>실제 브라우저</b>로 고정한다 — MockMvc는 화면 JS·Bootstrap 모달을 재현하지 못한다:
 * ① 배경 클릭·ESC로 닫히지 않는다 ② 푸터 [저장]이 폼에 연결되어 브라우저 기본 검증이 요청 전에 막는다 ③ 등록·삭제는 모달을 닫고 목록에 반영한다
 * ④ 열린 폼의 대상이 목록에서 사라져도 폼이 생성 모드로 비워지지 않는다 ⑤ 상세 조회가 즉시 실패(404)해도 모달이 결국 닫히고 안내가 보인다
 * ⑥ 상세 조회 중 닫아도 다음 수정 진입이 막히지 않고 늦게 온 응답이 폼을 덮지 않는다 ⑦ 수정 권한 없이도 행을 열어 볼 수 있다
 * ⑧ 미저장 입력이 있으면 ×·[닫기]가 확인을 거치고(PLAN-admin-form-dirty-close.md) 변경이 없거나 프로그램이 닫을 때는 확인창이 뜨지 않는다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PopupModalBrowserTest extends BrowserTestSupport {

    @LocalServerPort int port;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileStorage fileStorage;
    @Autowired PermissionCache cache;

    private Member admin;
    private Member manager;
    private BrowserContext context;
    private Page page;

    @BeforeEach
    void setUp() {
        cleanPopups();
        admin = TestMembers.save(memberRepository, "ui-pm-admin", Role.ROLE_ADMIN);
        manager = TestMembers.save(memberRepository, "ui-pm-manager", Role.ROLE_MANAGER);
        enableFormLogin(jdbc, admin);
        enableFormLogin(jdbc, manager);
        context = newContext();
        page = context.newPage();
    }

    @AfterEach
    void tearDown() {
        context.close();
        cleanPopups();
        TestMembers.delete(jdbc, List.of(admin.getId(), manager.getId()));
        cache.invalidate();
        jdbc.update("DELETE FROM admin_action_log WHERE action_type LIKE 'POPUP_%'");
    }

    private void cleanPopups() {
        jdbc.update("DELETE FROM popup");
        MediaTestSupport.cleanLibrary(jdbc, fileStorage);
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

    private long htmlPopupWithContent(String title, String contentHtml) {
        jdbc.update("INSERT INTO popup (title, revision, popup_type, content_html, pos_x, pos_y, width, height, use_yn, create_date, update_date) "
                + "VALUES (?, 0, 'HTML', ?, 10, 10, 300, 200, 1, ?, ?)", title, contentHtml, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject("SELECT MAX(id) FROM popup", Long.class);
    }

    private void waitModalState(String state) {
        page.waitForFunction("s => modalState === s", state);
    }

    private void openCreate() {
        page.click("#btnNew");
        waitModalState("open");
    }

    private long htmlPopup(String title) {
        jdbc.update("INSERT INTO popup (title, revision, popup_type, content_html, pos_x, pos_y, width, height, use_yn, create_date, update_date) "
                + "VALUES (?, 0, 'HTML', '<p>본문</p>', 10, 10, 300, 200, 1, ?, ?)", title, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject("SELECT MAX(id) FROM popup", Long.class);
    }

    private void grant(String... actions) {
        jdbc.update("DELETE FROM member_permission WHERE member_id = ?", manager.getId());
        for (String action : actions) {
            jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, 'POPUP', ?)", manager.getId(), action);
        }
        cache.invalidate();
    }

    private void open(Member member) {
        loginAsAdmin(page, port, member);
        page.navigate("http://localhost:" + port + "/admin/popup/manage");
        page.waitForFunction("() => !document.getElementById('popupList').textContent.includes('불러오는 중')");
    }

    private Locator row(String title) {
        return page.locator("#popupList .popup-row").filter(new Locator.FilterOptions().setHasText(title));
    }

    /** 행을 눌러 모달이 완전히 열리고(열림 전환 끝) 상세가 폼에 채워질 때까지 기다린다. */
    private void openRow(String title, long id) {
        row(title).click();
        page.waitForFunction("() => modalState === 'open'");
        page.waitForFunction("id => document.getElementById('formTitle').textContent.includes('#' + id + ' 수정')"
                + " && document.getElementById('popupTitle').value !== ''", String.valueOf(id));
    }

    private void waitModalClosed() {
        page.waitForSelector("#popupModal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
    }

    private void waitForText(String elementId, String text) {
        page.waitForFunction("args => { const e = document.getElementById(args[0]);"
                + " return e && !e.classList.contains('d-none') && e.textContent.includes(args[1]); }", List.of(elementId, text));
    }

    @Test
    @DisplayName("모달은 배경 클릭·ESC로 닫히지 않고 ×로만 닫힌다")
    void staticBackdropClosesOnlyByButtons() {
        long id = htmlPopup("static-popup");
        open(admin);
        openRow("static-popup", id);

        page.keyboard().press("Escape");
        page.mouse().click(5, 5);
        page.waitForTimeout(500);
        assertThat(page.locator("#popupModal.show").count()).as("ESC·배경 클릭으로는 닫히지 않는다").isEqualTo(1);

        page.click("#btnModalCloseX");
        waitModalClosed();
    }

    @Test
    @DisplayName("제목을 비운 채 [저장]하면 브라우저 검증이 막아 요청이 나가지 않고 모달이 유지된다")
    void emptyTitleIsBlockedByBrowserValidation() {
        long id = htmlPopup("valid-popup");
        open(admin);
        openRow("valid-popup", id);

        AtomicInteger requests = new AtomicInteger();
        page.onRequest(request -> {
            if ("PUT".equals(request.method()) && request.url().contains("/admin/api/popups/")) {
                requests.incrementAndGet();
            }
        });
        page.fill("#popupTitle", "");
        page.click("#btnSave");                             // 푸터 버튼이 form 속성으로 폼에 연결돼 있어야 검증이 동작한다
        page.waitForTimeout(500);

        // 부정 단언(요청 없음)만으로는 버튼이 폼에 연결되지 않은 경우도 통과하므로, 브라우저가 첫 무효 입력으로 포커스를 옮긴 것을 증거로 삼는다
        assertThat(page.evaluate("() => document.activeElement && document.activeElement.id")).isEqualTo("popupTitle");
        assertThat(requests.get()).isZero();
        assertThat(page.locator("#popupModal.show").count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM popup WHERE id = ?", String.class, id)).isEqualTo("valid-popup");
    }

    @Test
    @DisplayName("등록하면 모달이 닫히고 목록에 나타나며, 삭제하면 모달이 닫히고 목록에서 사라진다")
    void createAndDeleteCloseTheModal() throws Exception {
        open(admin);
        page.click("#btnNew");
        page.waitForFunction("() => modalState === 'open'");
        page.fill("#popupTitle", "created-popup");
        page.setInputFiles("#popupImage", new FilePayload("p.png", "image/png", ImageFileValidatorTest.png(30, 12)));
        page.click("#btnSave");
        waitForText("pageSuccess", "팝업을 등록했습니다");
        waitModalClosed();
        page.waitForFunction("() => document.getElementById('popupList').textContent.includes('created-popup')");
        long id = jdbc.queryForObject("SELECT id FROM popup WHERE title = 'created-popup'", Long.class);

        page.onDialog(dialog -> dialog.accept());          // confirm()
        openRow("created-popup", id);
        page.click("#btnDelete");
        waitForText("pageSuccess", "팝업을 삭제했습니다");
        waitModalClosed();
        page.waitForFunction("() => !document.getElementById('popupList').textContent.includes('created-popup')");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM popup WHERE title = 'created-popup'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("열어 둔 팝업이 목록에서 사라진 상태로 목록 응답이 도착해도 폼은 생성 모드로 비워지지 않고 안내만 보인다")
    void lateListResponseDoesNotResetOpenForm() {
        long id = htmlPopup("vanishing-popup");
        open(admin);
        openRow("vanishing-popup", id);
        page.fill("#popupTitle", "typed-by-user");

        jdbc.update("DELETE FROM popup WHERE id = ?", id);
        page.evaluate("() => loadList()");                  // 모달이 열리기 전에 시작된 목록 요청이 지금 도착한 것과 같다

        waitForText("modalAlert", "삭제되었거나");
        assertThat(page.inputValue("#popupTitle")).as("입력이 그대로 남는다").isEqualTo("typed-by-user");
        assertThat(page.textContent("#formTitle")).as("생성 모드(새 팝업)로 바뀌지 않는다").contains("수정");
    }

    @Test
    @DisplayName("상세 조회가 즉시 404로 실패해도(열림 전환 중) 모달이 결국 닫히고 페이지 상단에 안내가 보인다")
    void immediateDetailFailureStillClosesTheModal() {
        htmlPopup("ghost-popup");
        trackDialogs();
        open(admin);
        // 상세 조회(GET /admin/api/popups/{id})가 열림 전환(약 300ms)보다 훨씬 빨리 404로 끝나도록 즉시 해결되는 응답으로 바꾼다 —
        // 서버 왕복은 전환이 끝난 뒤에 도착할 수 있어 "전환 중 hide() 무시"를 결정적으로 재현하지 못한다.
        page.evaluate("() => { const orig = window.fetch; window.fetch = function (url, options) {"
                + " return /\\/admin\\/api\\/popups\\/\\d+$/.test(String(url)) && !(options && options.method)"
                + " ? Promise.resolve(new Response('{}', { status: 404 })) : orig(url, options); }; }");

        row("ghost-popup").click();
        waitForText("pageAlert", "불러오지 못했습니다");
        // 모달 요소의 "숨김" 대기는 쓰지 않는다 — 열림 전환 초기에는 요소가 아직 display:none이라 닫히지 않았는데도 바로 통과한다.
        page.waitForFunction("() => modalState === 'closed'");   // 전환 중 hide()가 무시되면 open에서 멈춰 시간 초과로 실패한다
        assertThat(dialogMessages).as("프로그램이 닫는 경로(상세 조회 실패)는 미저장 확인을 거치지 않는다").isEmpty();
    }

    @Test
    @DisplayName("상세 조회 중 모달을 닫아도 다음 수정 진입이 막히지 않고, 늦게 온 응답이 새 폼을 덮어쓰지 않는다")
    void closingWhileLoadingDetailDoesNotLockOrClobber() {
        long first = htmlPopup("slow-popup");
        long second = htmlPopup("quick-popup");
        trackDialogs();
        open(admin);

        // 첫 상세 조회(GET /admin/api/popups/{id})만 1.5초 지연시킨다
        page.evaluate("() => { window.__origFetch = window.fetch;"
                + " window.fetch = function (url, options) {"
                + "   const slow = /\\/admin\\/api\\/popups\\/" + first + "$/.test(String(url)) && !(options && options.method);"
                + "   return slow ? new Promise(function (resolve) { setTimeout(function () { resolve(window.__origFetch(url, options)); }, 1500); })"
                + "               : window.__origFetch(url, options); }; }");
        row("slow-popup").click();
        page.waitForFunction("() => modalState === 'open'");
        page.click("#btnModalClose");                       // 상세가 오기 전에 닫는다(상세 조회 중에는 닫을 수 있다)
        waitModalClosed();

        // 닫은 뒤 다른 행을 열면 새 상세 조회가 시작되고 편집이 가능하다(loadingDetail 영구 잠금 회귀)
        openRow("quick-popup", second);
        assertThat(page.isDisabled("#btnSave")).as("두 번째 폼은 편집 가능").isFalse();

        page.waitForTimeout(2000);                          // 첫 응답이 늦게 도착한다
        assertThat(page.textContent("#formTitle")).as("늦게 온 첫 응답이 폼을 덮지 않았다").contains("#" + second + " 수정");
        assertThat(page.inputValue("#popupTitle")).isEqualTo("quick-popup");
        assertThat(dialogMessages).as("상세 조회 중에는 입력이 없어 닫아도 확인하지 않는다").isEmpty();
    }

    @Test
    @DisplayName("READ 전용 MANAGER는 행을 열어 볼 수 있고 저장은 막히며 [새 팝업]은 보이지 않는다")
    void readOnlyManagerCanOpenButNotSave() {
        long id = htmlPopup("readonly-popup");
        grant("READ");
        open(manager);

        assertThat(page.locator("#btnNew").isVisible()).as("CREATE 권한이 없으면 [새 팝업] 숨김").isFalse();
        openRow("readonly-popup", id);
        assertThat(page.locator("#btnSave").isDisabled()).as("UPDATE 권한이 없으면 저장 비활성").isTrue();
        assertThat(page.locator("#btnDelete").isVisible()).as("DELETE 권한이 없으면 삭제 숨김").isFalse();
    }

    @Test
    @DisplayName("READ+DELETE MANAGER는 수정 권한 없이도 모달의 [삭제]에 도달한다")
    void readDeleteManagerReachesDelete() {
        long id = htmlPopup("deletable-popup");
        grant("READ", "DELETE");
        open(manager);

        openRow("deletable-popup", id);
        assertThat(page.locator("#btnDelete").isVisible()).isTrue();
        assertThat(page.locator("#btnSave").isDisabled()).isTrue();
    }

    @Test
    @DisplayName("미저장 입력이 있으면 ×·[닫기]가 확인하고, 취소하면 모달과 입력이 유지되며 수락하면 닫힌다")
    void dirtyCloseAsksForConfirmation() {
        trackDialogs();
        open(admin);
        openCreate();
        page.fill("#popupTitle", "typed-by-user");

        acceptDialogs.set(false);
        page.click("#btnModalClose");                       // 취소 — 닫힘이 막혀야 한다
        page.waitForTimeout(500);
        assertThat(dialogMessages).hasSize(1);
        assertThat(dialogMessages.get(0)).contains("저장하지 않은 변경이 있습니다");
        assertThat(page.locator("#popupModal.show").count()).as("취소하면 모달이 유지된다").isEqualTo(1);
        assertThat(page.inputValue("#popupTitle")).as("입력이 보존된다").isEqualTo("typed-by-user");

        page.click("#btnModalCloseX");                      // × 도 같은 확인을 거친다
        page.waitForTimeout(500);
        assertThat(dialogMessages).hasSize(2);
        assertThat(page.locator("#popupModal.show").count()).isEqualTo(1);

        acceptDialogs.set(true);
        page.click("#btnModalClose");                       // 수락 — 닫힌다
        waitModalState("closed");
        assertThat(dialogMessages).hasSize(3);

        openCreate();                                       // 닫힌 뒤 다시 열면 이전 입력도, 이전 기준값도 남지 않는다
        assertThat(page.inputValue("#popupTitle")).isEmpty();
        page.click("#btnModalClose");
        waitModalState("closed");
        assertThat(dialogMessages).as("입력 없이 닫으면 확인하지 않는다").hasSize(3);
    }

    @Test
    @DisplayName("변경이 없으면 확인하지 않는다 — 입력 없는 등록, 정규화되는 본문의 수정 모달, 바꿨다 되돌린 수정")
    void unchangedFormClosesWithoutConfirmation() {
        // 공백 연속·nbsp·끝 빈 문단은 편집기가 로드할 때 정규화하는 대표 입력이다 — 기준값이 정규화 이후여야 거짓 양성이 없다
        long id = htmlPopupWithContent("normalized-popup", "<p>두  칸&nbsp;&nbsp;공백</p><ul><li>항목</li></ul><p><br></p>");
        trackDialogs();
        open(admin);

        openCreate();
        page.click("#btnModalClose");
        waitModalState("closed");

        openRow("normalized-popup", id);
        page.click("#btnModalCloseX");
        waitModalState("closed");

        openRow("normalized-popup", id);
        page.fill("#popupTitle", "다른 제목");
        page.fill("#popupTitle", "normalized-popup");       // 되돌리면 변경이 아니다
        page.click("#btnModalClose");
        waitModalState("closed");

        assertThat(dialogMessages).as("변경이 없는 닫기는 확인창 0회").isEmpty();
    }

    @Test
    @DisplayName("수정 모달에서 HTML 본문만 바꿔도 변경으로 본다")
    void editorChangeCountsAsAChange() {
        long id = htmlPopup("body-popup");
        trackDialogs();
        acceptDialogs.set(true);
        open(admin);
        openRow("body-popup", id);

        page.click("#popupContentEditor .ql-editor");
        page.keyboard().type("추가 본문");                  // 다른 입력은 그대로 — 편집기 변경만으로 확인한다
        page.click("#btnModalClose");
        waitModalState("closed");

        assertThat(dialogMessages).hasSize(1);
    }

    @Test
    @DisplayName("제목·링크·위치·기간·사용·표시 대상·종류·이미지 파일·라이브러리 이미지 중 하나라도 바뀌면 변경으로 본다")
    void everyInputCountsAsAChange() throws Exception {
        trackDialogs();
        acceptDialogs.set(true);
        open(admin);
        assertThat((Integer) page.evaluate("() => document.getElementById('popupSiteScope').options.length"))
                .as("표시 대상 시험에 필요한 사이트 옵션").isGreaterThan(1);
        byte[] png = ImageFileValidatorTest.png(30, 12);

        List<String> names = List.of("제목", "링크", "위치", "노출 시작", "사용", "표시 대상", "종류", "이미지 파일", "라이브러리 이미지");
        List<Runnable> actions = List.of(
                () -> page.fill("#popupTitle", "x"),
                () -> page.fill("#popupLink", "https://example.com"),
                () -> page.fill("#popupPosX", "50"),
                () -> page.fill("#popupStartDate", "2030-01-01"),
                () -> page.uncheck("#popupUse"),
                () -> page.selectOption("#popupSiteScope", new SelectOption().setIndex(1)),
                () -> page.check("#typeHtml"),
                () -> page.setInputFiles("#popupImage", new FilePayload("p.png", "image/png", png)),
                () -> page.evaluate("() => setPickedImage({ id: 1, originalName: 'lib.png' })"));
        for (int i = 0; i < actions.size(); i++) {
            int before = dialogMessages.size();
            openCreate();
            actions.get(i).run();
            page.click("#btnModalClose");
            waitModalState("closed");
            assertThat(dialogMessages).as(names.get(i) + " 변경은 닫을 때 확인한다").hasSize(before + 1);
        }
    }

    @Test
    @DisplayName("저장·삭제 성공으로 닫을 때는 미저장 확인을 거치지 않는다(삭제 확인만 한 번 뜬다)")
    void programmaticClosesSkipTheDiscardConfirmation() throws Exception {
        trackDialogs();
        acceptDialogs.set(true);
        open(admin);

        openCreate();
        page.fill("#popupTitle", "saved-popup");
        page.setInputFiles("#popupImage", new FilePayload("p.png", "image/png", ImageFileValidatorTest.png(30, 12)));
        page.click("#btnSave");
        waitForText("pageSuccess", "팝업을 등록했습니다");
        waitModalState("closed");
        assertThat(dialogMessages).as("등록 성공 닫기").isEmpty();
        long id = jdbc.queryForObject("SELECT id FROM popup WHERE title = 'saved-popup'", Long.class);

        openRow("saved-popup", id);
        page.fill("#popupTitle", "saved-popup-2");
        page.click("#btnSave");
        waitForText("pageSuccess", "팝업을 저장했습니다");
        waitModalState("closed");
        assertThat(dialogMessages).as("수정 성공 닫기").isEmpty();

        openRow("saved-popup-2", id);
        page.fill("#popupTitle", "unsaved-edit");           // 미저장 입력이 있어도 삭제 성공 닫기는 확인하지 않는다
        page.click("#btnDelete");
        waitForText("pageSuccess", "팝업을 삭제했습니다");
        waitModalState("closed");
        assertThat(dialogMessages).as("삭제 확인 한 번뿐").hasSize(1);
        assertThat(dialogMessages.get(0)).contains("삭제하시겠습니까");
    }

    @Test
    @DisplayName("미디어 라이브러리 선택 모달을 열었다 닫아도 팝업 모달은 확인 없이 유지된다")
    void closingTheLibraryPickerDoesNotTriggerTheConfirmation() {
        trackDialogs();
        open(admin);
        openCreate();
        page.fill("#popupTitle", "typed-before-picker");    // 폼이 더러운 상태여야 선택 모달의 hide 이벤트가 버블링되면 확인창이 뜬다

        page.click("#btnPickLibrary");
        String picker = ".modal.show:not(#popupModal)";
        page.waitForSelector(picker);
        page.click(picker + " [data-dismiss=modal]");
        page.waitForFunction("() => !document.querySelector('.modal.show:not(#popupModal)')");

        assertThat(dialogMessages).isEmpty();
        assertThat(page.locator("#popupModal.show").count()).as("팝업 모달은 열려 있다").isEqualTo(1);
        assertThat((String) page.evaluate("() => modalState")).isEqualTo("open");
        assertThat(page.inputValue("#popupTitle")).isEqualTo("typed-before-picker");
    }
}
