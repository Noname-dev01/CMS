package com.cms.publicweb.page;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 공개 정적 페이지 템플릿의 이스케이프 없는 출력은 <b>본문 한 곳</b>이다(PLAN-static-page.md 쟁점 7·12). 그 값({@code page.contentHtml})은
 * {@code ContentBodyPolicy.sanitizeForPageOutput}(이미지 불허 정리)을 거친 HTML이다. 제목·슬러그·개정 정보는 전부 {@code th:text}/경로 변수다.
 * 새 {@code th:utext}가 필요해지면 이 시험을 보고 허용 목록을 일부러 늘려야 한다.
 */
class PublicPageTemplateConventionTest {

    private static final List<String> TEMPLATES = List.of("templates/public/page/detail.html", "templates/public/page/revisions.html");

    @Test
    @DisplayName("정적 페이지 템플릿에는 th:utext가 상세의 본문 한 곳뿐이고 인라인 비이스케이프 출력·스크립트·storageKey가 없다")
    void onlyBodyIsUnescaped() throws IOException {
        int utextCount = 0;
        for (String template : TEMPLATES) {
            String content = read(template);
            Matcher matcher = Pattern.compile("th:utext=\"([^\"]*)\"").matcher(content);
            while (matcher.find()) {
                utextCount++;
                assertThat(template).as("th:utext는 상세 템플릿에만").endsWith("detail.html");
                assertThat(matcher.group(1)).as("th:utext는 정리된 본문 값만").isEqualTo("${page.contentHtml}");
            }
            assertFalse(content.contains("[("), template + "에 인라인 비이스케이프 출력이 있습니다.");
            assertFalse(content.contains("<script"), template + "에 스크립트가 있습니다 — 공개 정적 페이지는 JS 없이 동작해야 합니다.");
            assertFalse(content.contains("storageKey"), template + "에 서버 내부 값이 있습니다.");
        }
        assertThat(utextCount).as("본문 출력 한 곳").isEqualTo(1);
    }

    @Test
    @DisplayName("제목·개정 제목은 th:text로만 쓴다")
    void titlesAreEscaped() throws IOException {
        String detail = read("templates/public/page/detail.html");
        String revisions = read("templates/public/page/revisions.html");

        assertThat(detail).contains("th:text=\"${page.title}\"");
        assertThat(revisions).contains("th:text=\"${item.title}\"").contains("th:text=\"${revisions.title}\"");
    }

    @Test
    @DisplayName("공개 메인 푸터의 링크 제목은 th:text로만 쓴다")
    void homeFooterIsEscaped() throws IOException {
        String home = read("templates/public/home.html");

        assertThat(home).contains("th:text=\"${link.title}\"");
        assertFalse(home.contains("th:utext"), "home.html에 th:utext가 있습니다.");
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
