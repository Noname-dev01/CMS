package com.cms.admin.board;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.BrowserTestSupport;
import com.cms.support.CmsTestApplication;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 게시판 정의 화면(ADMIN 전용)의 등록·수정 모달(PLAN-admin-form-modal.md)을 <b>실제 브라우저</b>로 고정한다 — MockMvc는 화면 JS·Bootstrap 모달을 재현하지 못한다:
 * ① 배경 클릭·ESC로 닫히지 않는다 ② 푸터 [저장]이 폼에 연결되어 브라우저 기본 검증이 요청 전에 막는다 ③ 등록·삭제는 모달을 닫고 목록에 반영한다
 * ④ 수정 중 게시판이 이미 없어졌을 때 저장(PATCH 404)은 모달을 닫고 안내하며 새 게시판 생성 모드로 바뀌지 않는다, 삭제(DELETE 404)는 성공으로 흡수된다
 * ⑤ 저장은 성공했는데 목록 재조회만 실패해도 다시 연 행이 저장 전 값이 아니다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BoardModalBrowserTest extends BrowserTestSupport {

    @LocalServerPort int port;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;

    private Member admin;
    private BrowserContext context;
    private Page page;

    @BeforeEach
    void setUp() {
        cleanBoards();
        admin = TestMembers.save(memberRepository, "bm-admin", Role.ROLE_ADMIN);
        enableFormLogin(jdbc, admin);
        context = newContext();
        page = context.newPage();
        loginAsAdmin(page, port, admin);
    }

    @AfterEach
    void tearDown() {
        context.close();
        cleanBoards();
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE action_type LIKE 'BOARD_%'");
    }

    private void cleanBoards() {
        jdbc.update("DELETE FROM board WHERE name LIKE 'bm-%'");
    }

    private long board(String name) {
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES (?, 1, 1, 0)", name);
        return jdbc.queryForObject("SELECT id FROM board WHERE name = ?", Long.class, name);
    }

    private void open() {
        page.navigate("http://localhost:" + port + "/admin/board/manage");
        page.waitForFunction("() => !document.getElementById('boardList').textContent.includes('불러오는 중')");
    }

    /** 모달이 완전히 열릴 때까지(열림 전환 끝) 기다린다. */
    private void waitModalOpen() {
        page.waitForFunction("() => modalState === 'open'");
    }

    /** 모달이 완전히 닫힐 때까지 기다린다. 요소의 "숨김" 대기는 열림 초기(display:none)에 거짓으로 통과하므로 쓰지 않는다. */
    private void waitModalClosed() {
        page.waitForFunction("() => modalState === 'closed'");
    }

    private void openRow(String name) {
        page.locator("#boardList tr", new Page.LocatorOptions().setHasText(name)).click();
        waitModalOpen();
    }

    private void waitForText(String elementId, String text) {
        page.waitForFunction("args => { const e = document.getElementById(args[0]);"
                + " return e && !e.classList.contains('d-none') && e.textContent.includes(args[1]); }", List.of(elementId, text));
    }

    @Test
    @DisplayName("모달은 배경 클릭·ESC로 닫히지 않고 ×로만 닫힌다")
    void staticBackdropClosesOnlyByButtons() {
        board("bm-static");
        open();
        openRow("bm-static");

        page.keyboard().press("Escape");
        page.mouse().click(5, 5);
        page.waitForTimeout(500);
        assertThat(page.locator("#boardModal.show").count()).as("ESC·배경 클릭으로는 닫히지 않는다").isEqualTo(1);

        page.click("#btnModalCloseX");
        waitModalClosed();
    }

    @Test
    @DisplayName("이름을 비운 채 [저장]하면 브라우저 검증이 막아 요청이 나가지 않고 모달이 유지된다")
    void emptyNameIsBlockedByBrowserValidation() {
        long id = board("bm-valid");
        open();
        openRow("bm-valid");

        AtomicInteger requests = new AtomicInteger();
        page.onRequest(request -> {
            if ("PATCH".equals(request.method()) && request.url().contains("/admin/api/boards/")) {
                requests.incrementAndGet();
            }
        });
        page.fill("#boardName", "");
        page.click("#btnSave");                             // 푸터 버튼이 form 속성으로 폼에 연결돼 있어야 검증이 동작한다
        page.waitForTimeout(500);

        // 부정 단언(요청 없음)만으로는 버튼이 폼에 연결되지 않은 경우도 통과하므로, 브라우저가 첫 무효 입력으로 포커스를 옮긴 것을 증거로 삼는다
        assertThat(page.evaluate("() => document.activeElement && document.activeElement.id")).isEqualTo("boardName");
        assertThat(requests.get()).isZero();
        assertThat(page.locator("#boardModal.show").count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM board WHERE id = ?", String.class, id)).isEqualTo("bm-valid");
    }

    @Test
    @DisplayName("등록하면 모달이 닫히고 목록에 나타나며, 삭제하면 모달이 닫히고 목록에서 사라진다")
    void createAndDeleteCloseTheModal() {
        open();
        page.click("#btnNew");
        waitModalOpen();
        page.fill("#boardName", "bm-created");
        page.click("#btnSave");
        waitForText("pageSuccess", "게시판을 만들었습니다");
        waitModalClosed();
        page.waitForFunction("() => document.getElementById('boardList').textContent.includes('bm-created')");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM board WHERE name = 'bm-created'", Integer.class)).isEqualTo(1);

        page.onDialog(dialog -> dialog.accept());          // confirm()
        openRow("bm-created");
        page.click("#btnDelete");
        waitForText("pageSuccess", "게시판을 삭제했습니다");
        waitModalClosed();
        page.waitForFunction("() => !document.getElementById('boardList').textContent.includes('bm-created')");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM board WHERE name = 'bm-created' AND deleted = 0", Integer.class)).isZero();
    }

    @Test
    @DisplayName("수정하던 게시판이 이미 없으면 저장(PATCH 404)은 모달을 닫고 페이지 상단에 안내하며, 새 게시판 생성 요청을 보내지 않는다")
    void patchNotFoundClosesTheModalWithoutSwitchingToCreate() {
        long id = board("bm-vanishing");
        open();
        openRow("bm-vanishing");
        List<String> writes = new ArrayList<>();
        page.onRequest(request -> {
            if (request.url().contains("/admin/api/boards") && !"GET".equals(request.method())) {
                writes.add(request.method());
            }
        });

        jdbc.update("DELETE FROM board WHERE id = ?", id);   // 다른 관리자가 지웠다
        page.fill("#boardName", "bm-renamed");
        page.click("#btnSave");

        waitForText("pageAlert", "찾을 수 없");
        waitModalClosed();
        assertThat(writes).as("PATCH 한 번뿐 — 생성 모드로 바뀌어 POST가 나가지 않는다").containsExactly("PATCH");
        page.waitForFunction("() => !document.getElementById('boardList').textContent.includes('bm-vanishing')");
    }

    @Test
    @DisplayName("삭제하려는 게시판이 이미 없으면(DELETE 404) '이미 삭제됨'으로 흡수되고 모달이 닫힌다")
    void deleteNotFoundIsAbsorbed() {
        long id = board("bm-gone");
        open();
        openRow("bm-gone");
        page.onDialog(dialog -> dialog.accept());

        jdbc.update("DELETE FROM board WHERE id = ?", id);
        page.click("#btnDelete");

        waitForText("pageSuccess", "이미 삭제된 게시판입니다");
        waitModalClosed();
        page.waitForFunction("() => !document.getElementById('boardList').textContent.includes('bm-gone')");
    }

    @Test
    @DisplayName("저장은 성공했는데 목록 재조회만 실패해도 목록은 저장 응답으로 보정되어 다시 연 행이 저장된 값이다")
    void snapshotIsPatchedWhenReloadFails() {
        board("bm-before");
        open();
        openRow("bm-before");

        // 이후 목록 조회(GET /admin/api/boards)만 네트워크 오류로 만든다 — 저장(PATCH)은 정상
        page.evaluate("() => { const orig = window.fetch; window.fetch = function (url, options) {"
                + " return String(url) === '/admin/api/boards' && !(options && options.method)"
                + " ? Promise.reject(new TypeError('network')) : orig(url, options); }; }");
        page.fill("#boardName", "bm-after");
        page.click("#btnSave");
        waitForText("pageAlert", "목록을 불러오지 못했습니다");
        waitModalClosed();

        page.waitForFunction("() => document.getElementById('boardList').textContent.includes('bm-after')");
        openRow("bm-after");
        assertThat(page.inputValue("#boardName")).as("저장 전 값(bm-before)이 아니다").isEqualTo("bm-after");
    }
}
