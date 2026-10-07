package com.cms.publicweb.notice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 컨벤션 검증: 공개 공지 템플릿의 {@code th:utext}는 상세 본문 한 곳({@code detail.html}의
 * {@code th:utext="${notice.content}"})만 허용한다(PLAN-public-notice.md 결정 5 → PLAN-html-editor.md 쟁점 12).
 * 그 값은 {@code PublicNoticeDetail.from}이 {@code HtmlContentSanitizer}로 정리한 HTML이다. 제목·첨부 파일명 등
 * 나머지 값을 이스케이프 없이 출력하면 저장형 XSS로 이어진다. Thymeleaf 엔진 없이 파일 텍스트만 정적으로 검사한다.
 */
class PublicNoticeTemplateConventionTest {

    private static final String[] TEMPLATES = {
            "templates/public/notice/list.html",
            "templates/public/notice/detail.html",
            "templates/public/notice/error.html"
    };

    private static final String ALLOWED_UTEXT = "th:utext=\"${notice.content}\"";

    @Test
    @DisplayName("공개 공지 템플릿의 th:utext는 detail.html 본문 한 곳뿐이다")
    void publicNoticeTemplates_useThUtextOnlyForSanitizedContent() throws IOException {
        for (String path : TEMPLATES) {
            String content = readResource(path);
            String rest = path.endsWith("detail.html") ? content.replaceFirst(java.util.regex.Pattern.quote(ALLOWED_UTEXT), "") : content;
            if (path.endsWith("detail.html")) {
                assertTrue(content.contains(ALLOWED_UTEXT), "detail.html 본문 출력이 바뀌었습니다 — 허용 목록을 함께 검토하세요.");
            }
            assertFalse(rest.contains("th:utext"), path + "에 허용되지 않은 th:utext가 있습니다 — th:text만 사용해야 합니다.");
        }
    }

    @Test
    @DisplayName("detail.html에 storageKey(서버 내부 경로) 문자열이 없다")
    void detailTemplate_doesNotReferenceStorageKey() throws IOException {
        String content = readResource("templates/public/notice/detail.html");
        assertFalse(content.contains("storageKey"), "detail.html에 storageKey가 있습니다 — 서버 내부 경로가 새어나갈 수 있습니다.");
    }

    private String readResource(String path) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                fail("리소스를 찾을 수 없습니다: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
