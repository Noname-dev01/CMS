package com.cms.admin.member;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.BrowserTestSupport;
import com.cms.support.CmsTestApplication;
import com.cms.support.PasskeyTestSupport;
import com.cms.support.TestMembers;
import com.google.gson.JsonObject;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 패스키(PLAN-passkey-second-factor.md 결정 4·5·7)를 실제 브라우저와 Chromium 가상 인증기(CDP {@code WebAuthn})로 끝까지 고정한다 —
 * 공개키 생성·서명·서버 검증(webauthn4j)이 목 없이 돈다. 보안 설정에서 등록 → 비밀번호만 통과 → 코드 화면에서 <b>코드를 비운 채</b> 패스키로 대시보드 →
 * 삭제.
 *
 * <p>WebAuthn origin 비교는 scheme·host·port 정확 일치라(webauthn4j 0.31.6 {@code Origin.equals}) 기동 전에 빈 포트를 골라
 * {@code DEFINED_PORT}로 띄우고 같은 포트를 허용 origin으로 넣는다(이 클래스만 별도 컨텍스트).
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class PasskeyBrowserTest extends BrowserTestSupport {

    private static final int PORT = freePort();
    private static final Pattern CSRF_INPUT = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"|value=\"([^\"]+)\"\\s+name=\"_csrf\"");

    @DynamicPropertySource
    static void serverPort(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("cms.passkey.rp-id", () -> "localhost");
        registry.add("cms.passkey.allowed-origins", () -> "http://localhost:" + PORT);
    }

    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;

    private Member admin;
    private BrowserContext context;
    private Page page;
    private CDPSession cdp;
    private String authenticatorId;

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "ui-passkey", Role.ROLE_ADMIN);
        enableFormLogin(jdbc, admin); // ADMIN은 2단계 필수 — TOTP를 켜 둔다(패스키는 TOTP 사용 회원만)
        context = newContext();
        page = context.newPage();
        // 가상 인증기: 내장(플랫폼) 인증기, 상주 키·사용자 확인 지원, 확인 자동 승인
        cdp = context.newCDPSession(page);
        cdp.send("WebAuthn.enable");
        JsonObject options = new JsonObject();
        options.addProperty("protocol", "ctap2");
        options.addProperty("transport", "internal");
        options.addProperty("hasResidentKey", true);
        options.addProperty("hasUserVerification", true);
        options.addProperty("isUserVerified", true);
        options.addProperty("automaticPresenceSimulation", true);
        JsonObject params = new JsonObject();
        params.add("options", options);
        authenticatorId = cdp.send("WebAuthn.addVirtualAuthenticator", params).get("authenticatorId").getAsString();
    }

    /** 가상 인증기를 사용자 확인(UV)을 지원하지 않는 것으로 바꾼다 — 상주 키는 지원한다. */
    private void replaceWithAuthenticatorWithoutUserVerification() {
        JsonObject remove = new JsonObject();
        remove.addProperty("authenticatorId", authenticatorId);
        cdp.send("WebAuthn.removeVirtualAuthenticator", remove);
        JsonObject options = new JsonObject();
        options.addProperty("protocol", "ctap2");
        options.addProperty("transport", "internal");
        options.addProperty("hasResidentKey", true);
        options.addProperty("hasUserVerification", false);
        options.addProperty("automaticPresenceSimulation", true);
        JsonObject params = new JsonObject();
        params.add("options", options);
        authenticatorId = cdp.send("WebAuthn.addVirtualAuthenticator", params).get("authenticatorId").getAsString();
    }

    private long storedSignatureCount() {
        return jdbc.queryForObject("SELECT signature_count FROM member_passkey WHERE member_id = ?", Long.class, admin.getId());
    }

    @AfterEach
    void tearDown() {
        context.close();
        jdbc.update("DELETE FROM visit_log WHERE visitor_user_id = ?", admin.getUserId());
        jdbc.update("DELETE FROM admin_action_log WHERE target_id = ? AND target_type = 'MEMBER'", admin.getId());
        TestMembers.delete(jdbc, List.of(admin.getId()));
    }

    private static String url(String path) {
        return "http://localhost:" + PORT + path;
    }

    /** 비밀번호 단계만 실제 엔드포인트로 통과한다(→ 코드 화면 302). */
    private void passwordOnly() {
        APIRequestContext request = page.context().request();
        String loginUrl = url("/admin/login");
        APIResponse form = request.get(loginUrl);
        Matcher csrf = CSRF_INPUT.matcher(form.text());
        assertThat(csrf.find()).isTrue();
        APIResponse response = request.post(loginUrl, RequestOptions.create()
                .setForm(FormData.create()
                        .set("username", admin.getUserId())
                        .set("password", TEST_PASSWORD)
                        .set("_csrf", csrf.group(1) != null ? csrf.group(1) : csrf.group(2)))
                .setMaxRedirects(0));
        assertThat(response.status()).isEqualTo(302);
        assertThat(URI.create(response.headers().get("location")).getPath()).isEqualTo("/admin/login/totp");
    }

    private void registerPasskey(String label) {
        page.navigate(url("/admin/member/settings"));
        page.locator("#passkeyAddForm:not(.d-none)").waitFor();
        page.locator("#passkeyLabel").fill(label);
        page.locator("#passkeyPassword").fill(TEST_PASSWORD);
        page.locator("#btnPasskeyAdd").click();
        page.locator("#passkeySuccess:not(.d-none)").waitFor();
        page.locator("#passkeyList li").first().waitFor();
    }

    @Test
    @DisplayName("등록(가상 인증기) → 비밀번호만 통과 → 코드 화면에서 코드 없이 패스키로 대시보드 → 마지막 사용 기록 → 삭제")
    void registerLoginAndDelete() {
        loginAsAdmin(page, PORT, admin);
        registerPasskey("가상 인증기");
        assertThat(page.locator("#passkeyList li strong").textContent()).isEqualTo("가상 인증기");
        assertThat(PasskeyTestSupport.count(jdbc, admin.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log WHERE action_type = 'PASSKEY_REGISTER' AND target_id = ?",
                Integer.class, admin.getId())).isEqualTo(1);

        // 다시 로그인 — 같은 페이지(같은 가상 인증기)에서 쿠키만 비운다
        context.clearCookies();
        passwordOnly();
        page.navigate(url("/admin/login/totp"));
        assertThat(page.locator("#totpCode").inputValue()).isEmpty();
        page.locator("#btnPasskeyLogin").click();
        page.waitForURL(url("/admin"));
        assertThat(jdbc.queryForObject("SELECT last_used_at IS NOT NULL FROM member_passkey WHERE member_id = ?", Boolean.class, admin.getId()))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM member_totp WHERE member_id = ?", Integer.class, admin.getId())).isZero();

        // 삭제(확인 창 수락) → 목록 비움
        page.onDialog(dialog -> dialog.accept());
        page.navigate(url("/admin/member/settings"));
        page.locator("#passkeyList li button").first().click();
        page.locator("#passkeyEmpty:not(.d-none)").waitFor();
        assertThat(PasskeyTestSupport.count(jdbc, admin.getId())).isZero();
    }

    @Test
    @DisplayName("패스키가 없으면 코드 화면에 패스키 버튼이 없다 / 같은 인증기로 두 번째 등록은 브라우저가 거절해 서버로 가지 않는다")
    void noButtonWithoutPasskeyAndExcludeCredentials() {
        context.clearCookies();
        passwordOnly();
        page.navigate(url("/admin/login/totp"));
        assertThat(page.locator("#btnPasskeyLogin").count()).isZero();

        context.clearCookies();
        loginAsAdmin(page, PORT, admin);
        registerPasskey("첫 번째");
        page.locator("#passkeyLabel").fill("두 번째");
        page.locator("#passkeyPassword").fill(TEST_PASSWORD);
        page.locator("#btnPasskeyAdd").click();
        page.locator("#passkeyAlert:not(.d-none)").waitFor();
        assertThat(PasskeyTestSupport.count(jdbc, admin.getId())).isEqualTo(1);
    }
    // ==================== 패스키 단독 로그인 — PLAN-passkey-login.md(이슈 #280) ====================

    @Test
    @DisplayName("#280 골든 패스: 등록 → 로그아웃 → 로그인 화면 [패스키로 로그인] 한 번(아이디·비밀번호·코드 없음) → 대시보드, 방문 기록 1건")
    void passwordlessLogin() {
        loginAsAdmin(page, PORT, admin);
        registerPasskey("가상 인증기");
        jdbc.update("DELETE FROM visit_log WHERE visitor_user_id = ?", admin.getUserId());

        context.clearCookies();
        page.navigate(url("/admin/login"));
        assertThat(page.locator("input[name=username]").inputValue()).isEmpty();
        page.locator("#btnPasskeyLogin").click();
        page.waitForURL(url("/admin"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visit_log WHERE visitor_user_id = ?", Integer.class, admin.getUserId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_used_at IS NOT NULL FROM member_passkey WHERE member_id = ?", Boolean.class, admin.getId()))
                .isTrue();
    }

    @Test
    @DisplayName("#280 F4(리뷰 1-5): 사용자 확인(UV) 없는 실제 서명은 서버가 거부한다 — 브라우저 옵션을 preferred로 바꿔도 세션의 옵션은 required")
    void signatureWithoutUserVerificationRejected() {
        replaceWithAuthenticatorWithoutUserVerification();
        loginAsAdmin(page, PORT, admin);
        registerPasskey("UV 없는 인증기");

        // UV 없는 인증기는 상주 자격을 credProtect(사용자 확인 선택 + 자격 ID 목록 필요)로 만들어, 빈 allowCredentials로는 응답하지 않는다(실측 —
        // NotAllowedError). 공격자처럼 클라이언트가 자격 ID를 직접 넣는다 — 서버 옵션의 allowCredentials가 비어 있으면 라이브러리는 목록을 검사하지 않는다.
        String credentialId = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                jdbc.queryForObject("SELECT credential_id FROM member_passkey WHERE member_id = ?", byte[].class, admin.getId()));
        context.clearCookies();
        page.navigate(url("/admin/login"));
        page.evaluate("id => { const f = document.getElementById('passkeyForm');"
                + " const o = JSON.parse(f.getAttribute('data-options')); o.userVerification = 'preferred';"
                + " o.allowCredentials = [{ type: 'public-key', id: id }];"
                + " f.setAttribute('data-options', JSON.stringify(o)); }", credentialId);
        page.locator("#btnPasskeyLogin").click();
        page.waitForURL(url("/admin/login?passkeyError"));

        assertThat(page.locator(".alert-danger").textContent()).contains("패스키로 로그인하지 못했습니다");
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM member_totp WHERE member_id = ?", Integer.class, admin.getId())).isZero();
    }

    @Test
    @DisplayName("#280 결정 8: 저장된 서명 횟수보다 작은 실제 서명(복제 의심)은 거부하고 저장 횟수·실패 횟수를 바꾸지 않는다")
    void signatureCounterRegressionRejected() {
        loginAsAdmin(page, PORT, admin);
        registerPasskey("가상 인증기");
        jdbc.update("UPDATE member_passkey SET signature_count = 1000000 WHERE member_id = ?", admin.getId());

        context.clearCookies();
        page.navigate(url("/admin/login"));
        page.locator("#btnPasskeyLogin").click();
        page.waitForURL(url("/admin/login?passkeyError"));

        assertThat(storedSignatureCount()).isEqualTo(1000000L);
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM member_totp WHERE member_id = ?", Integer.class, admin.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT last_used_at IS NULL FROM member_passkey WHERE member_id = ?", Boolean.class, admin.getId()))
                .isTrue();
    }
}
