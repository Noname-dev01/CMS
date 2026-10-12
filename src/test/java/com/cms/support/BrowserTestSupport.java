package com.cms.support;

import com.cms.admin.member.domain.Member;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 실제 브라우저(Chromium)로 관리 화면을 시험하는 클래스가 상속하는 베이스(PLAN-popup.md 코드 리뷰 R2 후속). MockMvc는 브라우저의 기본 폼 검증
 * (숨겨진 invalid 입력이 제출을 막는 것 등)과 화면 JS를 재현하지 못해, 이런 동작은 실제 브라우저로만 고정할 수 있다.
 *
 * <p>브라우저는 테스트 클래스당 하나만 띄운다(헤드리스). 첫 실행 때 Playwright가 드라이버와 브라우저를 자동으로 내려받고, CI는
 * {@code ./gradlew installPlaywrightBrowsers}로 미리 설치한다. 서버는 {@code @SpringBootTest(webEnvironment = RANDOM_PORT)}로 띄운 실제
 * 서버이고 DB는 {@link MariaDbContainerSupport}의 일회용 컨테이너다 — 로그인은 실제 폼 로그인이다({@link #loginAsAdmin}).
 * 브라우저 시험은 느리므로(컨텍스트·브라우저 기동) 브라우저가 필요한 동작에만 쓰고, 서버 로직은 기존 MockMvc·통합 시험에 둔다.
 */
public abstract class BrowserTestSupport extends MariaDbContainerSupport {

    /** 브라우저 시험 회원의 로그인 비밀번호. */
    private static final String TEST_PASSWORD = "browser-test-password";

    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    /** 쿠키·저장소가 분리된 새 브라우저 컨텍스트. 기본 대기 시간은 CI가 느려도 견디도록 넉넉히 둔다. */
    protected static BrowserContext newContext() {
        return newContext(new Browser.NewContextOptions());
    }

    /** 옵션을 지정한 새 브라우저 컨텍스트(예: 스크립트를 끈 공개 화면 시험). */
    protected static BrowserContext newContext(Browser.NewContextOptions options) {
        BrowserContext context = browser.newContext(options);
        context.setDefaultTimeout(15_000);
        return context;
    }

    /**
     * {@code TestMembers.save}로 만든 회원이 실제 로그인 폼으로 로그인할 수 있게 비밀번호를 BCrypt로 바꾼다. 앱은 {@code BCryptPasswordEncoder}만 쓰므로
     * {@code TestMembers}의 {@code {noop}} 비밀번호로는 폼 로그인이 안 된다(MockMvc 시험은 인증 주체를 직접 만들어 이 경로를 타지 않는다).
     */
    protected static void enableFormLogin(JdbcTemplate jdbc, Member member) {
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", new BCryptPasswordEncoder().encode(TEST_PASSWORD), member.getId());
    }

    /** 실제 로그인 폼으로 로그인한다. {@code member}는 {@link #enableFormLogin}을 거친 회원이어야 한다. */
    protected static void loginAsAdmin(Page page, int port, Member member) {
        page.navigate("http://localhost:" + port + "/admin/login");
        page.fill("input[name=username]", member.getUserId());
        page.fill("input[name=password]", TEST_PASSWORD);
        page.click("button[type=submit]");
        page.waitForURL(url -> !url.contains("/admin/login"));
    }
}
