package com.cms.support;

import com.cms.admin.member.domain.Member;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 실제 브라우저(Chromium)로 관리 화면을 시험하는 클래스가 상속하는 베이스(PLAN-popup.md 코드 리뷰 R2 후속). MockMvc는 브라우저의 기본 폼 검증
 * (숨겨진 invalid 입력이 제출을 막는 것 등)과 화면 JS를 재현하지 못해, 이런 동작은 실제 브라우저로만 고정할 수 있다.
 *
 * <p>브라우저(헤드리스)는 <b>테스트 JVM당 한 번</b> 띄워 모든 시험 클래스가 공유하고 JVM 종료 훅에서 닫는다(PLAN-e2e-browser-speedup.md 설계 1 —
 * 클래스마다 띄우면 회당 약 2.6초). 격리는 시험마다 새로 만드는 {@link BrowserContext}(쿠키·저장소·캐시 분리)가 맡는다. Playwright 객체는 스레드 안전하지
 * 않으므로 처음 띄운 스레드 밖에서 쓰면 즉시 실패한다(JUnit 병렬 실행은 꺼져 있다). 첫 실행 때 Playwright가 드라이버와 브라우저를 자동으로 내려받고, CI는
 * {@code ./gradlew installPlaywrightBrowsers}로 미리 설치한다. 서버는 {@code @SpringBootTest(webEnvironment = RANDOM_PORT)}로 띄운 실제
 * 서버이고 DB는 {@link MariaDbContainerSupport}의 일회용 컨테이너다 — 로그인은 실제 로그인 엔드포인트로의 폼 제출이다({@link #loginAsAdmin}).
 *
 * <p><b>Bootstrap {@code fade} 전환은 기본으로 꺼져 있다</b>(설계 3 — 모달을 열고 닫을 때마다 약 300ms). 앱 JS와 모달 상태 기계는 그대로 돌고, Bootstrap은
 * 전환 시간이 0이어도 완료를 비동기로 알린다. 전환 자체가 시험 조건인 시험은 화면 이동 뒤 {@link #restoreModalTransitions}를 부른다.
 * 브라우저 시험은 느리므로 브라우저가 필요한 동작에만 쓰고, 서버 로직은 기존 MockMvc·통합 시험에 둔다.
 */
@Tag("browser")   // CI는 이 태그로 브라우저 시험을 별도 job으로 나눈다(build.gradle -PbrowserTests, .github/workflows/ci.yml)
public abstract class BrowserTestSupport extends MariaDbContainerSupport {

    /** 브라우저 시험 회원의 로그인 비밀번호. */
    private static final String TEST_PASSWORD = "browser-test-password";

    /** 전환을 끄는 스타일 요소의 id — {@link #restoreModalTransitions}가 지운다. */
    private static final String NO_TRANSITIONS_STYLE_ID = "__test-no-transitions";

    private static final String NO_TRANSITIONS_SCRIPT = "document.addEventListener('DOMContentLoaded', () => {"
            + " const s = document.createElement('style'); s.id = '" + NO_TRANSITIONS_STYLE_ID + "';"
            + " s.textContent = '.fade, .modal.fade .modal-dialog { transition: none !important; }';"
            + " document.head.appendChild(s); });";

    private static final Pattern CSRF_INPUT = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"|value=\"([^\"]+)\"\\s+name=\"_csrf\"");

    private static final AtomicInteger BARRIER_SEQ = new AtomicInteger();

    private static Playwright playwright;
    private static Browser browser;
    private static Thread owner;

    /** 공유 브라우저. 처음 부를 때 띄우고 JVM 종료 때 닫는다. */
    private static synchronized Browser browser() {
        if (browser == null) {
            owner = Thread.currentThread();
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                browser.close();
                playwright.close();
            }, "playwright-shutdown"));
        } else if (owner != Thread.currentThread()) {
            throw new IllegalStateException("공유 Playwright 브라우저는 처음 띄운 스레드(" + owner.getName() + ")에서만 쓸 수 있다 — 병렬 실행을 켰다면 브라우저 공유 방식을 먼저 바꿔야 한다");
        }
        return browser;
    }

    /** 쿠키·저장소가 분리된 새 브라우저 컨텍스트. 기본 대기 시간은 CI가 느려도 견디도록 넉넉히 둔다. */
    protected static BrowserContext newContext() {
        return newContext(new Browser.NewContextOptions());
    }

    /** 옵션을 지정한 새 브라우저 컨텍스트(예: 스크립트를 끈 공개 화면 시험). 모달 전환은 꺼진 채로 시작한다. */
    protected static BrowserContext newContext(Browser.NewContextOptions options) {
        BrowserContext context = browser().newContext(options);
        context.setDefaultTimeout(15_000);
        context.addInitScript(NO_TRANSITIONS_SCRIPT);
        return context;
    }

    /**
     * 현재 문서에서 Bootstrap 전환을 되살린다(이후의 show/hide부터 원래 전환 시간이 적용된다). 초기화 스크립트가 화면을 옮길 때마다 전환을 다시 끄므로
     * <b>{@code navigate} 뒤에</b> 부른다.
     */
    protected static void restoreModalTransitions(Page page) {
        page.evaluate("id => { const s = document.getElementById(id); if (!s) { throw new Error('전환 차단 스타일이 없다: ' + id); } s.remove(); }",
                NO_TRANSITIONS_STYLE_ID);
    }

    /**
     * {@code TestMembers.save}로 만든 회원이 실제 로그인 폼으로 로그인할 수 있게 비밀번호를 BCrypt로 바꾼다. 앱은 {@code BCryptPasswordEncoder}만 쓰므로
     * {@code TestMembers}의 {@code {noop}} 비밀번호로는 폼 로그인이 안 된다(MockMvc 시험은 인증 주체를 직접 만들어 이 경로를 타지 않는다).
     */
    protected static void enableFormLogin(JdbcTemplate jdbc, Member member) {
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", new BCryptPasswordEncoder().encode(TEST_PASSWORD), member.getId());
    }

    /**
     * 실제 로그인 엔드포인트({@code POST /admin/login})로 폼을 제출해 로그인한다. {@code member}는 {@link #enableFormLogin}을 거친 회원이어야 한다.
     * 화면을 그리지 않고 페이지 컨텍스트와 쿠키를 공유하는 요청 컨텍스트로 보낸다(설계 2 — 로그인 화면·대시보드 렌더가 회당 약 0.8초). 로그인 필터·BCrypt
     * 검증·성공 핸들러·세션 고정 보호는 그대로 거친다. 호출 뒤에는 목적 화면으로 {@code navigate}한다.
     */
    protected static void loginAsAdmin(Page page, int port, Member member) {
        String loginUrl = "http://localhost:" + port + "/admin/login";
        APIRequestContext request = page.context().request();
        APIResponse form = request.get(loginUrl);
        Matcher csrf = CSRF_INPUT.matcher(form.text());
        if (form.status() != 200 || !csrf.find()) {
            throw new IllegalStateException("로그인 화면에서 CSRF 토큰을 찾지 못했다: status=" + form.status());
        }
        APIResponse response = request.post(loginUrl, RequestOptions.create()
                .setForm(FormData.create()
                        .set("username", member.getUserId())
                        .set("password", TEST_PASSWORD)
                        .set("_csrf", csrf.group(1) != null ? csrf.group(1) : csrf.group(2)))
                .setMaxRedirects(0));
        String location = response.headers().get("location");
        // 성공 핸들러는 항상 /admin으로 보낸다. 실패·계정 상태 재검증 실패는 /admin/login-error로 간다.
        if (response.status() != 302 || location == null || !"/admin".equals(URI.create(location).getPath())) {
            throw new IllegalStateException("폼 로그인 실패: status=" + response.status() + ", location=" + location);
        }
    }

    /**
     * 모달의 {@code hidePrevented.bs.modal}(정적 백드롭 클릭·ESC를 Bootstrap이 거부할 때 동기적으로 발생) 횟수를 {@code window.__hidePrevented}에
     * 세기 시작한다. "닫히지 않음"의 양성 증거로 쓴다({@link #assertHidePrevented}) — 거부되지 않고 {@code hide()}가 불렸다면 {@code .show}가 그 안에서
     * 동기적으로 빠진다.
     */
    protected static void countHidePrevented(Page page, String modalSelector) {
        page.evaluate("sel => { window.__hidePrevented = 0; $(sel).on('hidePrevented.bs.modal', () => { window.__hidePrevented++; }); }",
                modalSelector);
    }

    /**
     * {@link #countHidePrevented}로 센 거부 횟수가 {@code expected}인지 바로 단언한다. 거부 이벤트는 키·마우스 이벤트 처리 안에서 동기적으로 발생하고
     * Playwright의 {@code press}·{@code click}은 그 처리가 끝난 뒤 돌아오므로 기다리지 않는다 — 회귀하면 시간 초과가 아니라 횟수 불일치로 즉시 실패한다.
     */
    protected static void assertHidePrevented(Page page, int expected) {
        assertThat(((Number) page.evaluate("() => window.__hidePrevented")).intValue())
                .as("ESC·배경 클릭이 모두 Bootstrap에 닿아 거부됐다(hidePrevented.bs.modal)").isEqualTo(expected);
    }

    /**
     * {@code urlPattern}(JS 정규식 원문)에 맞는 첫 GET {@code fetch} 응답을 붙잡는다 — 서버 응답은 받되 앱에 넘기지 않고, {@link #releaseHeldResponse}가
     * 넘길 때까지 보관한다. 보관한 응답의 {@code json()}은 미리 파싱한 값을 즉시 돌려주도록 덮어써 앱의 후속 처리가 마이크로태스크만으로 끝나게 한다
     * (네이티브 본문 소비는 완료 시점이 보장되지 않는다 — PLAN-e2e-browser-speedup.md R2-2). 같은 문서에서 한 번만 부른다.
     */
    protected static void holdFirstResponse(Page page, String urlPattern) {
        page.evaluate("p => { const re = new RegExp(p); const orig = window.fetch; let taken = false; window.__releaseHeld = null;"
                + " window.fetch = function (url, options) {"
                + "   if (taken || !re.test(String(url)) || (options && options.method)) { return orig(url, options); }"
                + "   taken = true;"
                + "   return orig(url, options).then(r => r.text().then(text => new Promise(resolve => {"
                + "     const held = new Response(text, { status: r.status, statusText: r.statusText, headers: r.headers });"
                + "     const body = text ? JSON.parse(text) : null;"
                + "     held.json = () => Promise.resolve(body);"
                + "     window.__releaseHeld = () => { resolve(held); return new Promise(done => setTimeout(done, 0)); };"
                + "   }))); }; }", urlPattern);
    }

    /** {@link #holdFirstResponse}로 붙잡은 응답을 앱에 넘기고, 앱이 그 응답을 다 처리할 때까지(다음 태스크) 기다린다. */
    protected static void releaseHeldResponse(Page page) {
        page.waitForFunction("() => typeof window.__releaseHeld === 'function'");
        page.evaluate("() => window.__releaseHeld()");
    }

    /**
     * 요청 장벽 —페이지에서 표지 요청을 하나 보내고 그 요청이 시험 쪽 요청 이벤트로 도착할 때까지 기다린다. 앱이 직전 동기 처리에서 보낸 요청은 표지보다
     * 먼저 발행되므로, 장벽 뒤에 센 요청 수로 "요청이 나가지 않았다"를 단언한다(설계 4 B·I, 검출력은 변이 실험으로 확인).
     */
    protected static void awaitRequestBarrier(Page page) {
        String marker = "__barrier=" + BARRIER_SEQ.incrementAndGet();
        page.waitForRequest(request -> request.url().contains(marker),
                () -> page.evaluate("m => { fetch('/favicon.ico?' + m).catch(() => {}); }", marker));
    }
}
