package com.cms.publicweb.board;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            String rest = detail ? content.replaceFirst(Pattern.quote(ALLOWED_UTEXT), "") : content;
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

    @Test
    @DisplayName("공개 게시판 템플릿의 스크립트는 list.html의 외부 파일 board-thumb-fallback.js 하나뿐이고 인라인 스크립트·인라인 이벤트 속성이 없다")
    void scriptsAreExternalFallbackOnly() throws IOException {
        Pattern scriptTag = Pattern.compile("<script\\b([^>]*)>(.*?)</script>", Pattern.DOTALL);
        // on으로 시작하는 HTML 속성(onerror·onload 등)과 Thymeleaf 판(th:onerror 등) — 인라인 JS가 템플릿에 섞이지 않게 한다
        Pattern inlineHandler = Pattern.compile("\\s(?:th:)?on[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
        for (String path : TEMPLATES) {
            String content = readResource(path);
            Matcher script = scriptTag.matcher(content);
            int count = 0;
            while (script.find()) {
                count++;
                assertTrue(path.endsWith("list.html"), path + "에 스크립트가 있습니다 — 공개 게시판 스크립트는 list.html의 대체 스크립트 하나뿐입니다.");
                assertTrue(script.group(1).contains("th:src=\"@{/js/public/board-thumb-fallback.js}\""), "외부 스크립트는 board-thumb-fallback.js 하나여야 합니다.");
                assertEquals("", script.group(2).strip(), path + "에 인라인 스크립트 본문이 있습니다.");
            }
            assertEquals(path.endsWith("list.html") ? 1 : 0, count, path + "의 스크립트 개수가 다릅니다.");
            assertFalse(inlineHandler.matcher(content).find(), path + "에 인라인 이벤트 속성(on…=)이 있습니다 — 외부 스크립트로 옮기세요.");
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
