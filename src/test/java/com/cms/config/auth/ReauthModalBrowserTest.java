package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionCache;
import com.cms.support.BrowserTestSupport;
import com.cms.support.CmsTestApplication;
import com.cms.support.TestMembers;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재인증 모달(PLAN-sensitive-reauthentication.md 쟁점 4, F9)을 <b>실제 브라우저·실제 서버</b>로 고정한다 — 쿠키로 복원한 세션에서 민감 작업을
 * 저장하면 그 자리에서 비밀번호를 묻고 같은 요청을 다시 보낸다. MockMvc는 화면 JS·Bootstrap 모달을 재현하지 못한다.
 * 쿠키 복원은 "로그인 상태 유지"를 켠 폼 로그인 뒤 세션 쿠키만 지워 만든다(브라우저 재시작 모사).
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReauthModalBrowserTest extends BrowserTestSupport {

    private static final Pattern CSRF_INPUT = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"|value=\"([^\"]+)\"\\s+name=\"_csrf\"");

    @LocalServerPort int port;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PermissionCache cache;

    private Member admin;
    private Member manager;
    private BrowserContext context;
    private Page page;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "ui-ra-admin", Role.ROLE_ADMIN);
        manager = TestMembers.save(memberRepository, "ui-ra-manager", Role.ROLE_MANAGER);
        enableFormLogin(jdbc, admin);
        enableFormLogin(jdbc, manager);
        context = newContext();
        page = context.newPage();
        page.onRequest(r -> requests.add(r.method() + " " + r.url().replace("http://localhost:" + port, "")));
    }

    @AfterEach
    void tearDown() {
        context.close();
        for (Member m : List.of(admin, manager)) {
            jdbc.update("DELETE FROM persistent_logins WHERE username = ?", m.getUserId());
        }
        TestMembers.delete(jdbc, List.of(admin.getId(), manager.getId()));
        cache.invalidate();
        jdbc.update("DELETE FROM admin_action_log WHERE action_type = 'PERMISSION_UPDATE'");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** "로그인 상태 유지"를 켠 폼 로그인 → 세션 쿠키를 지우고 remember-me 쿠키만 남긴다(다음 요청이 쿠키로 복원된다). */
    private void rememberedSession(Member member) {
        APIRequestContext request = context.request();
        APIResponse form = request.get(url("/admin/login"));
        Matcher csrf = CSRF_INPUT.matcher(form.text());
        assertThat(csrf.find()).isTrue();
        APIResponse login = request.post(url("/admin/login"), RequestOptions.create()
                .setForm(FormData.create().set("username", member.getUserId()).set("password", TEST_PASSWORD).set("remember-me", "on")
                        .set("_csrf", csrf.group(1) != null ? csrf.group(1) : csrf.group(2)))
                .setMaxRedirects(0));
        assertThat(login.status()).isEqualTo(302);
        List<Cookie> rememberMe = context.cookies().stream().filter(c -> "remember-me".equals(c.name)).toList();
        assertThat(rememberMe).hasSize(1);
        context.clearCookies();
        context.addCookies(rememberMe);
    }

    private long count(String prefix) {
        return requests.stream().filter(r -> r.startsWith(prefix)).count();
    }

    private void openPermissionDraft() {
        page.navigate(url("/admin/permission/manage"));
        assertThat(page.locator("#reauthNotice").isVisible()).as("쿠키 복원 세션 안내").isTrue();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(Pattern.compile(manager.getUserId()))).first().click();
        Locator bannerRead = page.getByRole(AriaRole.CHECKBOX, new Page.GetByRoleOptions().setName("배너 조회"));
        bannerRead.waitFor();
        bannerRead.check(new Locator.CheckOptions().setForce(true));
    }

    private int grants() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM member_permission WHERE member_id = ?", Integer.class, manager.getId());
    }

    @Test
    @DisplayName("권한 저장: 모달 → 오답 문구 → 확인 연타는 검증 1회 → 정답이면 같은 화면에서 저장 완료(원 요청 1회 재시도, 초안 유지)")
    void permissionSave_promptsAndRetriesInPlace() {
        rememberedSession(admin);
        openPermissionDraft();

        page.locator("#btnSave").click();
        page.locator("#reauthModal.show").waitFor();
        page.locator("#reauthPassword").fill("wrong-password");
        page.locator("#reauthSubmit").click();
        page.waitForFunction("() => !document.getElementById('reauthError').classList.contains('d-none')");
        assertThat(page.locator("#reauthError").textContent()).contains("비밀번호가 올바르지 않습니다");

        page.locator("#reauthPassword").fill(TEST_PASSWORD);
        // 같은 태스크에서 두 번 제출 — 확인 중 잠금으로 검증 요청은 한 번만 나간다
        page.evaluate("() => { const f = document.getElementById('reauthForm'); f.requestSubmit(); f.requestSubmit(); }");
        page.waitForFunction("() => !document.getElementById('pageSuccess').classList.contains('d-none')");

        assertThat(page.locator("#pageSuccess").textContent()).contains("권한을 저장했습니다");
        assertThat(page.locator("#reauthModal.show").count()).isZero();
        assertThat(page.url()).endsWith("/admin/permission/manage");
        assertThat(grants()).isEqualTo(1);
        assertThat(count("POST /admin/api/members/me/reauthentication")).as("오답 1 + 정답 1").isEqualTo(2);
        assertThat(count("PUT /admin/api/members/" + manager.getId() + "/permissions")).as("원 요청 1 + 재시도 1").isEqualTo(2);
    }

    @Test
    @DisplayName("취소하면 원래 403의 서버 문구가 화면에 보이고 저장되지 않는다")
    void cancel_showsServerMessageAndDoesNotSave() {
        rememberedSession(admin);
        openPermissionDraft();

        page.locator("#btnSave").click();
        page.locator("#reauthModal.show").waitFor();
        page.locator("#reauthCancel").click();
        page.waitForFunction("() => !document.getElementById('pageAlert').classList.contains('d-none')");

        assertThat(page.locator("#pageAlert").textContent()).contains("비밀번호 확인이 필요합니다");
        assertThat(grants()).isZero();
        assertThat(count("PUT /admin/api/members/" + manager.getId() + "/permissions")).isEqualTo(1);
        assertThat(count("POST /admin/api/members/me/reauthentication")).isZero();
    }

    @Test
    @DisplayName("동시 403 두 건은 모달 1개·검증 1회로 묶이고, 재인증 뒤 늦게 도착한 403은 모달 없이 한 번만 다시 보낸다")
    void concurrentAndLate403_shareOnePrompt() {
        rememberedSession(manager);
        page.navigate(url("/admin/member/info"));
        String body = "{\"userName\":\"" + manager.getUserName() + "\",\"email\":\"" + manager.getEmail() + "\"}";
        // 세 번째 요청의 응답만 붙잡아 두었다가 재인증이 끝난 뒤 넘긴다(늦게 도착한 403)
        page.evaluate("body => {"
                + "  const realFetch = window.fetch; let calls = 0; let release; window.__release = new Promise(r => release = r); window.__doRelease = release;"
                + "  window.fetch = function (url, init) { const p = realFetch.apply(this, arguments);"
                + "    if (url === '/admin/api/members/me' && init && init.method === 'PATCH' && ++calls === 3) { return p.then(r => window.__release.then(() => r)); }"
                + "    return p; };"
                + "  const headers = { 'Content-Type': 'application/json' };"
                + "  headers[document.querySelector('meta[name=_csrf_header]').content] = document.querySelector('meta[name=_csrf]').content;"
                + "  const send = () => CmsReauth.fetch('/admin/api/members/me', { method: 'PATCH', headers: headers, body: body }).then(r => r.status);"
                + "  Promise.all([send(), send(), send()]).then(s => { window.__statuses = s; });"
                + "}", body);
        page.locator("#reauthModal.show").waitFor();
        assertThat(page.locator("#reauthModal").count()).isEqualTo(1);
        page.locator("#reauthPassword").fill(TEST_PASSWORD);
        page.locator("#reauthSubmit").click();
        page.waitForFunction("() => !document.querySelector('#reauthModal.show')");
        page.evaluate("() => window.__doRelease()");
        // 시간 제한이 있는 대기 — 늦은 403이 모달을 다시 띄우는 회귀는 결과가 끝내 나오지 않아 멈추지 않고 실패한다
        page.waitForFunction("() => Array.isArray(window.__statuses)", null, new Page.WaitForFunctionOptions().setTimeout(15_000));
        Object statuses = page.evaluate("() => window.__statuses");
        assertThat(page.locator("#reauthModal.show").count()).as("모달은 다시 뜨지 않는다").isZero();

        assertThat(statuses.toString()).isEqualTo("[200, 200, 200]");
        assertThat(count("POST /admin/api/members/me/reauthentication")).isEqualTo(1);
        assertThat(count("PATCH /admin/api/members/me")).as("원 요청 3 + 각 1회 재시도 3").isEqualTo(6);
    }

    /** 전환을 켠 상태에서 민감 요청 하나를 보낸다(취소하면 403 상태 코드가 window.__status에 남는다). */
    private void sendSensitive(String body) {
        page.evaluate("body => {"
                + "  const headers = { 'Content-Type': 'application/json' };"
                + "  headers[document.querySelector('meta[name=_csrf_header]').content] = document.querySelector('meta[name=_csrf]').content;"
                + "  window.__status = undefined;"
                + "  CmsReauth.fetch('/admin/api/members/me', { method: 'PATCH', headers: headers, body: body }).then(r => { window.__status = r.status; });"
                + "}", body);
    }

    private String meBody(Member m) {
        return "{\"userName\":\"" + m.getUserName() + "\",\"email\":\"" + m.getEmail() + "\"}";
    }

    @Test
    @DisplayName("표시 전환 중(shown 전)에는 취소가 먹지 않고, 완전히 열린 뒤 취소하면 모달·배경막이 남지 않는다(코드 리뷰 1라운드)")
    void cancelDuringShowTransition_isIgnoredThenWorks() {
        rememberedSession(manager);
        page.navigate(url("/admin/member/info"));
        restoreModalTransitions(page);
        // 모달에 show 클래스가 붙는 순간(= shown 전 전환 중) 취소 버튼을 누른다
        page.evaluate("() => { new MutationObserver((records, observer) => {"
                + "  const m = document.getElementById('reauthModal');"
                + "  if (m && m.classList.contains('show')) { window.__earlyCancelDisabled = document.getElementById('reauthCancel').disabled;"
                + "    document.getElementById('reauthCancel').click(); observer.disconnect(); }"
                + "}).observe(document.body, { subtree: true, attributes: true, attributeFilter: ['class'], childList: true }); }");
        sendSensitive(meBody(manager));
        page.waitForFunction("() => window.__earlyCancelDisabled !== undefined");
        assertThat(page.evaluate("() => window.__earlyCancelDisabled")).isEqualTo(true);

        page.locator("#reauthCancel").click();      // 완전히 열린 뒤(활성화될 때까지 기다림) 취소
        page.waitForFunction("() => window.__status === 403");
        page.waitForFunction("() => !document.querySelector('#reauthModal.show') && !document.querySelector('.modal-backdrop')");
    }

    @Test
    @DisplayName("닫힘 전환 중에 새 재인증 403이 오면 닫힘이 끝난 뒤 모달이 다시 떠서 정상 처리된다(코드 리뷰 1라운드)")
    void newPromptDuringHideTransition_opensAfterHidden() {
        rememberedSession(manager);
        page.navigate(url("/admin/member/info"));
        restoreModalTransitions(page);
        sendSensitive(meBody(manager));
        page.locator("#reauthCancel").click();
        // 취소 직후(닫힘 전환 중) 다음 민감 요청 — 403이 닫힘 전환보다 먼저 올 수 있다
        sendSensitive(meBody(manager));
        page.waitForFunction("() => document.querySelector('#reauthModal.show') && !document.getElementById('reauthPassword').disabled");
        page.locator("#reauthPassword").fill(TEST_PASSWORD);
        page.locator("#reauthSubmit").click();
        page.waitForFunction("() => window.__status === 200");
        page.waitForFunction("() => !document.querySelector('#reauthModal.show') && !document.querySelector('.modal-backdrop')");
    }

    @Test
    @DisplayName("검증 요청이 응답하지 않으면 제한 시간 뒤 모달이 다시 조작 가능해지고, 취소하면 원 요청이 403으로 끝난다(코드 리뷰 4라운드)")
    void unansweredVerification_timesOutAndRecovers() {
        rememberedSession(manager);
        page.navigate(url("/admin/member/info"));
        page.route("**/admin/api/members/me/reauthentication", route -> {
            // 응답하지 않는다 — 서버·프록시가 연결을 붙잡은 상황
        });
        sendSensitive(meBody(manager));
        page.locator("#reauthPassword").fill(TEST_PASSWORD);
        page.locator("#reauthSubmit").click();

        page.waitForFunction("() => !document.getElementById('reauthError').classList.contains('d-none')"
                + " && !document.getElementById('reauthCancel').disabled", null, new Page.WaitForFunctionOptions().setTimeout(20_000));
        assertThat(page.locator("#reauthError").textContent()).contains("응답이 없습니다");

        page.locator("#reauthCancel").click();
        page.waitForFunction("() => window.__status === 403");
        page.waitForFunction("() => !document.querySelector('#reauthModal.show') && !document.querySelector('.modal-backdrop')");
    }

    @Test
    @DisplayName("다섯 번째 오답이면 계정이 잠기고 로그인 화면으로 이동한다")
    void fifthWrongPassword_redirectsToLogin() {
        rememberedSession(manager);
        page.navigate(url("/admin/member/settings"));
        page.locator("input[type=password]").nth(0).fill(TEST_PASSWORD);
        page.locator("input[type=password]").nth(1).fill("Reauth-Browser-New-Password-2026!");
        page.locator("input[type=password]").nth(2).fill("Reauth-Browser-New-Password-2026!");
        page.locator("form button[type=submit], #changePasswordButton").first().click();
        page.locator("#reauthModal.show").waitFor();

        for (int i = 1; i <= 4; i++) {
            page.locator("#reauthPassword").fill("wrong-" + i);
            page.locator("#reauthSubmit").click();
            page.waitForFunction("() => !document.getElementById('reauthError').classList.contains('d-none') && !document.getElementById('reauthSubmit').disabled");
            page.evaluate("() => document.getElementById('reauthError').classList.add('d-none')");
        }
        page.locator("#reauthPassword").fill("wrong-5");
        page.locator("#reauthSubmit").click();
        page.waitForURL("**/admin/login");

        assertThat(jdbc.queryForObject("SELECT status FROM member WHERE id = ?", String.class, manager.getId())).isEqualTo("LOCKED");
    }
}
