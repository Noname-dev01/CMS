package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.BrowserTestSupport;
import com.cms.support.CmsTestApplication;
import com.cms.support.TestMembers;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
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
 * 복구할 수 없는 콘텐츠 삭제의 재인증 모달(PLAN-content-delete-reauthentication.md F4·F5)을 실제 브라우저로 고정한다 — 문의 관리 화면은
 * 403에 고정 문구를 쓰던 화면이고 삭제가 상세 모달 안에서 일어나, 취소 문구와 겹친 모달(재인증 모달이 상세 모달 위에 뜬다)을 함께 본다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContentDeleteReauthBrowserTest extends BrowserTestSupport {

    private static final Pattern CSRF_INPUT = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"|value=\"([^\"]+)\"\\s+name=\"_csrf\"");

    @LocalServerPort int port;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;

    private Member admin;
    private long inquiryId;
    private String subject;
    private BrowserContext context;
    private Page page;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "ui-cdr-admin", Role.ROLE_ADMIN);
        enableFormLogin(jdbc, admin);
        subject = "재인증 삭제 시험 " + System.nanoTime();
        jdbc.update("INSERT INTO inquiry (name, email, title, body, status, consent_at, create_date, update_date)"
                + " VALUES ('방문자', 'visitor@reauth.example', ?, '본문', 'RECEIVED', NOW(6), NOW(6), NOW(6))", subject);
        inquiryId = jdbc.queryForObject("SELECT id FROM inquiry WHERE title = ?", Long.class, subject);
        context = newContext();
        page = context.newPage();
        page.onDialog(dialog -> dialog.accept());     // 삭제 확인창
        page.onRequest(r -> requests.add(r.method() + " " + r.url().replace("http://localhost:" + port, "")));
    }

    @AfterEach
    void tearDown() {
        context.close();
        jdbc.update("DELETE FROM inquiry WHERE id = ?", inquiryId);
        jdbc.update("DELETE FROM persistent_logins WHERE username = ?", admin.getUserId());
        TestMembers.delete(jdbc, List.of(admin.getId()));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** "로그인 상태 유지"를 켠 폼 로그인 → 세션 쿠키를 지우고 remember-me 쿠키만 남긴다(다음 요청이 쿠키로 복원된다). */
    private void rememberedSession() {
        APIRequestContext request = context.request();
        Matcher csrf = CSRF_INPUT.matcher(request.get(url("/admin/login")).text());
        assertThat(csrf.find()).isTrue();
        APIResponse login = request.post(url("/admin/login"), RequestOptions.create()
                .setForm(FormData.create().set("username", admin.getUserId()).set("password", TEST_PASSWORD).set("remember-me", "on")
                        .set("_csrf", csrf.group(1) != null ? csrf.group(1) : csrf.group(2)))
                .setMaxRedirects(0));
        assertThat(login.status()).isEqualTo(302);
        List<Cookie> rememberMe = context.cookies().stream().filter(c -> "remember-me".equals(c.name)).toList();
        assertThat(rememberMe).hasSize(1);
        context.clearCookies();
        context.addCookies(rememberMe);
    }

    private void openDetailAndDelete() {
        page.navigate(url("/admin/inquiry/manage"));
        page.getByText(subject).click();
        page.locator("#detailModal.show #btnDelete:not(.d-none)").waitFor();
        page.waitForFunction("() => !document.getElementById('btnDelete').disabled");
        page.locator("#btnDelete").click();
        page.locator("#reauthModal.show").waitFor();
    }

    private long deletes() {
        return requests.stream().filter(r -> r.equals("DELETE /admin/api/inquiries/" + inquiryId)).count();
    }

    private long rows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inquiry WHERE id = ?", Long.class, inquiryId);
    }

    @Test
    @DisplayName("문의 상세 모달 위에 재인증 모달이 뜨고, 정답이면 같은 삭제를 한 번 다시 보내 문의가 지워진다")
    void correctPassword_retriesDeleteOnce() {
        rememberedSession();
        openDetailAndDelete();

        page.locator("#reauthPassword").fill(TEST_PASSWORD);
        page.locator("#reauthSubmit").click();
        page.waitForFunction("() => !document.querySelector('#detailModal.show') && !document.querySelector('#reauthModal.show')");

        assertThat(rows()).isZero();
        assertThat(deletes()).as("원 요청 1(403) + 재시도 1").isEqualTo(2);
    }

    @Test
    @DisplayName("취소하면 상세 모달에 서버의 재인증 문구가 보이고(고정 '권한 없음' 아님) 문의는 남는다")
    void cancel_showsReauthMessage() {
        rememberedSession();
        openDetailAndDelete();

        page.locator("#reauthCancel").click();
        page.waitForFunction("() => !document.getElementById('detailAlert').classList.contains('d-none')");

        assertThat(page.locator("#detailAlert").textContent()).contains("비밀번호 확인이 필요합니다").doesNotContain("권한이 없습니다");
        assertThat(page.locator("#detailModal.show").count()).isEqualTo(1);
        assertThat(rows()).isEqualTo(1);
        assertThat(deletes()).isEqualTo(1);
    }

    @Test
    @DisplayName("전환을 켠 상태에서 재인증을 취소하면 포커스가 상세 모달 안으로 돌아오고, Tab을 눌러도 상세 모달 밖으로 나가지 않는다")
    void cancel_keepsFocusInsideUnderlyingModal() {
        rememberedSession();
        page.navigate(url("/admin/inquiry/manage"));
        restoreModalTransitions(page);
        page.getByText(subject).click();
        page.locator("#detailModal.show #btnDelete:not(.d-none)").waitFor();
        page.waitForFunction("() => !document.getElementById('btnDelete').disabled");
        page.locator("#btnDelete").click();
        page.waitForFunction("() => document.querySelector('#reauthModal.show') && !document.getElementById('reauthCancel').disabled");

        page.locator("#reauthCancel").click();
        // 재인증 모달이 완전히 닫힌 뒤(hidden — 배경막이 하나만 남음)
        page.waitForFunction("() => !document.querySelector('#reauthModal.show') && document.querySelectorAll('.modal-backdrop').length === 1"
                + " && !document.getElementById('detailAlert').classList.contains('d-none')");
        page.waitForFunction("() => document.getElementById('detailModal').contains(document.activeElement)");

        // 마지막 버튼 다음 Tab은 문서 밖(브라우저 UI — activeElement = body)으로 나갔다가 다음 Tab에서 모달로 돌아온다. 재인증 없이 상세 모달만
        // 연 기준선과 같은 동작이다(진단으로 확인). 계약: 페이지의 다른 요소(사이드바·목록 등)로는 가지 않고, 문서 밖으로 나간 뒤에는 모달로 돌아온다.
        boolean leftDocument = false;
        boolean returnedAfterLeaving = false;
        for (int i = 0; i < 10; i++) {
            page.keyboard().press("Tab");
            String where = (String) page.evaluate("() => document.getElementById('detailModal').contains(document.activeElement) ? 'modal'"
                    + " : (document.activeElement === document.body || !document.activeElement) ? 'outside-document' : 'page:' + document.activeElement.outerHTML.slice(0, 80)");
            assertThat(where).as("Tab " + (i + 1) + "회째 포커스").isIn("modal", "outside-document");
            if ("outside-document".equals(where)) {
                leftDocument = true;
            } else if (leftDocument) {
                returnedAfterLeaving = true;
            }
        }
        assertThat(returnedAfterLeaving).as("문서 밖으로 나간 뒤 Tab으로 모달에 돌아옴").isTrue();
        assertThat(page.evaluate("() => document.body.classList.contains('modal-open')")).isEqualTo(true);
    }
}
