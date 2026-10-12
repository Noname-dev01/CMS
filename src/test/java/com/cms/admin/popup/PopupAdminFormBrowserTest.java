package com.cms.admin.popup;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.popup.service.PopupService;
import com.cms.common.image.ImageFileValidatorTest;
import com.cms.common.storage.FileStorage;
import com.cms.support.BrowserTestSupport;
import com.cms.support.MediaTestSupport;
import com.cms.support.CmsTestApplication;
import com.cms.support.TestMembers;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.FilePayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 팝업 관리 화면의 폼 동작을 <b>실제 브라우저</b>로 고정한다(PLAN-popup.md 코드 리뷰 R2). 핵심은 종류 전환이다 — 새 팝업 작성 중 HTML형에서
 * 범위 밖 높이를 입력한 뒤 이미지형으로 바꾸면 숨겨진 높이 입력이 활성으로 남아 <b>브라우저 기본 폼 검증이 등록을 조용히 막던</b> 문제다. 이 검증은
 * MockMvc로 재현되지 않는다. 서버 쪽 규칙(범위 400 등)은 {@code PopupApiIntegrationTest}가 맡고 여기서는 화면이 그 규칙과 어긋나지 않는지만 본다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PopupAdminFormBrowserTest extends BrowserTestSupport {

    @LocalServerPort int port;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileStorage fileStorage;

    private Member admin;
    private BrowserContext context;
    private Page page;

    @BeforeEach
    void setUp() {
        cleanPopups();
        admin = TestMembers.save(memberRepository, "ui-admin", Role.ROLE_ADMIN);
        enableFormLogin(jdbc, admin);
        context = newContext();
        page = context.newPage();
        loginAsAdmin(page, port, admin);
    }

    @AfterEach
    void tearDown() {
        context.close();
        cleanPopups();
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE action_type LIKE 'POPUP_%'");
    }

    private void cleanPopups() {
        jdbc.update("DELETE FROM popup");
        MediaTestSupport.cleanLibrary(jdbc, fileStorage);
    }

    private void openManage() {
        page.navigate("http://localhost:" + port + "/admin/popup/manage");
        // 목록 로딩이 끝나(불러오는 중 행이 사라짐) 이벤트 핸들러가 모두 연결된 뒤에 조작한다
        page.waitForFunction("() => !document.getElementById('popupList').textContent.includes('불러오는 중')");
    }

    private boolean formValid() {
        return (Boolean) page.evaluate("() => document.getElementById('popupForm').checkValidity()");
    }

    private void waitForSuccessMessage(String text) {
        page.waitForFunction("text => { const e = document.getElementById('pageSuccess');"
                + " return e && !e.classList.contains('d-none') && e.textContent.includes(text); }", text);
    }

    private static byte[] png() throws Exception {
        return ImageFileValidatorTest.png(30, 12);
    }

    /** 이미지 팝업 하나를 DB에 직접 넣는다(파일 포함 — 관리 화면의 미리보기가 읽는다). */
    private long insertImagePopup(String title) throws Exception {
        String key = fileStorage.store(png(), "p.png", PopupService.STORAGE_NAMESPACE);
        jdbc.update("INSERT INTO popup (title, revision, popup_type, storage_key, content_type, file_size, pos_x, pos_y, width, use_yn, create_date, update_date) "
                + "VALUES (?, 0, 'IMAGE', ?, 'image/png', 10, 10, 10, 300, 1, ?, ?)", title, key, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject("SELECT MAX(id) FROM popup", Long.class);
    }

    private long insertHtmlPopup(String title, Integer height) {
        jdbc.update("INSERT INTO popup (title, revision, popup_type, content_html, pos_x, pos_y, width, height, use_yn, create_date, update_date) "
                + "VALUES (?, 0, 'HTML', '<p>본문</p>', 10, 10, 300, ?, 1, ?, ?)", title, height, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject("SELECT MAX(id) FROM popup", Long.class);
    }

    /** 목록 행을 눌러 수정 모드로 들어가고, 상세가 도착해 폼이 채워질 때까지 기다린다. */
    private void startEdit(String title, long id) {
        page.locator("#popupList .popup-row").filter(new Locator.FilterOptions().setHasText(title)).click();
        page.waitForFunction("id => document.getElementById('formTitle').textContent.includes('#' + id + ' 수정')"
                + " && !document.getElementById('btnSave').disabled", String.valueOf(id));
    }

    @Test
    @DisplayName("HTML형에서 범위 밖 높이를 넣고 이미지형으로 전환해도 높이 입력은 비활성이라 폼이 유효하고 이미지 팝업이 등록된다 (코드 리뷰 R2 회귀)")
    void switchingToImageTypeDisablesHiddenHeight_soRegistrationIsNotBlocked() throws Exception {
        openManage();

        page.check("#typeHtml");
        page.fill("#popupHeight", "100");              // 하한(144) 미만 — HTML형이었다면 브라우저가 거부할 값
        page.check("#typeImage");

        assertThat(page.isVisible("#popupHeight")).as("이미지형에서는 높이 입력이 숨겨진다").isFalse();
        assertThat(page.isDisabled("#popupHeight")).as("숨겨진 높이 입력은 비활성이라 폼 검증에서 빠진다").isTrue();

        page.fill("#popupTitle", "브라우저 시험 이미지");
        page.setInputFiles("#popupImage", new FilePayload("p.png", "image/png", png()));
        assertThat(formValid()).as("숨겨진 invalid 입력이 없어 폼이 유효하다").isTrue();

        page.click("#btnSave");
        waitForSuccessMessage("등록했습니다");

        assertThat(jdbc.queryForObject("SELECT popup_type FROM popup WHERE title = '브라우저 시험 이미지'", String.class)).isEqualTo("IMAGE");
        assertThat(jdbc.queryForObject("SELECT height FROM popup WHERE title = '브라우저 시험 이미지'", Object.class)).as("이미지형은 높이 없음").isNull();
    }

    @Test
    @DisplayName("종류를 HTML ↔ 이미지로 오가면 높이 입력의 활성 상태가 종류를 따라간다")
    void heightInputFollowsTheSelectedType() {
        openManage();

        assertThat(page.isDisabled("#popupHeight")).as("기본(이미지형)").isTrue();
        page.check("#typeHtml");
        assertThat(page.isDisabled("#popupHeight")).as("HTML형").isFalse();
        assertThat(page.isVisible("#popupHeight")).isTrue();
        page.check("#typeImage");
        assertThat(page.isDisabled("#popupHeight")).as("다시 이미지형").isTrue();
        page.check("#typeHtml");
        assertThat(page.isDisabled("#popupHeight")).as("다시 HTML형").isFalse();
    }

    @Test
    @DisplayName("HTML형 높이가 범위(144~1000) 밖이면 브라우저가 제출을 막고(요청 없음·높이 입력에 포커스), 범위 안이면 등록된다")
    void htmlHeightIsValidatedByTheBrowser() {
        openManage();
        List<String> posts = new ArrayList<>();
        page.onRequest(request -> {
            if ("POST".equals(request.method()) && request.url().endsWith("/admin/api/popups")) {
                posts.add(request.url());
            }
        });

        page.check("#typeHtml");
        page.fill("#popupTitle", "높이 검증");
        page.click("#popupContentEditor .ql-editor");
        page.keyboard().type("높이 검증 본문");

        for (String bad : List.of("143", "1001")) {
            page.fill("#popupHeight", bad);
            assertThat(formValid()).as("높이 " + bad + "는 무효").isFalse();
            page.click("#btnSave");
            // 무효한 제출은 submit 이벤트 전에 브라우저가 막고 첫 무효 입력에 포커스를 준다 — 동기적으로 일어나므로 별도 대기가 필요 없다
            assertThat((String) page.evaluate("() => document.activeElement && document.activeElement.id")).as("높이 " + bad).isEqualTo("popupHeight");
            assertThat(posts).as("높이 " + bad + "는 요청 자체가 나가지 않는다").isEmpty();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM popup", Integer.class)).isZero();

        page.fill("#popupHeight", "144");               // 하한 경계는 유효
        assertThat(formValid()).isTrue();
        page.click("#btnSave");
        waitForSuccessMessage("등록했습니다");

        assertThat(jdbc.queryForObject("SELECT height FROM popup WHERE title = '높이 검증'", Integer.class)).isEqualTo(144);
        assertThat(posts).hasSize(1);
    }

    @Test
    @DisplayName("수정 모드: 이미지형은 종류·높이·업로드가 잠기고 미리보기가 보이며, HTML형은 높이·편집기가 보이고, [새 팝업 작성]으로 돌아오면 다시 열린다")
    void editModeShowsControlsForTheTypeOfThePopup() throws Exception {
        long imageId = insertImagePopup("이미지 팝업");
        long htmlId = insertHtmlPopup("HTML 팝업", 200);
        openManage();

        startEdit("이미지 팝업", imageId);
        assertThat(page.isDisabled("#typeImage")).as("종류는 등록 후 바꿀 수 없다").isTrue();
        assertThat(page.isDisabled("#typeHtml")).isTrue();
        assertThat(page.isDisabled("#popupHeight")).as("이미지형은 높이를 쓰지 않는다").isTrue();
        assertThat(page.isVisible("#imageGroup")).as("이미지 교체는 없다").isFalse();
        assertThat(page.isVisible("#previewGroup")).isTrue();
        assertThat(page.isVisible("#linkGroup")).isTrue();
        assertThat(page.isVisible("#editorGroup")).isFalse();

        startEdit("HTML 팝업", htmlId);
        assertThat(page.isDisabled("#popupHeight")).as("HTML형은 높이를 쓴다").isFalse();
        assertThat(page.isVisible("#popupHeight")).isTrue();
        assertThat(page.inputValue("#popupHeight")).isEqualTo("200");
        assertThat(page.isVisible("#editorGroup")).isTrue();
        assertThat(page.isVisible("#linkGroup")).isFalse();
        assertThat(page.isVisible("#previewGroup")).isFalse();
        assertThat(page.isDisabled("#typeHtml")).isTrue();

        page.click("#btnNew");
        page.waitForFunction("() => document.getElementById('formTitle').textContent.includes('새 팝업')");
        assertThat(page.isDisabled("#typeImage")).as("작성 모드에서는 종류를 고를 수 있다").isFalse();
        assertThat(page.isDisabled("#typeHtml")).isFalse();
        assertThat(page.isChecked("#typeImage")).as("기본은 이미지형").isTrue();
        assertThat(page.isDisabled("#popupHeight")).isTrue();
    }
}
