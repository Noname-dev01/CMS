package com.cms.admin.member;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.totp.Base32;
import com.cms.admin.member.totp.TotpCodeVerifier;
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

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2단계 인증(PLAN-totp-two-factor.md 결정 8)을 실제 브라우저로 고정한다 — 내 정보 설정 화면의 등록 JS(대기 발급 → QR·설정 키 표시 →
 * 확정 → 복구 코드 1회 표시)와, 등록 뒤 실제 로그인 화면 → 2단계 코드 화면 → 대시보드 흐름. 이미 쓴 코드는 실패가 아니라 안내로 끝난다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TotpLoginBrowserTest extends BrowserTestSupport {

    @LocalServerPort int port;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private Member manager;
    private BrowserContext context;
    private Page page;

    @BeforeEach
    void setUp() {
        manager = TestMembers.save(memberRepository, "ui-totp", Role.ROLE_MANAGER);
        enableFormLogin(jdbc, manager);
        context = newContext();
        page = context.newPage();
    }

    @AfterEach
    void tearDown() {
        context.close();
        jdbc.update("DELETE FROM visit_log WHERE visitor_user_id = ?", manager.getUserId());
        jdbc.update("DELETE FROM admin_action_log WHERE target_id = ? AND target_type = 'MEMBER'", manager.getId());
        TestMembers.delete(jdbc, List.of(manager.getId()));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private String codeAt(String base32Secret, long step) {
        return TotpCodeVerifier.codeAt(Base32.decode(base32Secret), step);
    }

    @Test
    @DisplayName("설정 화면 등록(QR·설정 키 → 확정 → 복구 코드 10개) → 다시 로그인 → 이미 쓴 코드는 안내 → 다음 코드로 대시보드")
    void enrollThenLoginWithSecondFactor() {
        loginAsAdmin(page, port, manager);
        page.navigate(url("/admin/member/settings"));
        page.locator("#btnTotpStart").click();
        page.locator("#totpEnroll:not(.d-none)").waitFor();
        String secret = page.locator("#totpSecret").textContent();
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat((Integer) page.locator("#totpQr").evaluate("img => img.complete ? img.naturalWidth : 0")).isPositive();

        long step = new TotpCodeVerifier(clock).currentStep();
        page.locator("#totpConfirmCode").fill(codeAt(secret, step));
        page.locator("#totpConfirmPassword").fill(TEST_PASSWORD);
        page.locator("#btnTotpConfirm").click();
        page.locator("#totpRecovery:not(.d-none)").waitFor();
        assertThat(page.locator("#totpRecoveryList").textContent().split("\n")).hasSize(10)
                .allMatch(code -> code.matches("[A-Z2-7]{4}(-[A-Z2-7]{4}){5}"));

        // 등록으로 본인 세션이 만료됐다 — 실제 로그인 화면으로 다시 로그인하면 2단계 화면이다
        page.locator("#btnTotpRelogin").click();
        page.waitForURL("**/admin/login");
        page.locator("#exampleInputEmail").fill(manager.getUserId());
        page.locator("#exampleInputPassword").fill(TEST_PASSWORD);
        page.locator("button[type=submit]").click();
        page.waitForURL("**/admin/login/totp");

        // 등록에 쓴 코드(같은 스텝)는 실패가 아니라 "다음 코드를 기다리라"는 안내다
        page.locator("#totpCode").fill(codeAt(secret, step));
        page.locator("button[type=submit]").first().click();
        page.waitForURL("**/admin/login/totp?reused");
        assertThat(page.locator("#totpReused").isVisible()).isTrue();
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM member_totp WHERE member_id = ?", Integer.class, manager.getId())).isZero();

        // 다음 스텝 코드(허용 범위 +1)로 통과
        page.locator("#totpCode").fill(codeAt(secret, step + 1));
        page.locator("button[type=submit]").first().click();
        page.waitForURL(url("/admin"));
        assertThat(page.title()).isEqualTo("관리자 대시보드");
    }
}
