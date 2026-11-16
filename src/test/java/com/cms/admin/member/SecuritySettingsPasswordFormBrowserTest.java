package com.cms.admin.member;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보안 설정 화면의 비밀번호 변경 카드 JS를 실제 브라우저로 고정한다 — 처음에는 안내와 시작 버튼만 보이고, 버튼을 눌러야 폼이 열리며,
 * 취소하면 입력값·오류 문구를 지우고 안내로 돌아간다. 변경에 성공하면 이동 전까지 취소를 막고 로그인 화면으로 보낸다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecuritySettingsPasswordFormBrowserTest extends BrowserTestSupport {

    private static final String NEW_PASSWORD = "browser-test-new-password";

    @LocalServerPort int port;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;

    private Member manager;
    private BrowserContext context;
    private Page page;

    @BeforeEach
    void setUp() {
        manager = TestMembers.save(memberRepository, "ui-pw-form", Role.ROLE_MANAGER);
        enableFormLogin(jdbc, manager);
        context = newContext();
        page = context.newPage();
        loginAsAdmin(page, port, manager);
        page.navigate("http://localhost:" + port + "/admin/member/settings");
    }

    @AfterEach
    void tearDown() {
        context.close();
        jdbc.update("DELETE FROM visit_log WHERE visitor_user_id = ?", manager.getUserId());
        jdbc.update("DELETE FROM admin_action_log WHERE target_id = ? AND target_type = 'MEMBER'", manager.getId());
        TestMembers.delete(jdbc, List.of(manager.getId()));
    }

    private String storedPasswordHash() {
        return jdbc.queryForObject("SELECT pwd FROM member WHERE id = ?", String.class, manager.getId());
    }

    private String focusedId() {
        return (String) page.evaluate("() => document.activeElement && document.activeElement.id");
    }

    @Test
    @DisplayName("처음에는 폼이 숨겨져 있고, 시작 버튼으로 열고, 검증 오류 뒤 취소하면 입력값·오류가 지워진 채 안내로 돌아간다")
    void openValidateCancelReopen() {
        assertThat(page.locator("#passwordIntro").isVisible()).isTrue();
        assertThat(page.locator("#passwordForm").isVisible()).isFalse();

        page.locator("#btnPasswordStart").click();
        assertThat(page.locator("#passwordForm").isVisible()).isTrue();
        assertThat(page.locator("#passwordIntro").isVisible()).isFalse();
        assertThat(focusedId()).isEqualTo("currentPassword");

        // 확인 값 불일치는 화면 검증에서 막힌다(요청 전) — 저장된 비밀번호는 그대로다
        String hashBefore = storedPasswordHash();
        page.locator("#currentPassword").fill(TEST_PASSWORD);
        page.locator("#newPassword").fill(NEW_PASSWORD);
        page.locator("#confirmPassword").fill(NEW_PASSWORD + "x");
        page.locator("#btnChangePassword").click();
        assertThat(page.locator("#pageAlert").textContent()).isEqualTo("새 비밀번호와 확인 값이 일치하지 않습니다.");
        assertThat(storedPasswordHash()).isEqualTo(hashBefore);

        page.locator("#btnPasswordCancel").click();
        assertThat(page.locator("#passwordForm").isVisible()).isFalse();
        assertThat(page.locator("#passwordIntro").isVisible()).isTrue();
        assertThat(page.locator("#pageAlert").isVisible()).isFalse();
        assertThat(focusedId()).isEqualTo("btnPasswordStart");

        page.locator("#btnPasswordStart").click();
        assertThat(page.locator("#currentPassword").inputValue()).isEmpty();
        assertThat(page.locator("#newPassword").inputValue()).isEmpty();
        assertThat(page.locator("#confirmPassword").inputValue()).isEmpty();
    }

    @Test
    @DisplayName("열린 폼으로 변경에 성공하면 성공 문구가 뜨고 취소가 막히며, 로그인 화면으로 이동한다")
    void changePasswordThroughOpenedForm() {
        String hashBefore = storedPasswordHash();
        page.locator("#btnPasswordStart").click();
        page.locator("#currentPassword").fill(TEST_PASSWORD);
        page.locator("#newPassword").fill(NEW_PASSWORD);
        page.locator("#confirmPassword").fill(NEW_PASSWORD);
        page.locator("#btnChangePassword").click();

        page.locator("#pageSuccess:not(.d-none)").waitFor();
        assertThat(page.locator("#btnPasswordCancel").isDisabled()).isTrue();
        assertThat(storedPasswordHash()).isNotEqualTo(hashBefore);
        page.waitForURL("**/admin/login");
    }
}
