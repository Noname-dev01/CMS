package com.cms.admin.media;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.BrowserTestSupport;
import com.cms.support.CmsTestApplication;
import com.cms.support.TestMembers;
import com.microsoft.playwright.Browser;
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
 * 미디어 선택 모달({@code media-picker.js})이 게시글 작성 모달 <b>위에 중첩</b>해서 열렸다 닫힐 때 아래 모달의 스크롤이 살아 있는지 실제
 * 브라우저로 고정한다. Bootstrap 4는 모달이 닫힐 때 다른 모달이 열려 있어도 {@code body.modal-open}을 지워서, 이 저장소 CSS
 * ({@code .modal-open .modal}에만 세로 스크롤 허용)에서는 화면보다 긴 게시글 폼이 스크롤되지 않았다(코드 리뷰 1라운드). MockMvc로는 재현되지
 * 않는다. 모달 내용이 화면보다 길어야 하므로 뷰포트 높이를 줄인다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaPickerNestedModalBrowserTest extends BrowserTestSupport {

    private static final String POST_MODAL = "#postDetailModal";
    private static final String PICKER_MODAL = ".modal.show:not(#postDetailModal)";

    @LocalServerPort int port;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;

    private Member admin;
    private BrowserContext context;
    private Page page;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "ui-picker-admin", Role.ROLE_ADMIN);
        enableFormLogin(jdbc, admin);
        context = newContext(new Browser.NewContextOptions().setViewportSize(1000, 380));
        page = context.newPage();
        loginAsAdmin(page, port, admin);
    }

    @AfterEach
    void tearDown() {
        context.close();
        TestMembers.delete(jdbc, List.of(admin.getId()));
    }

    /** 게시글 작성 모달을 열고 에디터의 "라이브러리" 버튼으로 선택 모달을 중첩해 연다. */
    private void openPickerOverPostModal() {
        page.navigate("http://localhost:" + port + "/admin/board/posts");
        page.click("#btnNewPost");
        page.waitForSelector(POST_MODAL + ".show .ql-library-image");
        // 모달 전환 애니메이션(fade)이 끝난 뒤에 다음 모달을 연다
        page.waitForFunction("() => document.body.classList.contains('modal-open')"
                + " && document.querySelector('.modal-backdrop.show') !== null");
        page.click(POST_MODAL + " .ql-library-image");
        // fade 전환이 끝나 Bootstrap이 선택 모달로 포커스를 옮긴 뒤에야 사용자가 닫을 수 있다 — 그 전의 ESC는 아래 게시글 모달이 받는다
        page.waitForFunction("() => { const picker = document.querySelector('.modal.show:not(#postDetailModal)');"
                + " return picker !== null && picker.contains(document.activeElement); }");
    }

    /**
     * 선택 모달의 닫힘이 끝날 때까지 기다린다. {@code show} 클래스는 닫힘 시작 때 먼저 빠지고 {@code hidden.bs.modal}은 fade 전환 뒤(백드롭 정리 직후)에
     * 발생하므로, {@code show}가 빠진 시점에 단언하면 복원 핸들러가 실행되기 전 상태를 보게 된다.
     */
    private void waitForPickerClosed() {
        page.waitForFunction("() => { const picker = document.querySelector('.modal:not(#postDetailModal)');"
                + " return picker !== null && getComputedStyle(picker).display === 'none'"
                + " && document.querySelectorAll('.modal-backdrop').length === 1; }");
    }

    /** 선택 모달이 닫힌 뒤에도 게시글 모달이 스크롤 가능한 상태인지 단언한다. */
    private void assertPostModalStillScrolls() {
        assertThat((Boolean) page.evaluate("() => document.body.classList.contains('modal-open')"))
                .as("아래 모달이 열려 있으면 body.modal-open이 유지된다").isTrue();
        assertThat((String) page.evaluate("() => getComputedStyle(document.getElementById('postDetailModal')).overflowY"))
                .as("게시글 모달의 세로 스크롤 허용").isEqualTo("auto");
        assertThat((Boolean) page.evaluate("() => { const m = document.getElementById('postDetailModal'); return m.scrollHeight > m.clientHeight; }"))
                .as("시험 전제: 모달 내용이 화면보다 길다").isTrue();

        page.mouse().move(500, 200);
        page.mouse().wheel(0, 300);
        page.waitForFunction("() => document.getElementById('postDetailModal').scrollTop > 0");

        assertThat((Boolean) page.evaluate("() => { const m = document.getElementById('postDetailModal');"
                + " return m.contains(document.activeElement) || document.activeElement === m; }"))
                .as("포커스가 게시글 모달로 돌아온다").isTrue();
    }

    @Test
    @DisplayName("선택 모달을 닫기 버튼으로 닫아도 게시글 모달은 body.modal-open·스크롤·포커스를 유지한다")
    void closingPickerWithCloseButtonKeepsPostModalScrollable() {
        openPickerOverPostModal();

        page.click(PICKER_MODAL + " [data-dismiss=modal]");
        waitForPickerClosed();

        assertPostModalStillScrolls();
    }

    @Test
    @DisplayName("선택 모달을 ESC로 닫아도 게시글 모달은 body.modal-open·스크롤·포커스를 유지한다")
    void closingPickerWithEscapeKeepsPostModalScrollable() {
        openPickerOverPostModal();

        page.keyboard().press("Escape");
        waitForPickerClosed();

        assertPostModalStillScrolls();
    }
}
