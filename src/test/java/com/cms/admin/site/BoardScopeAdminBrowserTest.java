package com.cms.admin.site;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 게시판 관리 화면의 표시 대상·사이트 공지 게시판(PLAN-site-menu.md PR ④ T6)을 <b>실제 브라우저</b>로 고정한다 — MockMvc는 화면 JS를 재현하지 못한다:
 * ① 선택 박스로 고른 대상과 "공지 게시판으로 만들기"가 생성 요청에 실린다(체크박스는 사이트를 고르고 공개일 때만 켤 수 있다)
 * ② 수정 때 대상을 바꾸지 않으면 PATCH에 {@code siteScope}가 실리지 않고, 바꾸면 실린다
 * ③ 공지 게시판을 열면 표시 대상 박스가 잠기고 PATCH에도 실리지 않는다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BoardScopeAdminBrowserTest extends BrowserTestSupport {

    @LocalServerPort int port;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;

    private Member admin;
    private BrowserContext context;
    private Page page;
    private long alpha;
    private long beta;

    @BeforeEach
    void setUp() {
        cleanBoards();
        alpha = site("bui-alpha");
        beta = site("bui-beta");
        admin = TestMembers.save(memberRepository, "bui-scope-admin", Role.ROLE_ADMIN);
        enableFormLogin(jdbc, admin);
        context = newContext();
        page = context.newPage();
        loginAsAdmin(page, port, admin);
        page.navigate("http://localhost:" + port + "/admin/board/manage");
        page.waitForFunction("() => !document.getElementById('boardList').textContent.includes('불러오는 중')");
    }

    @AfterEach
    void tearDown() {
        context.close();
        cleanBoards();
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE action_type LIKE 'BOARD_%'");
    }

    private void cleanBoards() {
        jdbc.update("DELETE FROM board WHERE name LIKE 'bui-%'");
        jdbc.update("DELETE FROM site WHERE id > 2");
    }

    private long site(String key) {
        jdbc.update("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, default_yn) VALUES (?, ?, 'PUBLIC', 0, 1, 0)", key, key);
        return jdbc.queryForObject("SELECT id FROM site WHERE site_key = ?", Long.class, key);
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

    /** 이후 /admin/api/boards 쓰기 요청의 본문을 모은다. */
    private List<String> captureWrites() {
        List<String> bodies = new ArrayList<>();
        page.onRequest(request -> {
            if (request.url().contains("/admin/api/boards") && !"GET".equals(request.method())) {
                bodies.add(request.method() + " " + request.postData());
            }
        });
        return bodies;
    }

    @Test
    @DisplayName("생성: 고른 표시 대상이 요청에 실리고, 공지 게시판 체크박스는 사이트를 고르고 공개일 때만 켜진다")
    void createSendsScopeAndNoticeFlag() {
        List<String> writes = captureWrites();
        page.click("#btnNew");
        waitModalOpen();
        page.fill("#boardName", "bui-알파공지");

        assertThat(page.locator("#boardNotice").isDisabled()).as("공통 선택 중에는 비활성").isTrue();
        page.selectOption("#boardSiteScope", String.valueOf(alpha));
        assertThat(page.locator("#boardNotice").isDisabled()).as("사이트를 고르면 활성").isFalse();
        page.uncheck("#boardPublic");
        assertThat(page.locator("#boardNotice").isDisabled()).as("비공개면 비활성").isTrue();
        page.check("#boardPublic");
        page.check("#boardNotice");
        page.click("#btnSave");
        waitForText("pageSuccess", "게시판을 만들었습니다");

        assertThat(writes).hasSize(1);
        assertThat(writes.get(0)).contains("\"siteScope\":\"" + alpha + "\"").contains("\"noticeBoard\":true");
        assertThat(jdbc.queryForObject("SELECT board_key FROM board WHERE name = 'bui-알파공지'", String.class)).isEqualTo("NOTICE:" + alpha);
        // 저장 뒤 모달이 닫힌다. 다시 열면 수정 모드이고 공지 게시판이라 표시 대상 박스가 잠긴다
        waitModalClosed();
        page.waitForFunction("() => document.getElementById('boardList').textContent.includes('bui-알파공지')");
        openRow("bui-알파공지");
        assertThat(page.locator("#boardSiteScope").isDisabled()).isTrue();
        assertThat(page.locator("#boardNoticeGroup").getAttribute("class")).contains("d-none");
    }

    @Test
    @DisplayName("수정: 표시 대상을 바꾸지 않으면 PATCH에 siteScope가 실리지 않고, 바꾸면 실려 저장된다. 목록에 대상 배지가 보인다")
    void patchSendsScopeOnlyWhenChanged() {
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted, site_id) VALUES ('bui-전용', 1, 1, 0, ?)", alpha);
        long id = jdbc.queryForObject("SELECT id FROM board WHERE name = 'bui-전용'", Long.class);
        page.click("#btnReload");
        page.waitForFunction("() => document.getElementById('boardList').textContent.includes('bui-전용')");
        assertThat(page.locator("#boardList tr", new Page.LocatorOptions().setHasText("bui-전용")).textContent()).contains("bui-alpha");

        List<String> writes = captureWrites();
        openRow("bui-전용");
        assertThat(page.locator("#boardSiteScope").inputValue()).isEqualTo(String.valueOf(alpha));

        page.fill("#boardName", "bui-전용-개명");
        page.click("#btnSave");
        waitForText("pageSuccess", "게시판을 저장했습니다");
        waitModalClosed();
        assertThat(writes.get(0)).as("대상을 안 바꾸면 siteScope 없음").doesNotContain("siteScope");
        assertThat(jdbc.queryForObject("SELECT site_id FROM board WHERE id = ?", Long.class, id)).isEqualTo(alpha);

        // 저장 뒤 모달이 닫히므로 같은 행을 다시 열어 대상을 바꾼다
        page.waitForFunction("() => document.getElementById('boardList').textContent.includes('bui-전용-개명')");
        openRow("bui-전용-개명");
        page.selectOption("#boardSiteScope", String.valueOf(beta));
        page.click("#btnSave");
        page.waitForFunction("() => document.getElementById('boardList').textContent.includes('bui-beta')");
        assertThat(writes.get(1)).contains("\"siteScope\":\"" + beta + "\"");
        assertThat(jdbc.queryForObject("SELECT site_id FROM board WHERE id = ?", Long.class, id)).isEqualTo(beta);
    }

    @Test
    @DisplayName("공지 게시판을 열면 표시 대상 박스가 잠기고 저장해도 siteScope가 실리지 않는다 — 목록에 공지 배지")
    void noticeBoardLocksScope() {
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted, board_key, site_id) VALUES ('bui-공지', 1, 1, 0, ?, ?)",
                "NOTICE:" + alpha, alpha);
        page.click("#btnReload");
        page.waitForFunction("() => document.getElementById('boardList').textContent.includes('bui-공지')");
        openRow("bui-공지");

        assertThat(page.locator("#boardList tr", new Page.LocatorOptions().setHasText("bui-공지")).textContent()).contains("공지");
        assertThat(page.locator("#boardSiteScope").isDisabled()).isTrue();
        assertThat(page.locator("#boardSiteScope").inputValue()).isEqualTo(String.valueOf(alpha));

        List<String> writes = captureWrites();
        page.fill("#boardName", "bui-공지-개명");
        page.click("#btnSave");
        waitForText("pageSuccess", "게시판을 저장했습니다");
        assertThat(writes.get(0)).doesNotContain("siteScope");
        assertThat(jdbc.queryForObject("SELECT name FROM board WHERE board_key = ?", String.class, "NOTICE:" + alpha)).isEqualTo("bui-공지-개명");
    }
}
