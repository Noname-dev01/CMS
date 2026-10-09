package com.cms.publicweb.board;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** 공개 게시판 템플릿의 이스케이프 없는 출력은 정리된 본문 한 곳뿐이다(공지의 PublicNoticeTemplateConventionTest와 같은 규칙 — PLAN-board.md 쟁점 10). */
class PublicBoardTemplateConventionTest {

    private static final String[] TEMPLATES = {
            "templates/public/board/list.html",
            "templates/public/board/detail.html"
    };

    private static final String ALLOWED_UTEXT = "th:utext=\"${post.content}\"";

    @Test
    @DisplayName("공개 게시판 템플릿의 th:utext는 detail.html 본문 한 곳뿐이다")
    void publicBoardTemplates_useThUtextOnlyForSanitizedContent() throws IOException {
        for (String path : TEMPLATES) {
            String content = readResource(path);
            boolean detail = path.endsWith("detail.html");
            String rest = detail ? content.replaceFirst(java.util.regex.Pattern.quote(ALLOWED_UTEXT), "") : content;
            if (detail) {
                assertTrue(content.contains(ALLOWED_UTEXT), "detail.html 본문 출력이 바뀌었습니다 — 허용 목록을 함께 검토하세요.");
            }
            assertFalse(rest.contains("th:utext"), path + "에 허용되지 않은 th:utext가 있습니다 — th:text만 사용해야 합니다.");
            assertFalse(rest.contains("[(") , path + "에 인라인 비이스케이프 출력([( ... )])이 있습니다.");
        }
    }

    @Test
    @DisplayName("공개 게시판 템플릿에 storageKey(서버 내부 경로) 문자열이 없다")
    void templates_doNotReferenceStorageKey() throws IOException {
        for (String path : TEMPLATES) {
            assertFalse(readResource(path).contains("storageKey"), path + "에 storageKey가 있습니다 — 서버 내부 경로가 새어나갈 수 있습니다.");
        }
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
