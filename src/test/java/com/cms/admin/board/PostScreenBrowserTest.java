package com.cms.admin.board;

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
import com.microsoft.playwright.FileChooser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.FilePayload;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 게시글 관리 화면({@code /admin/board/posts})의 동작을 <b>실제 브라우저</b>로 고정하는 특성 시험(PLAN-react-admin-posts.md §6). React 전환 전 인라인 스크립트
 * 화면에서 먼저 통과시켰고, 전환 뒤에도 같은 시험이 그대로 통과해야 한다 — 그래서 화면 전역 변수를 읽지 않고 DOM id·문구·요청 본문·DB로만 단언한다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostScreenBrowserTest extends BrowserTestSupport {

    private static final String MODAL = "#postDetailModal";

    @LocalServerPort int port;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PermissionCache cache;
    @Autowired FileStorage fileStorage;
    @Autowired ObjectMapper objectMapper;

    private Member admin;
    private Member manager;
    private BrowserContext context;
    private Page page;
    private long alpha;
    private long beta;
    private final List<String> dialogs = new ArrayList<>();
    private final List<String> writes = new ArrayList<>();
    private final List<String> consoleErrors = new ArrayList<>();

    @BeforeEach
    void setUp() {
        cleanBoards();
        admin = TestMembers.save(memberRepository, "ps-admin", Role.ROLE_ADMIN);
        manager = TestMembers.save(memberRepository, "ps-manager", Role.ROLE_MANAGER);
        enableFormLogin(jdbc, admin);
        enableFormLogin(jdbc, manager);
        alpha = board("ps-alpha");
        beta = board("ps-beta");
        context = newContext();
        page = context.newPage();
        page.onDialog(dialog -> {
            dialogs.add(dialog.message());
            dialog.accept();
        });
        page.onConsoleMessage(message -> {
            if ("error".equals(message.type())) {
                consoleErrors.add(message.text());
            }
        });
        page.onPageError(consoleErrors::add);
        page.onRequest(request -> {
            if (request.url().contains("/admin/api/boards/") && !"GET".equals(request.method())) {
                String type = request.headers().getOrDefault("content-type", "");
                String body = type.startsWith("application/json") && request.postData() != null ? request.postData() : "";
                writes.add(request.method() + " " + request.url().replaceFirst("https?://[^/]+", "") + " " + body);
            }
        });
        loginAsAdmin(page, port, admin);
    }

    @AfterEach
    void tearDown() {
        context.close();
        cleanBoards();
        for (Member member : List.of(admin, manager)) {
            for (var image : jdbc.queryForList("SELECT id, storage_key FROM content_image WHERE uploader_id = ?", member.getUserId())) {
                jdbc.update("DELETE FROM content_image_ref WHERE image_id = ?", image.get("id"));
                jdbc.update("DELETE FROM content_image WHERE id = ?", image.get("id"));
                fileStorage.delete((String) image.get("storage_key"));
            }
        }
        MediaTestSupport.recomputeCounter(jdbc);
        TestMembers.delete(jdbc, List.of(admin.getId(), manager.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE action_type LIKE 'POST_%'");
        cache.invalidate();
    }

    private void cleanBoards() {
        String boards = "(SELECT id FROM board WHERE name LIKE 'ps-%')";
        String posts = "(SELECT id FROM post WHERE board_id IN " + boards + ")";
        for (String key : jdbc.queryForList("SELECT storage_key FROM post_attachment WHERE post_id IN " + posts, String.class)) {
            fileStorage.delete(key);
        }
        jdbc.update("DELETE FROM post_attachment WHERE post_id IN " + posts);
        jdbc.update("DELETE FROM content_image_ref WHERE owner_type = 'POST' AND owner_id IN " + posts);
        jdbc.update("DELETE FROM post WHERE board_id IN " + boards);
        jdbc.update("DELETE FROM member_board_permission WHERE board_id IN " + boards);
        jdbc.update("DELETE FROM board WHERE name LIKE 'ps-%'");
    }

    // ===================== 픽스처 =====================

    private long board(String name) {
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES (?, 1, 1, 0)", name);
        return jdbc.queryForObject("SELECT MAX(id) FROM board WHERE name = ?", Long.class, name);
    }

    private long post(long boardId, String title, String content) {
        jdbc.update("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, updater_id, create_date, update_date)"
                + " VALUES (?, ?, ?, 1, 0, ?, ?, NOW(6), NOW(6))", boardId, title, content, admin.getUserId(), admin.getUserId());
        return jdbc.queryForObject("SELECT MAX(id) FROM post WHERE board_id = ? AND title = ?", Long.class, boardId, title);
    }

    private long post(long boardId, String title) {
        return post(boardId, title, "<p>본문 " + title + "</p>");
    }

    private void attachment(long postId, String filename) {
        jdbc.update("INSERT INTO post_attachment (post_id, original_filename, content_type, file_size, storage_key, create_date) VALUES (?, ?, 'text/plain', 2048, ?, NOW(6))",
                postId, filename, "ps/" + System.nanoTime() + ".txt");
    }

    private void grant(long boardId, String... actions) {
        for (String action : actions) {
            jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, ?)", manager.getId(), boardId, action);
        }
        cache.invalidate();
    }

    private void loginAsManager() {
        context.clearCookies();
        loginAsAdmin(page, port, manager);
    }

    /** 게시판 출처의 본문 이미지를 실제 업로드 API로 올린다(대표 썸네일까지 동작하는 진짜 파일). */
    private long uploadImage(long boardId) {
        Object id = page.evaluate("async boardId => { const canvas = document.createElement('canvas'); canvas.width = 20; canvas.height = 20;"
                + " canvas.getContext('2d').fillRect(0, 0, 20, 20); const blob = await new Promise(r => canvas.toBlob(r, 'image/png'));"
                + " const form = new FormData(); form.append('file', blob, 'a.png'); const csrf = document.querySelector('meta[name=_csrf]').content;"
                + " const response = await fetch('/admin/api/boards/' + boardId + '/content-images', { method: 'POST', headers: { 'X-CSRF-TOKEN': csrf }, body: form });"
                + " if (!response.ok) { throw new Error('업로드 실패 ' + response.status); } return (await response.json()).url.split('/').pop(); }", String.valueOf(boardId));
        return Long.parseLong(String.valueOf(id));
    }

    private long imagePost(long boardId, String title, long... imageIds) {
        StringBuilder content = new StringBuilder("<p>그림</p><p>");
        for (long id : imageIds) {
            content.append("<img src=\"/content-images/").append(id).append("\">");
        }
        long postId = post(boardId, title, content.append("</p>").toString());
        for (long id : imageIds) {
            jdbc.update("INSERT INTO content_image_ref (owner_type, owner_id, image_id) VALUES ('POST', ?, ?)", postId, id);
        }
        return postId;
    }

    // ===================== 화면 조작 =====================

    private String url(String query) {
        return "http://localhost:" + port + "/admin/board/posts" + query;
    }

    private void open() {
        page.navigate(url(""));
        page.waitForFunction("() => { const s = document.getElementById('boardSelect'); return s && !s.disabled; }");
    }

    private void open(long boardId) {
        open();
        selectBoard(boardId);
    }

    private void selectBoard(long boardId) {
        page.selectOption("#boardSelect", String.valueOf(boardId));
        waitListLoaded();
    }

    private void waitListLoaded() {
        page.waitForFunction("() => !document.getElementById('postTableBody').textContent.includes('조회 중')");
    }

    private void waitRow(String text) {
        page.waitForFunction("t => document.getElementById('postTableBody').textContent.includes(t)", text);
    }

    private void waitNoRow(String text) {
        page.waitForFunction("t => { const b = document.getElementById('postTableBody').textContent; return !b.includes(t) && !b.includes('조회 중'); }", text);
    }

    private Locator row(String text) {
        return page.locator("#postTableBody tr", new Page.LocatorOptions().setHasText(text));
    }

    private void waitModalOpen() {
        page.waitForFunction("() => { const m = $('#postDetailModal').data('bs.modal'); return !!m && m._isShown && !m._isTransitioning; }");
    }

    private void waitModalClosed() {
        page.waitForFunction("() => { const m = $('#postDetailModal').data('bs.modal'); return !!m && !m._isShown && !m._isTransitioning"
                + " && !document.querySelector('.modal-backdrop'); }");
    }

    private void closeModal() {
        page.click(MODAL + " .modal-footer [data-dismiss=modal]");
        waitModalClosed();
    }

    private void openDetail(String title) {
        row(title).locator(".js-detail").first().click();
        waitModalOpen();
        waitVisibleText("viewTitle", title);
    }

    private void openEdit(String title) {
        openDetail(title);
        page.click("#btnEditMode");
    }

    private void openCreate() {
        page.click("#btnNewPost");
        waitModalOpen();
    }

    private void waitVisibleText(String id, String text) {
        page.waitForFunction("a => { const e = document.getElementById(a[0]); return e && !e.closest('.d-none') && e.textContent.includes(a[1]); }", List.of(id, text));
    }

    private boolean hidden(String id) {
        return (Boolean) page.evaluate("id => document.getElementById(id).closest('.d-none') !== null", id);
    }

    private void typeBody(String text) {
        page.click(".post-editor .ql-editor");
        page.keyboard().press("Control+A");
        page.keyboard().type(text);
    }

    private void save() {
        page.click("#btnSave");
        waitVisibleText("detailSuccess", "저장되었습니다.");
    }

    private void check(String inputId) {
        page.click("label[for=" + inputId + "]");   // custom-switch는 입력이 label에 가려져 있다
    }

    private JsonNode lastWrite(String method) {
        String last = writes.stream().filter(w -> w.startsWith(method + " ")).reduce((a, b) -> b).orElseThrow();
        return objectMapper.readTree(last.substring(last.indexOf('{')));
    }

    private long countWrites(String method) {
        return writes.stream().filter(w -> w.startsWith(method + " ")).count();
    }

    private List<String> keys(JsonNode node) {
        return List.copyOf(node.propertyNames());
    }

    /** 이후 {@code glob}에 맞는 {@code method} 요청을 서버로 보내지 않고 붙잡는다. */
    private List<Route> hold(String glob, String method) {
        List<Route> held = new ArrayList<>();
        page.route(glob, route -> {
            if (method.equals(route.request().method())) {
                held.add(route);
            } else {
                route.fallback();
            }
        });
        return held;
    }

    private FilePayload file(String name, String type, byte[] bytes) {
        return new FilePayload(name, type, bytes);
    }

    // ===================== 목록 =====================

    @Test
    @DisplayName("목록은 상태 배지·기간·고정·수정자를 보이고, 검색·초기화가 조건을 바꾼다")
    void listShowsBadgesAndSearches() {
        long scheduled = post(alpha, "ps-예약");
        jdbc.update("UPDATE post SET publish_start = '2099-01-02 03:04:00' WHERE id = ?", scheduled);
        long hiddenPost = post(alpha, "ps-숨김");
        jdbc.update("UPDATE post SET use_yn = 0, pinned = 1, updater_id = NULL WHERE id = ?", hiddenPost);
        post(alpha, "ps-일반");
        open(alpha);

        waitRow("ps-일반");
        assertThat(page.textContent("#summaryText")).isEqualTo("총 3건 · 1 / 1 페이지");
        assertThat(row("ps-예약").textContent()).contains("예약됨").contains("2099-01-02 03:04 ~ …");
        assertThat(row("ps-예약").locator(".badge-status-scheduled").count()).isEqualTo(1);
        assertThat(row("ps-숨김").textContent()).contains("고정").contains("비노출").contains("제한 없음");
        assertThat(row("ps-숨김").locator("td").nth(3).textContent()).isEqualTo("-");
        assertThat(row("ps-일반").textContent()).contains("노출 중").contains(admin.getUserId());

        page.fill("#searchKeyword", "  일반  ");
        page.click("#searchForm button[type=submit]");
        waitNoRow("ps-예약");
        assertThat(page.textContent("#summaryText")).startsWith("총 1건");
        assertThat(page.url()).as("페이지를 옮기지 않는다").isEqualTo(url(""));

        page.click("#btnReset");
        waitRow("ps-예약");
        assertThat(page.inputValue("#searchKeyword")).isEmpty();

        page.selectOption("#searchUseYn", "false");
        page.press("#searchKeyword", "Enter");
        waitNoRow("ps-일반");
        assertThat(page.textContent("#postTableBody")).contains("ps-숨김");

        page.fill("#searchKeyword", "없는제목");
        page.press("#searchKeyword", "Enter");
        waitVisibleText("emptyState", "조회 결과가 없습니다.");
        assertThat(page.textContent("#postTableBody")).isEmpty();
    }

    @Test
    @DisplayName("페이지 이동은 화면에 적힌 검색어(제출 전 포함)를 싣고, 마지막 글을 지워 빈 페이지가 되면 이전 유효 페이지로 돌아간다")
    void paginationUsesCurrentFormAndFallsBackToLastPage() {
        for (int i = 1; i <= 21; i++) {
            post(alpha, "ps-글" + String.format("%02d", i));
        }
        open(alpha);
        waitRow("ps-글21");
        assertThat(page.locator("#pagination .page-item.active").textContent()).isEqualTo("1");
        assertThat(page.locator("#pagination .page-item.disabled").textContent()).isEqualTo("이전");

        page.click("#pagination button:has-text('다음')");
        waitRow("ps-글01");
        assertThat(page.textContent("#summaryText")).isEqualTo("총 21건 · 2 / 2 페이지");

        openDetail("ps-글01");
        page.click("#btnDelete");
        waitModalClosed();
        assertThat(dialogs).containsExactly("선택한 게시글을 삭제하시겠습니까?");
        waitRow("ps-글02");
        assertThat(page.textContent("#summaryText")).isEqualTo("총 20건 · 1 / 1 페이지");
        assertThat(page.locator("#pagination li").count()).isZero();

        post(alpha, "ps-글22");
        page.click("#searchForm button[type=submit]");
        waitRow("ps-글22");
        List<String> urls = new ArrayList<>();
        page.onRequest(request -> {
            if (request.url().contains("/posts?")) {
                urls.add(request.url());
            }
        });
        page.fill("#searchKeyword", "글0");
        page.click("#pagination button:has-text('다음')");
        waitNoRow("ps-글22");
        assertThat(urls.get(0)).contains("keyword=%EA%B8%800").contains("page=1").contains("size=20");
    }

    @Test
    @DisplayName("게시판을 바꾼 뒤 늦게 온 이전 게시판의 목록은 화면을 덮지 않는다")
    void lateListOfPreviousBoardIsIgnored() {
        post(alpha, "ps-알파글");
        post(beta, "ps-베타글");
        open();
        holdFirstResponse(page, "/boards/" + alpha + "/posts\\?");
        page.selectOption("#boardSelect", String.valueOf(alpha));
        page.waitForFunction("() => typeof window.__releaseHeld === 'function'");
        selectBoard(beta);
        waitRow("ps-베타글");

        releaseHeldResponse(page);

        assertThat(page.textContent("#postTableBody")).contains("ps-베타글").doesNotContain("ps-알파글");
    }

    @Test
    @DisplayName("첫 목록 조회가 진행 중일 때 글을 저장하면 저장 전 응답이 목록에 남지 않는다")
    void saveDuringFirstListLoadIsReflected() {
        open(beta);
        holdFirstResponse(page, "/boards/" + alpha + "/posts\\?");
        page.selectOption("#boardSelect", String.valueOf(alpha));
        page.waitForFunction("() => typeof window.__releaseHeld === 'function'");
        assertThat(page.textContent("#postTableBody")).contains("조회 중입니다.");

        openCreate();
        page.fill("#formTitle", "ps-조회중저장");
        typeBody("본문");
        save();
        waitRow("ps-조회중저장");

        releaseHeldResponse(page);

        assertThat(page.textContent("#postTableBody")).contains("ps-조회중저장");
        assertThat(page.textContent("#summaryText")).startsWith("총 1건");
    }

    @Test
    @DisplayName("저장 중 검색 조건을 바꿔 새 목록 조회가 먼저 끝나도, 저장 완료 뒤 현재 조건으로 다시 읽는다")
    void listIsReloadedWithCurrentFiltersAfterSaveCompletes() {
        open(alpha);
        List<Route> held = hold("**/admin/api/boards/" + alpha + "/posts", "POST");
        openCreate();
        page.fill("#formTitle", "ps-늦은저장");
        typeBody("본문");
        page.click("#btnSave");
        page.waitForCondition(() -> !held.isEmpty());
        closeModal();

        page.fill("#searchKeyword", "늦은저장");
        page.press("#searchKeyword", "Enter");
        waitVisibleText("emptyState", "조회 결과가 없습니다.");
        held.get(0).resume();

        waitRow("ps-늦은저장");
        assertThat(page.locator(MODAL + ".show").count()).as("닫은 모달이 다시 열리지 않는다").isZero();
    }

    @Test
    @DisplayName("목록 조회 실패: 403은 권한 안내, 연결 실패는 네트워크 안내를 보이고 실패 행을 그린다")
    void listFailureMessages() {
        for (int i = 1; i <= 21; i++) {
            post(alpha, "ps-글" + i);
        }
        open(alpha);
        waitRow("ps-글21");
        page.route("**/admin/api/boards/" + alpha + "/posts?*", route -> route.fulfill(new Route.FulfillOptions().setStatus(403)
                .setContentType("application/json").setBody("{\"message\":\"Access Denied\"}")));
        page.click("#searchForm button[type=submit]");
        waitVisibleText("pageAlert", "권한이 없습니다. 권한이 변경되었을 수 있으니 페이지를 새로고침해 주세요.");
        assertThat(page.textContent("#postTableBody")).isEqualTo("조회에 실패했습니다.");
        assertThat(page.locator("#pagination li").count()).isZero();

        page.unroute("**/admin/api/boards/" + alpha + "/posts?*");
        page.route("**/admin/api/boards/" + alpha + "/posts?*", Route::abort);
        page.click("#searchForm button[type=submit]");
        waitVisibleText("pageAlert", "네트워크 연결을 확인해주세요.");

        page.unroute("**/admin/api/boards/" + alpha + "/posts?*");
        page.click("#searchForm button[type=submit]");
        waitRow("ps-글21");
        assertThat(hidden("pageAlert")).isTrue();
    }

    // ===================== 작성·수정 =====================

    @Test
    @DisplayName("생성: 고르지 않은 고정·기간·대표는 보내지 않고, 저장 뒤 보기 모드와 첨부 영역이 나타난다")
    void createSendsOnlyChosenOptions() {
        open(alpha);
        openCreate();
        assertThat(page.textContent("#modalModeLabel")).isEqualTo("새 게시글을 작성합니다. (ps-alpha)");
        assertThat(hidden("attachmentSection")).isTrue();
        assertThat(hidden("pinGroup")).as("고정을 끈 게시판").isTrue();
        assertThat(page.isChecked("#formUseYn")).isTrue();
        page.fill("#formTitle", "  ps-새글  ");
        typeBody("새 본문");
        save();

        JsonNode body = lastWrite("POST");
        assertThat(keys(body)).containsExactlyInAnyOrder("title", "content", "contentFormat", "useYn");
        assertThat(body.get("title").asString()).isEqualTo("ps-새글");
        assertThat(body.get("contentFormat").asString()).isEqualTo("HTML");
        assertThat(page.textContent("#detailSuccess")).isEqualTo("저장되었습니다. (ps-새글)");
        assertThat(page.textContent("#viewTitle")).isEqualTo("ps-새글");
        waitVisibleText("attachmentList", "첨부된 파일이 없습니다.");
        waitRow("ps-새글");
    }

    @Test
    @DisplayName("생성: 고정과 기간을 고르면 실려 저장된다. 날짜 없이 시·분만 고르면 요청 전에 막힌다")
    void createWithPinAndPeriod() {
        jdbc.update("UPDATE board SET pin_max_count = 2 WHERE id = ?", alpha);
        open(alpha);
        openCreate();
        assertThat(page.textContent("#pinNote")).contains("최대 2개까지");
        page.fill("#formTitle", "ps-기간글");
        typeBody("본문");
        check("formPinned");
        page.selectOption("#formStartHour", "09");
        page.click("#btnSave");
        waitVisibleText("detailAlert", "노출 시작 날짜를 선택해 주세요.");
        awaitRequestBarrier(page);
        assertThat(countWrites("POST")).isZero();

        page.fill("#formStartDate", "2099-03-04");
        page.fill("#formEndDate", "2099-05-06");
        page.selectOption("#formEndMinute", "30");
        save();

        JsonNode body = lastWrite("POST");
        assertThat(body.get("pinned").asBoolean()).isTrue();
        assertThat(body.get("publishPeriod").get("start").asString()).isEqualTo("2099-03-04T09:00");
        assertThat(body.get("publishPeriod").get("end").asString()).isEqualTo("2099-05-06T00:30");
        assertThat(page.textContent("#viewPeriod")).isEqualTo("2099-03-04 09:00 ~ 2099-05-06 00:30");
        assertThat(page.textContent("#viewPinned")).isEqualTo("예");
        assertThat(page.textContent("#viewStatus")).isEqualTo("예약됨");
    }

    @Test
    @DisplayName("수정: 바꾸지 않으면 요청 없이 안내하고, 바뀐 필드만 PATCH에 싣는다")
    void patchSendsOnlyChangedFields() {
        jdbc.update("UPDATE board SET pin_max_count = 1 WHERE id = ?", alpha);
        long id = post(alpha, "ps-원본");
        jdbc.update("UPDATE post SET publish_start = '2020-01-02 03:04:00' WHERE id = ?", id);
        open(alpha);
        openEdit("ps-원본");
        assertThat(page.inputValue("#formStartDate")).isEqualTo("2020-01-02");
        assertThat(page.inputValue("#formStartHour")).isEqualTo("03");
        assertThat(page.inputValue("#formStartMinute")).isEqualTo("04");
        page.click("#btnSave");
        waitVisibleText("detailAlert", "변경된 내용이 없습니다.");
        assertThat(countWrites("PATCH")).isZero();

        page.fill("#formTitle", "ps-원본수정");
        save();
        assertThat(keys(lastWrite("PATCH"))).containsExactly("title");

        page.click("#btnEditMode");
        check("formUseYn");
        save();
        assertThat(keys(lastWrite("PATCH"))).containsExactly("useYn");

        page.click("#btnEditMode");
        check("formPinned");
        save();
        assertThat(keys(lastWrite("PATCH"))).containsExactly("pinned");

        page.click("#btnEditMode");
        typeBody("바뀐 본문");
        save();
        assertThat(keys(lastWrite("PATCH"))).containsExactlyInAnyOrder("content", "contentFormat");

        page.click("#btnEditMode");
        page.fill("#formEndDate", "2099-01-01");
        save();
        JsonNode body = lastWrite("PATCH");
        assertThat(keys(body)).containsExactly("publishPeriod");
        assertThat(body.get("publishPeriod").get("start").asString()).as("기간은 통째로 보낸다").isEqualTo("2020-01-02T03:04");

        page.click("#btnEditMode");
        page.fill("#formStartDate", "");
        page.selectOption("#formStartHour", "");
        page.selectOption("#formStartMinute", "");
        page.fill("#formEndDate", "");
        page.selectOption("#formEndHour", "");
        page.selectOption("#formEndMinute", "");
        save();
        assertThat(lastWrite("PATCH").get("publishPeriod").get("start").isNull()).isTrue();
        assertThat(page.textContent("#viewPeriod")).isEqualTo("제한 없음");
        waitRow("ps-원본수정");
    }

    @Test
    @DisplayName("수정 [취소]는 보기 모드로 돌아가고 다시 수정하면 서버 값으로 채워진다. 작성 [취소]는 모달을 닫는다")
    void cancelRestoresServerValues() {
        post(alpha, "ps-취소글");
        open(alpha);
        openEdit("ps-취소글");
        page.fill("#formTitle", "버릴 제목");
        typeBody("버릴 본문");
        page.click("#btnCancelEdit");
        assertThat(hidden("detailContent")).isFalse();
        page.click("#btnEditMode");
        assertThat(page.inputValue("#formTitle")).isEqualTo("ps-취소글");
        assertThat(page.textContent(".post-editor .ql-editor")).isEqualTo("본문 ps-취소글");
        closeModal();

        openCreate();
        page.click("#btnCancelEdit");
        waitModalClosed();
    }

    @Test
    @DisplayName("예약 게시를 끈 게시판: 기존 기간과 [기간 해제]만 보이고, 해제 취소하면 기간을 보내지 않으며, 해제하면 비운 기간을 보낸다")
    void lockedPeriodCanOnlyBeCleared() {
        jdbc.update("UPDATE board SET publish_period_yn = 0, pin_max_count = 0 WHERE id = ?", alpha);
        long id = post(alpha, "ps-잠긴기간");
        jdbc.update("UPDATE post SET publish_end = '2099-01-02 03:04:00', pinned = 1 WHERE id = ?", id);
        open(alpha);
        openEdit("ps-잠긴기간");
        assertThat(hidden("periodFields")).isTrue();
        assertThat(page.textContent("#periodLockedText")).isEqualTo("… ~ 2099-01-02 03:04");
        assertThat(hidden("pinGroup")).as("고정을 꺼도 이미 고정된 글은 해제할 수 있다").isFalse();
        assertThat(page.textContent("#pinNote")).isEqualTo("고정이 꺼진 게시판입니다. 해제만 할 수 있습니다.");

        page.click("#btnClearPeriod");
        assertThat(page.textContent("#periodLockedText")).isEqualTo("해제 예정 (저장하면 제한 없음)");
        page.click("#btnUndoClearPeriod");
        page.click("#btnSave");
        waitVisibleText("detailAlert", "변경된 내용이 없습니다.");

        page.click("#btnClearPeriod");
        save();
        JsonNode body = lastWrite("PATCH");
        assertThat(keys(body)).containsExactly("publishPeriod");
        assertThat(body.get("publishPeriod").get("end").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT publish_end IS NULL FROM post WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("대표 이미지: 직접 고르면 그 값, 직접 '없음'이면 null을 보내고, 본문에서 지워 자동으로 없어진 것은 보내지 않는다")
    void coverIsSentOnlyWhenUserChoseIt() {
        open(alpha);
        long first = uploadImage(alpha);
        long second = uploadImage(alpha);
        writes.clear();
        long id = imagePost(alpha, "ps-그림글", first, second);
        page.click("#searchForm button[type=submit]");
        waitRow("ps-그림글");
        openEdit("ps-그림글");
        assertThat(page.locator("#coverChoices .cover-choice").count()).isEqualTo(3);
        page.check("#coverChoices input[value='" + second + "']");
        assertThat(page.locator("#coverChoices .is-selected input").inputValue()).isEqualTo(String.valueOf(second));
        save();
        assertThat(keys(lastWrite("PATCH"))).containsExactly("coverImageId");
        assertThat(lastWrite("PATCH").get("coverImageId").asLong()).isEqualTo(second);
        assertThat(page.getAttribute("#viewCover img", "src")).isEqualTo("/content-images/" + second + "?size=thumb");

        page.click("#btnEditMode");
        page.check("#coverChoices input[value='']");
        save();
        assertThat(keys(lastWrite("PATCH"))).containsExactly("coverImageId");
        assertThat(lastWrite("PATCH").get("coverImageId").isNull()).isTrue();
        assertThat(page.textContent("#viewCover")).isEqualTo("없음");

        page.click("#btnEditMode");
        page.check("#coverChoices input[value='" + first + "']");
        save();
        page.click("#btnEditMode");
        typeBody("글자만");
        assertThat(hidden("coverGroup")).as("이미지가 없으면 선택지를 숨긴다").isTrue();
        save();
        assertThat(keys(lastWrite("PATCH"))).containsExactlyInAnyOrder("content", "contentFormat");
        assertThat(jdbc.queryForObject("SELECT cover_image_id IS NULL FROM post WHERE id = ?", Boolean.class, id)).as("서버가 자동 해제").isTrue();
    }

    @Test
    @DisplayName("제목에서 Enter를 눌러도 저장·이동하지 않고, 여러 번 여닫아도 편집기 툴바는 하나이며 배경·ESC로 닫힌다")
    void enterDoesNotSubmitAndModalReopens() {
        open(alpha);
        openCreate();
        page.fill("#formTitle", "ps-입력");
        page.press("#formTitle", "Enter");
        awaitRequestBarrier(page);
        assertThat(writes).isEmpty();
        assertThat(page.url()).isEqualTo(url(""));
        assertThat(page.inputValue("#formTitle")).isEqualTo("ps-입력");
        page.keyboard().press("Escape");
        waitModalClosed();

        openCreate();
        assertThat(page.inputValue("#formTitle")).as("새로 열면 빈 폼").isEmpty();
        page.mouse().click(5, 5);
        waitModalClosed();
        openCreate();
        assertThat(page.locator(".post-editor .ql-toolbar").count()).isEqualTo(1);
        assertThat(dialogs).isEmpty();
    }

    @Test
    @DisplayName("저장 실패: 서버 문구를 보이고 입력을 그대로 둔다")
    void saveFailureKeepsInput() {
        open(alpha);
        openCreate();
        page.fill("#formTitle", "ps-실패글");
        typeBody("보존할 본문");
        page.route("**/admin/api/boards/" + alpha + "/posts", route -> route.fulfill(new Route.FulfillOptions().setStatus(400)
                .setContentType("application/json").setBody("{\"message\":\"서버가 거부했습니다.\"}")));
        page.click("#btnSave");
        waitVisibleText("detailAlert", "서버가 거부했습니다.");
        assertThat(page.inputValue("#formTitle")).isEqualTo("ps-실패글");
        assertThat(page.textContent(".post-editor .ql-editor")).isEqualTo("보존할 본문");
        assertThat(page.isDisabled("#btnSave")).isFalse();
        assertThat(page.isDisabled("#btnCancelEdit")).isFalse();
    }

    // ===================== 늦은 응답 =====================

    @Test
    @DisplayName("늦게 온 상세는 다른 글 모달이나 새 글 작성 폼을 덮지 않는다")
    void lateDetailIsIgnored() {
        long slow = post(alpha, "ps-느린글");
        post(alpha, "ps-빠른글");
        open(alpha);
        waitRow("ps-느린글");
        holdFirstResponse(page, "/posts/" + slow + "$");
        row("ps-느린글").locator(".js-detail").first().click();
        waitModalOpen();
        waitVisibleText("detailLoading", "상세 정보를 불러오는 중입니다.");
        page.waitForFunction("() => typeof window.__releaseHeld === 'function'");
        closeModal();
        openDetail("ps-빠른글");

        releaseHeldResponse(page);

        assertThat(page.textContent("#viewTitle")).isEqualTo("ps-빠른글");
        page.click("#btnEditMode");
        assertThat(page.inputValue("#formTitle")).isEqualTo("ps-빠른글");
    }

    @Test
    @DisplayName("상세 조회 실패는 모달 안에 서버 문구를 보이고 수정·삭제 버튼을 숨긴다")
    void detailFailureIsShownInModal() {
        long id = post(alpha, "ps-사라질글");
        open(alpha);
        waitRow("ps-사라질글");
        jdbc.update("UPDATE post SET deleted = 1 WHERE id = ?", id);
        row("ps-사라질글").locator(".js-detail").first().click();
        waitModalOpen();
        page.waitForFunction("() => !document.getElementById('detailAlert').classList.contains('d-none')");
        assertThat(hidden("detailContent")).isTrue();
        assertThat(hidden("detailLoading")).isTrue();
        assertThat(hidden("btnEditMode")).isTrue();
        assertThat(hidden("btnDelete")).isTrue();
        assertThat(hidden("attachmentSection")).isTrue();
        closeModal();
        post(alpha, "ps-다른글");
        page.click("#searchForm button[type=submit]");
        waitRow("ps-다른글");
        openDetail("ps-다른글");
        assertThat(hidden("detailAlert")).as("이전 오류가 남지 않는다").isTrue();
    }

    @Test
    @DisplayName("저장 응답 전에 다른 글을 열면 새 모달은 그대로이고 목록만 갱신된다. 저장 중에는 저장·취소가 잠겨 중복 제출이 없다")
    void lateSaveDoesNotTouchAnotherPost() {
        long a = post(alpha, "ps-저장글");
        post(alpha, "ps-새로연글");
        open(alpha);
        List<Route> held = hold("**/admin/api/boards/" + alpha + "/posts/" + a, "PATCH");
        openEdit("ps-저장글");
        page.fill("#formTitle", "ps-저장완료");
        page.click("#btnSave");
        page.waitForCondition(() -> !held.isEmpty());
        assertThat(page.isDisabled("#btnSave")).isTrue();
        assertThat(page.isDisabled("#btnCancelEdit")).isTrue();
        page.evaluate("() => document.getElementById('btnSave').click()");
        awaitRequestBarrier(page);
        assertThat(countWrites("PATCH")).isEqualTo(1);

        closeModal();
        openEdit("ps-새로연글");
        assertThat(page.isDisabled("#btnSave")).as("다른 글의 저장이 이 모달을 잠그지 않는다").isFalse();
        page.fill("#formTitle", "ps-편집중");
        held.get(0).resume();

        waitRow("ps-저장완료");
        assertThat(page.inputValue("#formTitle")).isEqualTo("ps-편집중");
        assertThat(hidden("postForm")).isFalse();
        assertThat(hidden("detailSuccess")).isTrue();
    }

    // ===================== 첨부 =====================

    @Test
    @DisplayName("첨부: 정책 안내·accept를 보이고 업로드·삭제가 목록에 반영된다. 파일을 고르지 않으면 안내한다")
    void attachmentUploadAndDelete() {
        jdbc.update("UPDATE board SET attach_max_count = 2, attach_max_size_mb = 3, attach_extensions = 'pdf,txt' WHERE id = ?", alpha);
        post(alpha, "ps-첨부글");
        open(alpha);
        openDetail("ps-첨부글");
        waitVisibleText("attachmentList", "첨부된 파일이 없습니다.");
        assertThat(page.textContent("#attachmentPolicyNote")).isEqualTo("최대 2개 · 파일당 3MB · 허용 형식: pdf, txt");
        assertThat(page.getAttribute("#attachmentFileInput", "accept")).isEqualTo(".pdf,.txt");
        page.click("#btnUploadAttachment");
        waitVisibleText("attachmentAlert", "업로드할 파일을 선택해주세요.");

        page.setInputFiles("#attachmentFileInput", file("메모.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)));
        page.click("#btnUploadAttachment");
        waitVisibleText("attachmentList", "메모.txt");
        assertThat(page.textContent("#attachmentList")).contains("(5 B · 다운로드 0회)");
        assertThat(page.inputValue("#attachmentFileInput")).isEmpty();
        assertThat(hidden("attachmentAlert")).isTrue();
        assertThat(page.getAttribute("#attachmentList a", "href")).matches("/admin/api/boards/" + alpha + "/posts/\\d+/attachments/\\d+/content");

        page.click(".js-delete-attachment");
        waitVisibleText("attachmentList", "첨부된 파일이 없습니다.");
        assertThat(dialogs).containsExactly("선택한 첨부파일을 삭제하시겠습니까?");

        page.setInputFiles("#attachmentFileInput", file("나쁨.exe", "application/octet-stream", new byte[]{1}));
        page.click("#btnUploadAttachment");
        page.waitForFunction("() => !document.getElementById('attachmentAlert').classList.contains('d-none')");
        assertThat(page.isDisabled("#btnUploadAttachment")).isFalse();
    }

    @Test
    @DisplayName("첨부 업로드 응답 전에 다른 글을 열면 새 모달의 파일 선택·목록·버튼을 건드리지 않는다")
    void lateUploadDoesNotTouchAnotherPost() {
        long a = post(alpha, "ps-업로드글");
        long b = post(alpha, "ps-옆글");
        attachment(b, "옆글첨부.txt");
        open(alpha);
        List<Route> held = hold("**/posts/" + a + "/attachments", "POST");
        openDetail("ps-업로드글");
        page.setInputFiles("#attachmentFileInput", file("늦음.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8)));
        page.click("#btnUploadAttachment");
        page.waitForCondition(() -> !held.isEmpty());
        assertThat(page.isDisabled("#btnUploadAttachment")).isTrue();
        closeModal();

        openDetail("ps-옆글");
        waitVisibleText("attachmentList", "옆글첨부.txt");
        assertThat(page.isDisabled("#btnUploadAttachment")).isFalse();
        page.setInputFiles("#attachmentFileInput", file("고른것.txt", "text/plain", "y".getBytes(StandardCharsets.UTF_8)));
        page.waitForResponse(r -> r.url().endsWith("/posts/" + a + "/attachments"), () -> held.get(0).resume());
        awaitRequestBarrier(page);
        assertThat(page.inputValue("#attachmentFileInput")).contains("고른것.txt");
        assertThat(page.textContent("#attachmentList")).contains("옆글첨부.txt").doesNotContain("늦음.txt");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_attachment WHERE post_id = ?", Integer.class, a)).isEqualTo(1);
    }

    @Test
    @DisplayName("첨부가 남은 글 삭제(409): 수정 권한이 없는 MANAGER에게는 권한 안내를 덧붙인다")
    void deleteConflictExplainsMissingUpdatePermission() {
        long id = post(alpha, "ps-첨부남음");
        attachment(id, "남은파일.txt");
        grant(alpha, "READ", "DELETE");
        loginAsManager();
        open(alpha);
        openDetail("ps-첨부남음");
        waitVisibleText("attachmentList", "남은파일.txt");
        assertThat(page.locator(".js-delete-attachment").count()).isZero();
        assertThat(hidden("attachmentUpload")).isTrue();
        assertThat(hidden("btnEditMode")).isTrue();
        assertThat(hidden("btnNewPost")).isTrue();
        page.click("#btnDelete");
        waitVisibleText("detailAlert", "첨부파일이 남아있어 삭제할 수 없습니다. 첨부를 먼저 삭제해주세요."
                + " 첨부 삭제에는 이 게시판의 수정 권한이 필요합니다. 관리자에게 요청하세요.");
        assertThat(page.locator(MODAL + ".show").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("첨부 목록 조회 실패는 첨부 영역 안에 안내한다")
    void attachmentListFailure() {
        long id = post(alpha, "ps-첨부실패");
        open(alpha);
        page.route("**/posts/" + id + "/attachments", Route::abort);
        openDetail("ps-첨부실패");
        waitVisibleText("attachmentAlert", "네트워크 연결을 확인해주세요.");
        waitVisibleText("attachmentList", "첨부된 파일이 없습니다.");
    }

    // ===================== 휴지통 =====================

    @Test
    @DisplayName("휴지통: 열 때 조회하고, 삭제한 글이 바로 나타나며, 복원하면 휴지통과 목록이 함께 갱신된다")
    void trashOpenDeleteRestore() {
        post(alpha, "ps-버릴글");
        open(alpha);
        assertThat(hidden("trashBody")).isTrue();
        page.click("#btnToggleTrash");
        waitVisibleText("trashEmpty", "삭제된 게시글이 없습니다.");
        assertThat(page.textContent("#btnToggleTrash")).isEqualTo("휴지통 닫기");
        assertThat(page.textContent("#trashSummary")).isEqualTo("삭제된 게시글 0건 · 0 / 1 페이지");

        openDetail("ps-버릴글");
        page.click("#btnDelete");
        waitModalClosed();
        waitVisibleText("trashTableBody", "ps-버릴글");
        waitNoRow("ps-버릴글");
        assertThat(page.textContent("#trashTableBody")).contains(admin.getUserId());

        page.click("#trashTableBody .js-restore");
        waitVisibleText("trashSuccess", "「ps-버릴글」 글을 비노출 상태로 복원했습니다. 공개하려면 목록에서 노출을 켜세요.");
        waitVisibleText("trashEmpty", "삭제된 게시글이 없습니다.");
        waitRow("ps-버릴글");
        assertThat(row("ps-버릴글").textContent()).contains("비노출");
        assertThat(dialogs.get(1)).startsWith("「ps-버릴글」 글을 복원하시겠습니까?\n비노출·상단 고정 해제");

        page.click("#btnToggleTrash");
        assertThat(hidden("trashBody")).isTrue();
        assertThat(page.textContent("#btnToggleTrash")).isEqualTo("휴지통 보기");
    }

    @Test
    @DisplayName("휴지통: 이미 사라진 글의 복원(404)은 안내하고 다시 읽는다. 게시판을 바꾸면 닫힌다")
    void trashRestoreNotFoundAndBoardSwitch() {
        long id = post(alpha, "ps-유령글");
        jdbc.update("UPDATE post SET deleted = 1 WHERE id = ?", id);
        open(alpha);
        page.click("#btnToggleTrash");
        waitVisibleText("trashTableBody", "ps-유령글");
        jdbc.update("UPDATE post SET deleted = 0 WHERE id = ?", id);
        page.click("#trashTableBody .js-restore");
        page.waitForFunction("() => !document.getElementById('trashAlert').classList.contains('d-none')");
        waitVisibleText("trashEmpty", "삭제된 게시글이 없습니다.");

        selectBoard(beta);
        assertThat(hidden("trashBody")).isTrue();
        page.click("#btnToggleTrash");
        waitVisibleText("trashEmpty", "삭제된 게시글이 없습니다.");
        assertThat(hidden("trashAlert")).isTrue();
    }

    @Test
    @DisplayName("휴지통: 복원 요청 중에 닫았다 다시 열거나 게시판을 바꿨다 돌아와도 복원 요청을 또 보내지 않는다")
    void restoreLockSurvivesReopenAndBoardSwitch() {
        long id = post(alpha, "ps-복원글");
        jdbc.update("UPDATE post SET deleted = 1 WHERE id = ?", id);
        open(alpha);
        List<Route> held = hold("**/posts/" + id + "/restoration", "POST");
        page.click("#btnToggleTrash");
        waitVisibleText("trashTableBody", "ps-복원글");
        page.click("#trashTableBody .js-restore");
        page.waitForCondition(() -> !held.isEmpty());

        page.click("#btnToggleTrash");
        page.click("#btnToggleTrash");
        waitVisibleText("trashTableBody", "ps-복원글");
        page.evaluate("() => document.querySelector('#trashTableBody .js-restore').click()");
        selectBoard(beta);
        selectBoard(alpha);
        page.click("#btnToggleTrash");
        waitVisibleText("trashTableBody", "ps-복원글");
        page.evaluate("() => document.querySelector('#trashTableBody .js-restore').click()");
        awaitRequestBarrier(page);
        assertThat(countWrites("POST")).isEqualTo(1);
        held.get(0).resume();
        waitRow("ps-복원글");
    }

    @Test
    @DisplayName("휴지통: 복원으로 마지막 페이지가 비면 이전 유효 페이지를 보인다")
    void trashFallsBackToLastPage() {
        for (int i = 1; i <= 11; i++) {
            post(alpha, "ps-휴지" + String.format("%02d", i));
        }
        jdbc.update("UPDATE post SET deleted = 1 WHERE board_id = ?", alpha);
        open(alpha);
        page.click("#btnToggleTrash");
        waitVisibleText("trashSummary", "11건 · 1 / 2");
        page.click("#trashPagination button:has-text('다음')");
        waitVisibleText("trashSummary", "11건 · 2 / 2");
        page.click("#trashTableBody .js-restore");
        waitVisibleText("trashSummary", "10건 · 1 / 1");
        assertThat(page.locator("#trashTableBody tr").count()).isEqualTo(10);
    }

    // ===================== 진입·권한 =====================

    @Test
    @DisplayName("?boardId=&id= 진입은 그 게시판의 상세를 열고 주소에서 두 값만 지운다. 콘솔 오류가 없다")
    void deepLinkOpensDetail() {
        long id = post(beta, "ps-링크글");
        page.navigate(url("?boardId=" + beta + "&id=" + id + "&keep=1"));
        waitModalOpen();
        waitVisibleText("viewTitle", "ps-링크글");
        waitVisibleText("attachmentList", "첨부된 파일이 없습니다.");
        assertThat(page.inputValue("#boardSelect")).isEqualTo(String.valueOf(beta));
        assertThat(page.url()).isEqualTo(url("?keep=1"));
        assertThat(page.textContent("#modalModeLabel")).isEqualTo("게시글 정보를 확인·수정합니다. (ps-beta)");
        page.click("#btnEditMode");
        assertThat(page.inputValue("#formTitle")).isEqualTo("ps-링크글");
        assertThat(consoleErrors).isEmpty();
    }

    @Test
    @DisplayName("볼 수 없는 게시판이나 형식이 틀린 번호의 진입은 상세를 열지 않는다")
    void invalidDeepLinkIsIgnored() {
        long id = post(beta, "ps-비공개링크");
        grant(alpha, "READ");
        loginAsManager();
        page.navigate(url("?boardId=" + beta + "&id=" + id));
        page.waitForFunction("() => { const s = document.getElementById('boardSelect'); return s && !s.disabled; }");
        waitListLoaded();
        assertThat(page.inputValue("#boardSelect")).isEqualTo(String.valueOf(alpha));
        assertThat(page.locator("#boardSelect option").count()).isEqualTo(1);
        assertThat(page.locator(MODAL + ".show").count()).isZero();

        page.navigate(url("?boardId=" + alpha + "&id=0x1"));
        page.waitForFunction("() => { const s = document.getElementById('boardSelect'); return s && !s.disabled; }");
        waitListLoaded();
        assertThat(page.locator(MODAL + ".show").count()).isZero();
    }

    @Test
    @DisplayName("조회 권한만 있는 MANAGER는 쓰기 버튼이 없고, 볼 게시판이 없으면 안내만 보인다")
    void readOnlyManagerSeesNoWriteButtons() {
        post(alpha, "ps-읽기글");
        grant(alpha, "READ");
        loginAsManager();
        open(alpha);
        assertThat(hidden("btnNewPost")).isTrue();
        assertThat(hidden("trashPanel")).isTrue();
        openDetail("ps-읽기글");
        assertThat(hidden("btnEditMode")).isTrue();
        assertThat(hidden("btnDelete")).isTrue();
        assertThat(hidden("attachmentUpload")).isTrue();
        assertThat(hidden("attachmentDisabledNote")).isTrue();
        assertThat(page.locator(".post-editor .ql-library-image").count()).as("MEDIA 조회 권한이 없으면 라이브러리 버튼이 없다").isZero();

        page.route("**/admin/api/members/me/boards", route -> route.fulfill(new Route.FulfillOptions()
                .setContentType("application/json").setBody("[]")));
        page.navigate(url(""));
        waitVisibleText("noBoardState", "조회할 수 있는 게시판이 없습니다.");
        assertThat(hidden("postArea")).isTrue();
        assertThat(page.isDisabled("#boardSelect")).isTrue();
    }

    @Test
    @DisplayName("쓰기 중지 게시판: 안내를 보이고 새 글·수정·업로드만 숨긴다(글 삭제·첨부 삭제는 유지). 첨부를 끈 게시판은 업로드 대신 안내한다")
    void writeStoppedBoardKeepsDeletes() {
        long id = post(alpha, "ps-보관글");
        attachment(id, "보관.txt");
        post(beta, "ps-첨부끔");
        jdbc.update("UPDATE board SET writable_yn = 0 WHERE id = ?", alpha);
        jdbc.update("UPDATE board SET attachment_yn = 0, public_yn = 0 WHERE id = ?", beta);
        open(alpha);
        assertThat(hidden("boardWriteStoppedNote")).isFalse();
        assertThat(hidden("btnNewPost")).isTrue();
        openDetail("ps-보관글");
        waitVisibleText("attachmentList", "보관.txt");
        assertThat(hidden("btnEditMode")).isTrue();
        assertThat(hidden("btnDelete")).isFalse();
        assertThat(hidden("attachmentUpload")).isTrue();
        assertThat(page.locator(".js-delete-attachment").count()).isEqualTo(1);
        closeModal();

        selectBoard(beta);
        assertThat(page.locator("#boardSelect option:checked").textContent()).isEqualTo("#" + beta + " ps-beta (비공개)");
        assertThat(hidden("boardWriteStoppedNote")).isTrue();
        assertThat(hidden("btnNewPost")).isFalse();
        openDetail("ps-첨부끔");
        assertThat(hidden("attachmentUpload")).isTrue();
        assertThat(hidden("attachmentPolicyNote")).isTrue();
        assertThat(hidden("attachmentDisabledNote")).isFalse();
    }

    @Test
    @DisplayName("모달을 연 뒤 게시판이 바뀌어도 모달의 요청은 연 시점의 게시판으로 나간다")
    void modalKeepsItsBoard() {
        long id = post(alpha, "ps-고정대상");
        open(alpha);
        openEdit("ps-고정대상");
        page.evaluate("id => { const s = document.getElementById('boardSelect'); s.value = String(id); s.dispatchEvent(new Event('change', { bubbles: true })); }", String.valueOf(beta));
        waitNoRow("ps-고정대상");
        page.fill("#formTitle", "ps-고정대상수정");
        page.click("#btnSave");
        waitVisibleText("detailSuccess", "저장되었습니다.");
        assertThat(writes.get(0)).startsWith("PATCH /admin/api/boards/" + alpha + "/posts/" + id);
        assertThat(page.textContent("#postTableBody")).doesNotContain("ps-고정대상");
    }

    // ===================== 본문·편집기 =====================

    @Test
    @DisplayName("제목·작성자·게시판·파일 이름의 HTML은 글자로 보이고, 본문은 정리된 서식만 그려지며 스크립트가 실행되지 않는다")
    void userInputIsTextAndBodyIsSanitized() {
        String evil = "<img src=x onerror=\"window.__xss=1\">";
        jdbc.update("UPDATE board SET name = ? WHERE id = ?", "ps-" + evil, alpha);
        long id = post(alpha, "ps-" + evil, "<p><strong>굵게</strong>" + evil + "<script>window.__xss=2</script></p>");
        jdbc.update("UPDATE post SET author_id = ? WHERE id = ?", evil, id);
        attachment(id, evil + ".txt");
        open(alpha);
        page.waitForFunction("() => document.querySelectorAll('#postTableBody .js-detail').length === 2");
        assertThat(page.locator("#postTableBody img").count()).isZero();
        assertThat(page.textContent("#postTableBody")).contains("ps-" + evil);
        assertThat(page.locator("#boardSelect option:checked").textContent()).contains(evil);
        openDetail("ps-" + evil);
        waitVisibleText("attachmentList", evil + ".txt");
        assertThat(page.textContent("#viewTitle")).isEqualTo("ps-" + evil);
        assertThat(page.textContent("#viewAuthor")).isEqualTo(evil);
        assertThat(page.textContent("#modalModeLabel")).contains(evil);
        assertThat(page.textContent("#viewContent strong")).isEqualTo("굵게");
        assertThat(page.locator("#viewContent script, #viewContent img").count()).isZero();

        page.click("#btnEditMode");
        page.fill("#formTitle", "ps-2" + evil);
        save();
        assertThat(page.textContent("#detailSuccess")).isEqualTo("저장되었습니다. (ps-2" + evil + ")");
        assertThat(page.locator("#detailSuccess img").count()).isZero();
        assertThat(page.evaluate("() => window.__xss")).isNull();
    }

    @Test
    @DisplayName("본문 이미지 업로드 중에는 저장이 막히고, 끝나면 이미지가 들어가 대표 선택지에 나타난다")
    void imageUploadBlocksSave() throws IOException {
        open(alpha);
        List<Route> held = hold("**/boards/" + alpha + "/content-images", "POST");
        openCreate();
        page.fill("#formTitle", "ps-업로드중");
        FileChooser chooser = page.waitForFileChooser(() -> page.click(".post-editor .ql-image"));
        chooser.setFiles(file("a.png", "image/png", ImageFileValidatorTest.png(12, 12)));
        page.waitForCondition(() -> !held.isEmpty());
        assertThat(hidden("editorBusy")).isFalse();
        assertThat(page.isDisabled("#btnSave")).isTrue();
        held.get(0).resume();
        page.waitForSelector(".post-editor .ql-editor img");
        assertThat(hidden("editorBusy")).isTrue();
        assertThat(page.isDisabled("#btnSave")).isFalse();
        assertThat(page.locator("#coverChoices .cover-choice").count()).isEqualTo(2);
        save();
        assertThat(page.locator("#viewContent img").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("본문 이미지 업로드 중 다른 글을 열면 늦게 올라온 이미지가 새 글에 들어가지 않는다")
    void lateImageDoesNotEnterAnotherPost() throws IOException {
        post(alpha, "ps-이미지없는글");
        open(alpha);
        List<Route> held = hold("**/boards/" + alpha + "/content-images", "POST");
        openCreate();
        FileChooser chooser = page.waitForFileChooser(() -> page.click(".post-editor .ql-image"));
        chooser.setFiles(file("a.png", "image/png", ImageFileValidatorTest.png(12, 12)));
        page.waitForCondition(() -> !held.isEmpty());
        closeModal();
        openEdit("ps-이미지없는글");
        assertThat(page.isDisabled("#btnSave")).isFalse();
        page.waitForResponse(r -> r.url().endsWith("/content-images"), () -> held.get(0).resume());
        awaitRequestBarrier(page);
        assertThat(page.locator(".post-editor .ql-editor img").count()).isZero();
        assertThat(hidden("coverGroup")).isTrue();
    }

    @Test
    @DisplayName("본문 이미지를 끈 게시판: 버튼을 숨기고 기존 이미지는 지키며, 갤러리 유형 안내를 보인다. 다른 게시판을 열면 원래대로다")
    void imageDisabledBoardKeepsExistingImages() {
        open(alpha);
        long image = uploadImage(alpha);
        writes.clear();
        imagePost(alpha, "ps-기존그림", image);
        post(beta, "ps-갤러리글");
        jdbc.update("UPDATE board SET body_image_yn = 0 WHERE id = ?", alpha);
        jdbc.update("UPDATE board SET board_type = 'GALLERY' WHERE id IN (?, ?)", alpha, beta);
        open(alpha);
        waitRow("ps-기존그림");
        openEdit("ps-기존그림");
        assertThat(page.locator(".post-editor .ql-editor img").count()).isEqualTo(1);
        assertThat(page.isVisible(".post-editor .ql-image")).isFalse();
        assertThat(hidden("imagePolicyNote")).isFalse();
        assertThat(page.textContent("#coverTypeNote")).startsWith("이 게시판은 갤러리형입니다.").doesNotContain("본문에 이미지를 넣은 뒤");
        page.fill("#formTitle", "ps-기존그림2");
        save();
        assertThat(keys(lastWrite("PATCH"))).containsExactly("title");
        closeModal();

        selectBoard(beta);
        openEdit("ps-갤러리글");
        assertThat(page.isVisible(".post-editor .ql-image")).isTrue();
        assertThat(hidden("imagePolicyNote")).isTrue();
        assertThat(page.textContent("#coverTypeNote")).endsWith("본문에 이미지를 넣은 뒤 아래 '대표 이미지'에서 고르세요.");
    }
}
