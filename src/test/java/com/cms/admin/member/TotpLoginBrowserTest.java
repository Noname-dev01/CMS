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
 * 2단계 인증(PLAN-totp-two-factor.md 결정 8)을 실제 브라우저로 고정한다 — 보안 설정 화면의 등록 JS(대기 발급 → QR·설정 키 표시 →
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

    @Test
    @DisplayName("미등록 ADMIN 강제 등록(PLAN-totp-required-admin.md 결정 2): 로그인 → 등록 화면(QR·설정 키) → 확정 → 복구 코드 10개 → 다시 로그인 → 코드 → 대시보드")
    void unenrolledAdminIsForcedToEnroll() {
        Member admin = TestMembers.save(memberRepository, "ui-totp-admin", Role.ROLE_ADMIN);
        // enableFormLogin은 ADMIN을 미리 등록하므로 비밀번호만 바꾼다 — 이 시험은 강제 등록 흐름 자체를 본다
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(TEST_PASSWORD), admin.getId());
        try {
            page.navigate(url("/admin/login"));
            page.locator("#exampleInputEmail").fill(admin.getUserId());
            page.locator("#exampleInputPassword").fill(TEST_PASSWORD);
            page.locator("button[type=submit]").click();
            page.waitForURL(url("/admin/login/totp-enrollment"));
            assertThat(page.title()).isEqualTo("2단계 인증 등록");
            String secret = page.locator("#enrollmentSecret").textContent();
            assertThat(secret).matches("[A-Z2-7]{32}");
            assertThat((Integer) page.locator("#enrollmentQr").evaluate("img => img.complete ? img.naturalWidth : 0")).isPositive();

            // 등록 전에는 대시보드로 갈 수 없다
            page.navigate(url("/admin"));
            page.waitForURL(url("/admin/login/totp-enrollment"));
            secret = page.locator("#enrollmentSecret").textContent();

            long step = new TotpCodeVerifier(clock).currentStep();
            page.locator("#enrollmentCode").fill(codeAt(secret, step));
            page.locator("#enrollmentPassword").fill(TEST_PASSWORD);
            // 완료 화면이 불러오는 스타일시트 응답 — 만료 표시만 남은 세션 쿠키로 요청하면 ConcurrentSessionFilter가 로그인으로 302한다(실기 검증에서 발견)
            List<String> stylesheetResponses = new java.util.concurrent.CopyOnWriteArrayList<>();
            page.onResponse(response -> {
                if (response.url().endsWith(".css") && response.url().startsWith(url(""))) {
                    stylesheetResponses.add(response.status() + " " + response.url());
                }
            });
            page.locator("#enrollmentForm button[type=submit]").click();
            page.locator("#recoveryCodeList").waitFor();
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.LOAD);
            assertThat(page.title()).isEqualTo("2단계 인증 등록 완료");
            assertThat(stylesheetResponses).isNotEmpty().allMatch(line -> line.startsWith("200 "));
            assertThat(page.locator("#recoveryCodeList").textContent().strip().split("\\R")).hasSize(10)
                    .allMatch(code -> code.matches("[A-Z2-7]{4}(-[A-Z2-7]{4}){5}"));

            page.locator("#btnRelogin").click();
            page.waitForURL("**/admin/login");
            page.locator("#exampleInputEmail").fill(admin.getUserId());
            page.locator("#exampleInputPassword").fill(TEST_PASSWORD);
            page.locator("button[type=submit]").click();
            page.waitForURL("**/admin/login/totp");
            page.locator("#totpCode").fill(codeAt(secret, step + 1));
            page.locator("button[type=submit]").first().click();
            page.waitForURL(url("/admin"));
            assertThat(page.title()).isEqualTo("관리자 대시보드");
        } finally {
            jdbc.update("DELETE FROM visit_log WHERE visitor_user_id = ?", admin.getUserId());
            jdbc.update("DELETE FROM admin_action_log WHERE target_id = ? AND target_type = 'MEMBER'", admin.getId());
            TestMembers.delete(jdbc, List.of(admin.getId()));
        }
    }

    @Test
    @DisplayName("FM5: 시험 도우미로 같은 ADMIN이 30초 안에 연달아 로그인해도 둘 다 대시보드에 닿는다(이미 쓴 코드로 막히지 않음)")
    void sameAdminCanLogInTwiceInARow() {
        Member admin = TestMembers.save(memberRepository, "ui-totp-admin", Role.ROLE_ADMIN);
        enableFormLogin(jdbc, admin);
        try {
            loginAsAdmin(page, port, admin);
            try (BrowserContext second = newContext()) {
                loginAsAdmin(second.newPage(), port, admin);
            }
        } finally {
            jdbc.update("DELETE FROM visit_log WHERE visitor_user_id = ?", admin.getUserId());
            TestMembers.delete(jdbc, List.of(admin.getId()));
        }
    }
}
