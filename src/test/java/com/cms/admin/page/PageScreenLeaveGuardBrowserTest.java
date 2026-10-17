package com.cms.admin.page;

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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정적 페이지 화면의 이탈 경고(PLAN-content-menu-unify.md U3-v3): 화면이 분리돼 사이드바 이동이 문서를 교체하므로, 저장 요청이 진행 중일 때도
 * {@code beforeunload} 경고가 나와야 한다(응답 처리 문맥이 사라지지 않게). 미저장 입력이 없고 진행 중 요청도 없으면 경고가 없다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PageScreenLeaveGuardBrowserTest extends BrowserTestSupport {

    @LocalServerPort int port;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;

    private Member admin;
    private BrowserContext context;
    private Page page;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "ui-leave-admin", Role.ROLE_ADMIN);
        enableFormLogin(jdbc, admin);
        context = newContext();
        page = context.newPage();
        loginAsAdmin(page, port, admin);
    }

    @AfterEach
    void tearDown() {
        context.close();
        jdbc.update("DELETE FROM site_page_revision WHERE page_id IN (SELECT id FROM site_page WHERE slug LIKE 'leave-%')");
        jdbc.update("DELETE FROM site_page WHERE slug LIKE 'leave-%'");
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE action_type LIKE 'SITE_PAGE_%'");
    }

    private void openPageScreen() {
        page.navigate("http://localhost:" + port + "/admin/site-page/manage");
        page.waitForSelector("#pageForm");
    }

    @Test
    @DisplayName("저장 요청이 진행 중일 때 다른 화면으로 이동하려 하면 beforeunload 경고가 나오고, 취소하면 화면과 입력이 유지된다")
    void leavingWhileSavingIsWarned() {
        openPageScreen();
        List<String> dialogTypes = new CopyOnWriteArrayList<>();
        page.onDialog(dialog -> {
            dialogTypes.add(dialog.type());
            dialog.dismiss();   // 이동 취소
        });
        page.fill("#pageSlug", "leave-save");
        page.fill("#pageTitle", "이탈 경고 시험");

        AtomicReference<Route> held = new AtomicReference<>();
        page.route("**/admin/api/site-pages", route -> {
            if (route.request().method().equals("POST") && held.compareAndSet(null, route)) {
                return;   // 응답을 붙잡아 저장이 진행 중인 상태를 유지한다
            }
            route.resume();
        });
        page.click("#pageBtnSave");
        page.waitForCondition(() -> held.get() != null);

        page.evaluate("() => { window.location.href = '/admin/site/manage'; }");
        page.waitForCondition(() -> dialogTypes.contains("beforeunload"));

        assertThat(page.url()).as("이동이 취소돼 화면이 그대로다").endsWith("/admin/site-page/manage");
        assertThat(page.inputValue("#pageTitle")).isEqualTo("이탈 경고 시험");
        held.get().resume();
    }

    @Test
    @DisplayName("미저장 입력도 진행 중 요청도 없으면 경고 없이 이동한다")
    void leavingCleanIsNotWarned() {
        openPageScreen();
        List<String> dialogTypes = new CopyOnWriteArrayList<>();
        page.onDialog(dialog -> {
            dialogTypes.add(dialog.type());
            dialog.accept();
        });

        page.click("#btnReload");   // 사용자 활성화를 만든다(활성화가 없으면 브라우저가 경고를 생략한다)
        page.evaluate("() => { window.location.href = '/admin/site/manage'; }");
        page.waitForURL("**/admin/site/manage");

        assertThat(dialogTypes).isEmpty();
    }
}
