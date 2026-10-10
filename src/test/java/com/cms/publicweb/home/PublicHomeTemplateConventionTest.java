package com.cms.publicweb.home;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 공개 메인 템플릿은 이스케이프 없는 출력이 <b>한 곳도 없다</b>(PLAN-public-home-banner.md 쟁점 12·13): 배너 제목·게시글 제목·게시판 이름은 모두
 * 사용자 입력이고, 메인은 본문 HTML을 출력하지 않는다. 새 {@code th:utext}가 필요해지면 이 시험을 보고 허용 목록을 일부러 늘려야 한다.
 * 배너 링크({@code th:href})는 {@code PublicBanner}가 {@code SafeUrls}로 거른 값만 받는다.
 */
class PublicHomeTemplateConventionTest {

    private static final String TEMPLATE = "templates/public/home.html";

    @Test
    @DisplayName("공개 메인 템플릿에는 th:utext·인라인 비이스케이프 출력([( ... )])·인라인 스크립트가 없다")
    void homeTemplate_hasNoUnescapedOutput() throws IOException {
        String content = read();

        assertFalse(content.contains("th:utext"), "home.html에 th:utext가 있습니다 — th:text만 사용해야 합니다.");
        assertFalse(content.contains("[("), "home.html에 인라인 비이스케이프 출력이 있습니다.");
        assertFalse(content.contains("<script"), "home.html에 스크립트가 있습니다 — 메인은 JS 없이 동작해야 합니다.");
        assertFalse(content.contains("storageKey"), "home.html에 storageKey(서버 내부 경로)가 있습니다.");
    }

    @Test
    @DisplayName("배너 이미지는 th:src로 공개 경로(/banners/{id}/image)만 가리키고 제목은 th:alt(속성 이스케이프)로만 쓴다")
    void bannerImage_usesPublicPathAndEscapedAlt() throws IOException {
        String content = read();

        assertFalse(content.contains("src=\"${"), "이미지 src는 th:src 링크 표현식(@{...})만 써야 합니다.");
        assertFalse(content.contains("th:src=\"${"), "이미지 src에 임의 문자열 표현식을 쓰면 안 됩니다.");
        assertFalse(content.contains("th:alt=\"${banner.title}\" th:utext"), "제목을 비이스케이프로 쓰면 안 됩니다.");
    }

    private String read() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                fail("리소스를 찾을 수 없습니다: " + TEMPLATE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
