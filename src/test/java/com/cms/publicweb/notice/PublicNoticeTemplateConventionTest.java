package com.cms.publicweb.notice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 컨벤션 검증: 공개 공지 템플릿은 {@code th:utext}를 쓰지 않는다(PLAN-public-notice.md 결정 5).
 * 공지 제목·본문은 관리 화면에서 plain text로만 입력받으므로, 뷰에서 이스케이프 없이
 * 출력(th:utext)하면 저장형 XSS로 이어진다. Thymeleaf 엔진 없이 파일 텍스트만 정적으로 검사한다.
 */
class PublicNoticeTemplateConventionTest {

    private static final String[] TEMPLATES = {
            "templates/public/notice/list.html",
            "templates/public/notice/detail.html",
            "templates/public/notice/error.html"
    };

    @Test
    @DisplayName("공개 공지 템플릿에 th:utext가 없다")
    void publicNoticeTemplates_doNotUseThUtext() throws IOException {
        for (String path : TEMPLATES) {
            String content = readResource(path);
            assertFalse(content.contains("th:utext"), path + "에 th:utext가 있습니다 — th:text만 사용해야 합니다.");
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
