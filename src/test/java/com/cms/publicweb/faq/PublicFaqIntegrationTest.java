package com.cms.publicweb.faq;

import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 공개 {@code /faq}의 실제 스택 시험(PLAN-faq.md 쟁점 8): 노출 불변식(숨긴 카테고리 → 그 안의 FAQ 전부 숨김, 숨긴 FAQ 제외), 정렬, 빈 카테고리 미표시,
 * 보이는 내용이 없는 답변 제외(<b>제외한 뒤</b> 비게 된 카테고리도 미표시), 출력 시 재정리, 메서드·하위 경로 거부. 데이터는 JDBC로 직접 넣어
 * "DB를 직접 고쳐도 공개 화면이 안전하다"는 계약까지 같이 본다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PublicFaqIntegrationTest extends MariaDbContainerSupport {

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    private String ip;

    @BeforeEach
    void setUp() {
        int n = IP_SEQUENCE.getAndIncrement();
        ip = "10.95." + (n / 250) + "." + (n % 250 + 1);
        cleanFaq();
    }

    @AfterEach
    void cleanUp() {
        cleanFaq();
    }

    private void cleanFaq() {
        jdbc.update("DELETE FROM faq");
        jdbc.update("DELETE FROM faq_category");
    }

    private RequestPostProcessor fromIp() {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private long category(String name, int ord, boolean useYn) {
        jdbc.update("INSERT INTO faq_category (name, ord, use_yn, create_date, update_date) VALUES (?, ?, ?, ?, ?)",
                name, ord, useYn, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject("SELECT MAX(id) FROM faq_category", Long.class);
    }

    private long faq(long categoryId, String question, String answerHtml, int ord, boolean useYn) {
        jdbc.update("INSERT INTO faq (category_id, question, answer_html, ord, use_yn, create_date, update_date) VALUES (?, ?, ?, ?, ?, ?, ?)",
                categoryId, question, answerHtml, ord, useYn, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject("SELECT MAX(id) FROM faq", Long.class);
    }

    private String page() throws Exception {
        MvcResult result = mockMvc.perform(get("/main/faq").with(fromIp())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    @Test
    @DisplayName("노출 중인 카테고리·FAQ만 (카테고리 순서, FAQ 순서)대로 나오고, 숨긴 FAQ와 숨긴 카테고리 안의 FAQ는 나오지 않는다")
    void onlyVisibleInOrder() throws Exception {
        long second = category("두번째", 1, true);
        long first = category("첫번째", 0, true);
        long hidden = category("숨은카테고리", 2, false);
        faq(first, "첫-B", "<p>b</p>", 1, true);
        faq(first, "첫-A", "<p>a</p>", 0, true);
        faq(first, "첫-숨김FAQ", "<p>x</p>", 2, false);
        faq(second, "둘-A", "<p>a</p>", 0, true);
        faq(hidden, "숨은카테고리의FAQ", "<p>x</p>", 0, true);

        String html = page();

        assertThat(html).contains("첫번째", "두번째", "첫-A", "첫-B", "둘-A");
        assertThat(html).doesNotContain("숨은카테고리", "숨은카테고리의FAQ", "첫-숨김FAQ");
        assertThat(html.indexOf("첫번째")).isLessThan(html.indexOf("두번째"));
        assertThat(html.indexOf("첫-A")).isLessThan(html.indexOf("첫-B"));
        assertThat(html.indexOf("첫-B")).isLessThan(html.indexOf("둘-A"));
    }

    @Test
    @DisplayName("보이는 FAQ가 하나도 없는 카테고리(비어 있거나 FAQ가 전부 숨김)는 그리지 않고, 아무것도 없으면 빈 안내만 나온다")
    void emptyCategoriesAreNotRendered() throws Exception {
        long empty = category("빈카테고리", 0, true);
        long allHidden = category("전부숨김", 1, true);
        faq(allHidden, "숨김", "<p>x</p>", 0, false);

        String html = page();

        assertThat(html).doesNotContain("빈카테고리", "전부숨김");
        assertThat(html).contains("등록된 질문이 없습니다");
        assertThat(empty).isPositive();
    }

    @Test
    @DisplayName("답변에 보이는 내용이 없으면(<p></p>·<p><br></p>·이미지뿐·스크립트뿐) 그 FAQ를 건너뛰고, 건너뛴 뒤 비게 된 카테고리도 그리지 않는다")
    void blankAnswersAreSkipped_thenEmptiedCategoryDropped() throws Exception {
        long keep = category("유지", 0, true);
        long dropped = category("마지막이빈답변", 1, true);
        faq(keep, "정상질문", "<p>정상답변</p>", 0, true);
        faq(keep, "빈문단질문", "<p></p>", 1, true);
        faq(keep, "브레이크질문", "<p><br></p>", 2, true);
        faq(keep, "이미지질문", "<p><img src=\"/content-images/1\"></p>", 3, true);
        faq(dropped, "스크립트질문", "<script>alert(1)</script>", 0, true);

        String html = page();

        assertThat(html).contains("정상질문", "정상답변");
        assertThat(html).doesNotContain("빈문단질문", "브레이크질문", "이미지질문", "스크립트질문", "마지막이빈답변");
    }

    @Test
    @DisplayName("DB를 직접 고쳐 들어온 위험한 답변도 출력 시 다시 정리된다(script·이벤트 핸들러·img·javascript: 제거, 안전한 서식은 유지)")
    void answersAreSanitizedAtOutput() throws Exception {
        long c = category("c", 0, true);
        faq(c, "질문", "<p>안전 <strong>굵게</strong></p><script>alert(1)</script><img src=\"x\" onerror=\"alert(2)\">"
                + "<a href=\"javascript:alert(3)\">나쁜링크</a><a href=\"https://example.com\">좋은링크</a>", 0, true);

        String html = page();

        assertThat(html).contains("<strong>굵게</strong>").contains("href=\"https://example.com\"").contains("좋은링크");
        assertThat(html).doesNotContain("alert(1)", "onerror", "javascript:alert");
        assertThat(html).as("답변 안의 img는 렌더하지 않는다").doesNotContain("src=\"x\"");
    }

    @Test
    @DisplayName("카테고리 이름·질문은 이스케이프되어 출력된다 — 마크업이 글자로 보인다")
    void namesAndQuestionsAreEscaped() throws Exception {
        long c = category("<b>카테고리</b>", 0, true);
        faq(c, "<script>alert(9)</script>질문", "<p>답</p>", 0, true);

        String html = page();

        assertThat(html).contains("&lt;b&gt;카테고리&lt;/b&gt;").contains("&lt;script&gt;alert(9)&lt;/script&gt;질문");
        assertThat(html).doesNotContain("<script>alert(9)");
    }

    @Test
    @DisplayName("HEAD는 본문 없이 같은 상태이고, 쓰기 메서드·하위 경로는 막힌다(익명 쓰기는 CSRF 없으면 403, 하위 경로는 로그인 302)")
    void methodsAndSubPaths() throws Exception {
        category("c", 0, true);

        assertThat(mockMvc.perform(head("/main/faq").with(fromIp())).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(post("/main/faq").with(fromIp())).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(post("/main/faq").with(fromIp()).with(csrf())).andReturn().getResponse().getStatus()).isEqualTo(302);
        assertThat(mockMvc.perform(put("/main/faq").with(fromIp()).with(csrf())).andReturn().getResponse().getStatus()).isEqualTo(302);
        assertThat(mockMvc.perform(delete("/main/faq").with(fromIp()).with(csrf())).andReturn().getResponse().getStatus()).isEqualTo(302);
        MvcResult sub = mockMvc.perform(get("/main/faq/x").with(fromIp())).andReturn();
        assertThat(sub.getResponse().getStatus()).as("denyAll이라 MVC에 닿지 않는다").isEqualTo(302);
        assertThat(sub.getResponse().getRedirectedUrl()).endsWith("/admin/login");
        assertThat(mockMvc.perform(get("/main/faq?q=무시").with(fromIp())).andReturn().getResponse().getStatus()).as("검색 파라미터는 무시").isEqualTo(200);
    }

    @Test
    @DisplayName("검색 입력은 hidden으로 내보내고 스크립트는 외부 파일 한 개뿐이다")
    void searchBoxIsProgressivelyEnhanced() throws Exception {
        long c = category("c", 0, true);
        faq(c, "q", "<p>a</p>", 0, true);

        String html = page();

        assertThat(html).contains("id=\"faq-search-box\" hidden").contains("src=\"/js/public/faq.js\"");
        assertThat(html.split("<script", -1).length - 1).isEqualTo(1);
    }
}
