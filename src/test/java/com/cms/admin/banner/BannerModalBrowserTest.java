package com.cms.admin.banner;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionCache;
import com.cms.common.storage.FileStorage;
import com.cms.support.BrowserTestSupport;
import com.cms.support.CmsTestApplication;
import com.cms.support.MediaTestSupport;
import com.cms.support.TestMembers;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배너 관리 화면의 등록·수정 모달(PLAN-admin-form-modal.md)을 <b>실제 브라우저</b>로 고정한다 — MockMvc는 화면 JS·Bootstrap 모달을 재현하지 못한다:
 * ① 배경 클릭·ESC로 닫히지 않고 ×로만 닫힌다 ② 푸터 [등록]이 폼에 연결되어 브라우저 기본 검증이 요청 전에 막는다
 * ③ 등록·삭제는 모달을 닫고 목록에 반영한다 ④ 열린 폼의 대상이 목록에서 사라져도(늦게 온 목록 응답) 폼이 생성 모드로 비워지지 않는다
 * ⑤ 수정 권한 없이도 행을 열어 볼 수 있고 저장·[새 배너]는 권한대로 막힌다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BannerModalBrowserTest extends BrowserTestSupport {

    /** 1x1 PNG. */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

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
        cleanBanners();
        admin = TestMembers.save(memberRepository, "ui-bm-admin", Role.ROLE_ADMIN);
        manager = TestMembers.save(memberRepository, "ui-bm-manager", Role.ROLE_MANAGER);
        enableFormLogin(jdbc, admin);
        enableFormLogin(jdbc, manager);
        context = newContext();
        page = context.newPage();
    }

    @AfterEach
    void tearDown() {
        context.close();
        cleanBanners();
        TestMembers.delete(jdbc, List.of(admin.getId(), manager.getId()));
        cache.invalidate();
        jdbc.update("DELETE FROM admin_action_log WHERE action_type LIKE 'BANNER_%'");
    }

    private void cleanBanners() {
        jdbc.update("DELETE FROM banner");
        MediaTestSupport.cleanLibrary(jdbc, fileStorage);
    }

    private long banner(String title) {
        jdbc.update("INSERT INTO banner (title, use_yn, ord, create_date, update_date) VALUES (?, 1, 0, NOW(), NOW())", title);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM banner", Long.class);
        MediaTestSupport.attachImage(jdbc, "BANNER", id, "ui-bm-" + id + ".png", 10);
        return id;
    }

    private void grant(String... actions) {
        jdbc.update("DELETE FROM member_permission WHERE member_id = ?", manager.getId());
        for (String action : actions) {
            jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, 'BANNER', ?)", manager.getId(), action);
        }
        cache.invalidate();
    }

    private void open(Member member) {
        loginAsAdmin(page, port, member);
        page.navigate("http://localhost:" + port + "/admin/banner/manage");
        page.waitForFunction("() => !document.getElementById('bannerList').textContent.includes('불러오는 중')");
    }

    private void openRow(String title) {
        page.locator(".banner-row", new Page.LocatorOptions().setHasText(title)).click();
        page.waitForFunction("() => modalState === 'open'");   // 열림 전환(shown)이 끝나야 닫기 동작을 시험할 수 있다
    }

    private void waitModalClosed() {
        page.waitForSelector("#bannerModal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
    }

    private void waitForText(String elementId, String text) {
        page.waitForFunction("args => { const e = document.getElementById(args[0]);"
                + " return e && !e.classList.contains('d-none') && e.textContent.includes(args[1]); }", List.of(elementId, text));
    }

    @Test
    @DisplayName("모달은 배경 클릭·ESC로 닫히지 않고 ×로만 닫힌다")
    void staticBackdropClosesOnlyByButtons() {
        banner("static-banner");
        open(admin);
        openRow("static-banner");

        page.keyboard().press("Escape");
        page.mouse().click(5, 5);                          // 모달 바깥(배경)
        page.waitForTimeout(500);                          // 닫힘 전환(약 300ms)이 시작됐다면 이 안에 끝난다
        assertThat(page.locator("#bannerModal.show").count()).as("ESC·배경 클릭으로는 닫히지 않는다").isEqualTo(1);

        page.click("#btnModalCloseX");
        waitModalClosed();
    }

    @Test
    @DisplayName("제목을 비운 채 [저장]하면 브라우저 검증이 막아 요청이 나가지 않고 모달이 유지된다")
    void emptyTitleIsBlockedByBrowserValidation() {
        long id = banner("valid-banner");
        open(admin);
        openRow("valid-banner");

        AtomicInteger requests = new AtomicInteger();
        page.onRequest(request -> {
            if ("PUT".equals(request.method()) && request.url().contains("/admin/api/banners/")) {
                requests.incrementAndGet();
            }
        });
        page.fill("#bannerTitle", "");
        page.click("#btnSave");                             // 푸터 버튼이 form 속성으로 폼에 연결돼 있어야 검증이 동작한다
        page.waitForTimeout(500);

        // 부정 단언(요청 없음)만으로는 버튼이 폼에 연결되지 않은 경우도 통과하므로, 브라우저가 첫 무효 입력으로 포커스를 옮긴 것을 증거로 삼는다
        assertThat(page.evaluate("() => document.activeElement && document.activeElement.id")).isEqualTo("bannerTitle");
        assertThat(requests.get()).as("검증에 걸려 수정 요청이 나가지 않았다").isZero();
        assertThat(page.locator("#bannerModal.show").count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM banner WHERE id = ?", String.class, id)).isEqualTo("valid-banner");
    }

    @Test
    @DisplayName("등록하면 모달이 닫히고 목록에 나타나며, 삭제하면 모달이 닫히고 목록에서 사라진다")
    void createAndDeleteCloseTheModal() {
        open(admin);
        page.click("#btnNew");
        page.waitForFunction("() => modalState === 'open'");   // 열림 전환(shown)이 끝나야 닫기 동작을 시험할 수 있다
        page.fill("#bannerTitle", "created-banner");
        page.setInputFiles("#bannerImage", new FilePayload("one.png", "image/png", PNG));
        page.click("#btnSave");
        waitForText("pageSuccess", "배너를 등록했습니다");
        waitModalClosed();
        page.waitForFunction("() => document.getElementById('bannerList').textContent.includes('created-banner')");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM banner WHERE title = 'created-banner'", Integer.class)).isEqualTo(1);

        page.onDialog(dialog -> dialog.accept());          // confirm()
        openRow("created-banner");
        page.click("#btnDelete");
        waitForText("pageSuccess", "배너를 삭제했습니다");
        waitModalClosed();
        page.waitForFunction("() => !document.getElementById('bannerList').textContent.includes('created-banner')");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM banner WHERE title = 'created-banner'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("열어 둔 배너가 목록에서 사라진 상태로 목록 응답이 도착해도 폼은 생성 모드로 비워지지 않고 안내만 보인다")
    void lateListResponseDoesNotResetOpenForm() {
        long id = banner("vanishing-banner");
        open(admin);
        openRow("vanishing-banner");
        page.fill("#bannerTitle", "typed-by-user");

        jdbc.update("DELETE FROM content_image_ref WHERE owner_type = 'BANNER' AND owner_id = ?", id);
        jdbc.update("DELETE FROM banner WHERE id = ?", id);
        page.evaluate("() => loadList()");                  // 모달이 열리기 전에 시작된 목록 요청이 지금 도착한 것과 같다

        waitForText("modalAlert", "삭제되었거나");
        assertThat(page.inputValue("#bannerTitle")).as("입력이 그대로 남는다").isEqualTo("typed-by-user");
        assertThat(page.textContent("#formTitle")).as("생성 모드(새 배너)로 바뀌지 않는다").contains("수정");
    }

    @Test
    @DisplayName("READ 전용 MANAGER는 행을 열어 볼 수 있고 저장은 막히며 [새 배너]는 보이지 않는다")
    void readOnlyManagerCanOpenButNotSave() {
        banner("readonly-banner");
        grant("READ");
        open(manager);

        assertThat(page.locator("#btnNew").isVisible()).as("CREATE 권한이 없으면 [새 배너] 숨김").isFalse();
        openRow("readonly-banner");
        assertThat(page.locator("#btnSave").isDisabled()).as("UPDATE 권한이 없으면 저장 비활성").isTrue();
        assertThat(page.locator("#btnDelete").isVisible()).as("DELETE 권한이 없으면 삭제 숨김").isFalse();
    }

    @Test
    @DisplayName("READ+DELETE MANAGER는 수정 권한 없이도 모달의 [삭제]에 도달한다")
    void readDeleteManagerReachesDelete() {
        banner("deletable-banner");
        grant("READ", "DELETE");
        open(manager);

        openRow("deletable-banner");
        assertThat(page.locator("#btnDelete").isVisible()).isTrue();
        assertThat(page.locator("#btnSave").isDisabled()).isTrue();
    }
}
