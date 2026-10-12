package com.cms.publicweb.faq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 공개 FAQ 템플릿의 이스케이프 없는 출력은 <b>답변 한 곳</b>이다(PLAN-faq.md 쟁점 7·8). 그 값({@code item.answerHtml})은
 * {@code ContentBodyPolicy.sanitizeForFaqOutput}(이미지 불허 정리)을 거친 HTML이다. 카테고리 이름·질문은 전부 {@code th:text}다. 스크립트는 외부 파일
 * 한 개뿐이고 인라인 스크립트·이벤트 핸들러 속성은 없다. 새 {@code th:utext}가 필요해지면 이 시험을 보고 허용 목록을 일부러 늘려야 한다.
 */
class PublicFaqTemplateConventionTest {

    private static final String TEMPLATE = "templates/public/faq/list.html";

    @Test
    @DisplayName("FAQ 템플릿에는 th:utext가 답변 한 곳뿐이고 인라인 비이스케이프 출력·인라인 스크립트·이벤트 핸들러·storage 내부 값이 없다")
    void onlyAnswerIsUnescaped() throws IOException {
        String content = read(TEMPLATE);

        int utextCount = 0;
        Matcher matcher = Pattern.compile("th:utext=\"([^\"]*)\"").matcher(content);
        while (matcher.find()) {
            utextCount++;
            assertThat(matcher.group(1)).as("th:utext는 정리된 답변 값만").isEqualTo("${item.answerHtml}");
        }
        assertThat(utextCount).as("답변 출력 한 곳").isEqualTo(1);
        assertFalse(content.contains("[("), "인라인 비이스케이프 출력이 있습니다.");
        assertFalse(Pattern.compile("\\son[a-z]+\\s*=").matcher(content).find(), "이벤트 핸들러 속성이 있습니다.");
        assertFalse(content.contains("storageKey"), "서버 내부 값이 있습니다.");

        Matcher scripts = Pattern.compile("<script([^>]*)>").matcher(content);
        int scriptCount = 0;
        while (scripts.find()) {
            scriptCount++;
            assertThat(scripts.group(1)).as("스크립트는 외부 파일만 — 인라인 금지").contains("th:src=\"@{/js/public/faq.js}\"");
        }
        assertThat(scriptCount).as("스크립트 한 개").isEqualTo(1);
    }

    @Test
    @DisplayName("카테고리 이름·질문은 th:text로만 쓴다")
    void namesAndQuestionsAreEscaped() throws IOException {
        String content = read(TEMPLATE);

        assertThat(content).contains("th:text=\"${category.name}\"").contains("th:text=\"${item.question}\"");
    }

    @Test
    @DisplayName("검색 입력 상자는 hidden으로 시작한다(스크립트가 꺼져 있으면 동작하지 않는 입력란이 보이지 않는다)")
    void searchBoxStartsHidden() throws IOException {
        assertThat(read(TEMPLATE)).containsPattern("id=\"faq-search-box\"[^>]*\\shidden");
    }

    private String read(String resource) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                fail("리소스를 찾을 수 없습니다: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
