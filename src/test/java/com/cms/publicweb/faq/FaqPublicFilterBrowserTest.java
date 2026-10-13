package com.cms.publicweb.faq;

import com.cms.support.BrowserTestSupport;
import com.cms.support.CmsTestApplication;
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

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공개 {@code /faq}의 즉시 필터 JS를 <b>실제 브라우저</b>로 고정한다(PLAN-faq.md 쟁점 9, R2-2): 검색 입력은 스크립트가 켜진 뒤에만 보이고,
 * 질문·답변을 NFC·소문자·공백 압축으로 정규화해 부분 일치하며, 블록 경계(문단·br)는 공백으로 / 인라인 경계는 구분자 없이 잇는다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FaqPublicFilterBrowserTest extends BrowserTestSupport {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    private BrowserContext context;
    private Page page;

    @BeforeEach
    void setUp() {
        cleanFaq();
        context = newContext();
        page = context.newPage();
    }

    @AfterEach
    void tearDown() {
        context.close();
        cleanFaq();
    }

    private void cleanFaq() {
        jdbc.update("DELETE FROM faq");
        jdbc.update("DELETE FROM faq_category");
    }

    private long category(String name, int ord) {
        jdbc.update("INSERT INTO faq_category (name, ord, use_yn, create_date, update_date) VALUES (?, ?, 1, ?, ?)",
                name, ord, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject("SELECT MAX(id) FROM faq_category", Long.class);
    }

    private void faq(long categoryId, String question, String answerHtml, int ord) {
        jdbc.update("INSERT INTO faq (category_id, question, answer_html, ord, use_yn, create_date, update_date) VALUES (?, ?, ?, ?, 1, ?, ?)",
                categoryId, question, answerHtml, ord, LocalDateTime.now(), LocalDateTime.now());
    }

    private void open() {
        page.navigate("http://localhost:" + port + "/main/faq");
        page.waitForFunction("() => !document.getElementById('faq-search-box').hidden");
    }

    private List<String> visibleQuestions() {
        @SuppressWarnings("unchecked")
        List<Object> texts = (List<Object>) page.evaluate("() => Array.from(document.querySelectorAll('[data-faq-item]:not([hidden]) .faq-question'))"
                + ".filter(e => !e.closest('[data-faq-category]').hidden).map(e => e.textContent)");
        return texts.stream().map(String::valueOf).toList();
    }

    private void search(String text) {
        page.fill("#faq-search", text);
    }

    @Test
    @DisplayName("스크립트가 켜지면 검색 입력이 보이고, 질문에 대한 부분 일치(대소문자 무시)로 항목을 바로 거르며 일치하는 항목이 없는 카테고리는 숨긴다")
    void filtersByQuestion_hidesEmptyCategories_caseInsensitive() {
        long c1 = category("배송", 0);
        long c2 = category("계정", 1);
        faq(c1, "배송은 얼마나 걸리나요?", "<p>2~3일</p>", 0);
        faq(c1, "해외 배송 되나요?", "<p>가능합니다</p>", 1);
        faq(c2, "Password 변경", "<p>설정에서</p>", 0);
        open();

        assertThat(visibleQuestions()).hasSize(3);

        search("해외");
        assertThat(visibleQuestions()).containsExactly("해외 배송 되나요?");
        assertThat((Boolean) page.evaluate("() => document.querySelectorAll('[data-faq-category]')[1].hidden")).as("일치 없는 카테고리 숨김").isTrue();

        search("pass");
        assertThat(visibleQuestions()).as("대소문자 무시").containsExactly("Password 변경");

        search("");
        assertThat(visibleQuestions()).as("빈 입력은 전부 표시").hasSize(3);
        assertThat((Boolean) page.evaluate("() => document.getElementById('faq-no-match').hidden")).isTrue();
    }

    @Test
    @DisplayName("답변 텍스트도 검색된다 — 문단·줄바꿈 경계는 공백으로, 인라인 서식 경계는 구분자 없이 이어진다")
    void searchesAnswers_withBlockAndInlineBoundaries() {
        long c = category("c", 0);
        faq(c, "문단 질문", "<p>배송</p><p>안내</p>", 0);
        faq(c, "줄바꿈 질문", "<p>반품<br>절차</p>", 1);
        faq(c, "인라인 질문", "<p><strong>환불</strong>규정</p>", 2);
        open();

        search("배송 안내");
        assertThat(visibleQuestions()).as("문단 경계는 공백").containsExactly("문단 질문");
        search("배송안내");
        assertThat(visibleQuestions()).as("문단 경계에는 구분자가 있어 붙여 쓴 검색어는 일치하지 않는다").isEmpty();

        search("반품 절차");
        assertThat(visibleQuestions()).as("br 경계는 공백").containsExactly("줄바꿈 질문");

        search("환불규정");
        assertThat(visibleQuestions()).as("인라인 경계는 구분자 없음").containsExactly("인라인 질문");
        search("환불 규정");
        assertThat(visibleQuestions()).as("인라인 경계에는 공백이 없다").isEmpty();
    }

    @Test
    @DisplayName("분해형(NFD) 한글로 저장된 답변도 일반(NFC) 입력으로 검색되고, 입력의 앞뒤·연속 공백은 무시된다")
    void normalizesUnicodeAndWhitespace() {
        long c = category("c", 0);
        String decomposed = Normalizer.normalize("한글 답변", Normalizer.Form.NFD);
        assertThat(decomposed).isNotEqualTo("한글 답변");
        faq(c, "유니코드 질문", "<p>" + decomposed + "</p>", 0);
        faq(c, "다른 질문", "<p>무관</p>", 1);
        open();

        search("한글 답변");
        assertThat(visibleQuestions()).containsExactly("유니코드 질문");

        search("  한글    답변  ");
        assertThat(visibleQuestions()).as("공백 정규화").containsExactly("유니코드 질문");
    }

    @Test
    @DisplayName("일치하는 항목이 하나도 없으면 안내 문구가 보인다 — 그리고 일치 항목을 자동으로 펼치지 않는다")
    void noMatchMessage_andNoAutoOpen() {
        long c = category("c", 0);
        faq(c, "질문", "<p>답변</p>", 0);
        open();

        search("zzzz없는검색어");
        assertThat(visibleQuestions()).isEmpty();
        assertThat((Boolean) page.evaluate("() => document.getElementById('faq-no-match').hidden")).as("안내 문구 표시").isFalse();

        search("질문");
        assertThat(visibleQuestions()).containsExactly("질문");
        assertThat((Boolean) page.evaluate("() => document.querySelector('[data-faq-item]').open")).as("자동으로 펼치지 않는다").isFalse();

        page.click("[data-faq-item] summary");
        assertThat((Boolean) page.evaluate("() => document.querySelector('[data-faq-item]').open")).as("클릭으로 펼쳐진다").isTrue();
    }

    @Test
    @DisplayName("스크립트를 끄면 검색 입력은 보이지 않고 모든 질문이 그대로 보인다(점진적 향상)")
    void withoutJavaScript_everythingStaysVisible() {
        long c = category("c", 0);
        faq(c, "질문1", "<p>답변</p>", 0);
        faq(c, "질문2", "<p>답변</p>", 1);
        BrowserContext noScript = browserContextWithoutJavaScript();
        try {
            Page plain = noScript.newPage();
            plain.navigate("http://localhost:" + port + "/main/faq");

            assertThat(plain.locator("[data-faq-item]").count()).isEqualTo(2);
            assertThat(plain.locator("#faq-search-box").isVisible()).as("입력 상자는 숨겨진 채").isFalse();
            assertThat(plain.locator("[data-faq-item] summary").first().isVisible()).isTrue();
        } finally {
            noScript.close();
        }
    }

    private BrowserContext browserContextWithoutJavaScript() {
        return newContext(new com.microsoft.playwright.Browser.NewContextOptions().setJavaScriptEnabled(false));
    }
}
