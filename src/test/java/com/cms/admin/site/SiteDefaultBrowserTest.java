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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사이트 설정 화면의 기본 사이트 지정 UI를 실제 브라우저로 고정한다(PLAN-site-menu.md PR ② P2-5 · P4): [기본으로 지정] 버튼은 활성 공개 비기본 사이트에서만,
 * 미저장 입력이 없을 때만 보이고, 누르면 PATCH 후 목록의 "기본" 표시가 옮겨 가며 버튼이 사라진다. MockMvc는 화면 JS를 실행하지 못한다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SiteDefaultBrowserTest extends BrowserTestSupport {

    private static final String KEY = "sdb-";

    @LocalServerPort int port;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;

    private Member admin;
    private BrowserContext context;
    private Page page;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "ui-admin-default", Role.ROLE_ADMIN);
        enableFormLogin(jdbc, admin);
        context = newContext();
        page = context.newPage();
        loginAsAdmin(page, port, admin);
    }

    @AfterEach
    void tearDown() {
        context.close();
        jdbc.update("DELETE FROM site WHERE site_key LIKE ?", KEY + "%");
        jdbc.update("UPDATE site SET default_yn = CASE WHEN id = 2 THEN 1 ELSE 0 END, use_yn = 1 WHERE id <= 2");
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE action_type LIKE 'SITE_%'");
    }

    private long insertSite(String suffix, boolean useYn) {
        jdbc.update("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, default_yn, create_date, update_date) "
                + "VALUES (?, ?, 'PUBLIC', 0, ?, 0, ?, ?)", KEY + suffix, "SDB " + suffix, useYn, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject("SELECT id FROM site WHERE site_key = ?", Long.class, KEY + suffix);
    }

    private void openSiteScreen() {
        page.navigate("http://localhost:" + port + "/admin/site/manage");
        page.waitForFunction("() => document.querySelectorAll('#siteTableBody tr').length >= 3");
    }

    /** 모달 상태(data-state)가 원하는 값이 될 때까지 기다린다. 요소의 보임 여부는 열림 초기에 거짓으로 통과하므로 쓰지 않는다. */
    private void waitModalState(String state) {
        page.waitForFunction("s => document.getElementById('siteModal').dataset.state === s", state);
    }

    /** 열린 모달이 있으면 [닫기]로 닫는다(미저장 입력이 없을 때) — 열려 있는 동안 목록 행은 배경 뒤에 있다. */
    private void closeModalIfOpen() {
        if ("open".equals(page.evaluate("() => document.getElementById('siteModal').dataset.state"))) {
            page.click("#siteBtnClose");
            waitModalState("closed");
        }
    }

    private void selectRow(long id) {
        closeModalIfOpen();
        page.click("#siteTableBody tr[data-site-id='" + id + "']");
        waitModalState("open");
        page.waitForFunction("() => document.getElementById('siteFormModeLabel').textContent.startsWith('사이트 수정')");
    }

    private boolean defaultButtonVisible() {
        return (Boolean) page.evaluate("() => !document.getElementById('siteBtnDefault').classList.contains('d-none')");
    }

    @Test
    @DisplayName("활성 공개 비기본 사이트에서만 [기본으로 지정]이 보이고, 누르면 기본 표시가 옮겨 가며 버튼이 사라진다")
    void makeDefaultFromTheSiteScreen() {
        long news = insertSite("news", true);
        long off = insertSite("off", false);
        openSiteScreen();

        selectRow(news);
        assertThat(defaultButtonVisible()).as("활성 공개 비기본").isTrue();

        selectRow(off);
        assertThat(defaultButtonVisible()).as("비활성 사이트").isFalse();
        selectRow(2);
        assertThat(defaultButtonVisible()).as("이미 기본인 main").isFalse();
        selectRow(1);
        assertThat(defaultButtonVisible()).as("관리자 사이트").isFalse();

        selectRow(news);
        page.fill("#siteNameInput", "SDB 이름 변경 중");
        page.waitForFunction("() => document.getElementById('siteBtnDefault').classList.contains('d-none')");
        assertThat(defaultButtonVisible()).as("미저장 입력이 있으면 숨는다").isFalse();
        page.fill("#siteNameInput", "SDB news");   // 원래 이름으로 되돌리면 다시 보인다
        page.waitForFunction("() => !document.getElementById('siteBtnDefault').classList.contains('d-none')");

        page.click("#siteBtnDefault");
        page.waitForFunction("() => document.getElementById('siteFormSuccess').textContent.includes('기본 사이트로 지정했습니다')");
        waitModalState("closed");                       // 지정이 끝나면 모달이 닫히고 안내는 페이지 상단에 남는다

        assertThat(jdbc.queryForObject("SELECT default_yn FROM site WHERE id = ?", Boolean.class, news)).isTrue();
        assertThat(jdbc.queryForObject("SELECT default_yn FROM site WHERE id = 2", Boolean.class)).isFalse();
        assertThat((String) page.evaluate("() => document.querySelector(\"#siteTableBody tr[data-site-id='" + news + "'] td:nth-child(6)\").textContent"))
                .isEqualTo("기본");
        assertThat((String) page.evaluate("() => document.querySelector(\"#siteTableBody tr[data-site-id='2'] td:nth-child(6)\").textContent"))
                .isEmpty();
        assertThat(defaultButtonVisible()).as("지정된 뒤에는 버튼이 사라진다").isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site WHERE default_yn = 1", Long.class)).isEqualTo(1L);
    }
}
