package com.cms.publicweb.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공개 사이트 메뉴 템플릿 규약(PLAN-site-menu.md PR ⑤ P5-5·P5-1): 조각은 이스케이프 없는 출력·스크립트·인라인 이벤트가 없고 외부 링크는
 * {@code noopener noreferrer}이며, 사이트를 아는 정상 공개 화면은 모두 이 조각과 CSS를 포함한다(새 화면에서 빠뜨리면 CI가 잡는다).
 */
class PublicSiteMenuTemplateConventionTest {

    private static final Path PUBLIC_TEMPLATES = Paths.get("src/main/resources/templates/public");
    private static final Path FRAGMENT = PUBLIC_TEMPLATES.resolve("fragments/site-menu.html");
    private static final Path CSS = Paths.get("src/main/resources/static/css/public/site-menu.css");

    /** 사이트를 알 수 없는 공유 오류 뷰와 조각 파일 자체는 메뉴를 포함하지 않는다. */
    private static final List<Path> EXEMPT = List.of(
            PUBLIC_TEMPLATES.resolve("notice/error.html"),
            PUBLIC_TEMPLATES.resolve("fragments/home-popups.html"),
            FRAGMENT);

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 파서 수준 주석 안의 설명 문구는 검사 대상에서 뺀다. */
    private static String codeOf(Path path) throws IOException {
        return read(path).replaceAll("(?s)<!--/\\*.*?\\*/-->", "");
    }

    @Test
    @DisplayName("메뉴 조각에는 th:utext·인라인 비이스케이프 출력·스크립트·on* 이벤트가 없다")
    void fragmentHasNoUnescapedOutputOrScript() throws IOException {
        String code = codeOf(FRAGMENT);

        assertThat(code).doesNotContain("th:utext").doesNotContain("[(").doesNotContain("<script");
        assertThat(Pattern.compile("\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE).matcher(code).find())
                .as("인라인 이벤트 속성").isFalse();
    }

    @Test
    @DisplayName("메뉴 이름은 th:text로만, 링크는 th:href=\"${item.url}\"로만 나오고 외부 링크는 새 탭 + noopener noreferrer")
    void fragmentOutputsAreEscapedAndExternalLinksAreHardened() throws IOException {
        String code = codeOf(FRAGMENT);

        assertThat(code).contains("th:text=\"${item.name}\"").contains("th:href=\"${item.url}\"");
        assertThat(code).doesNotContain("th:href=\"@{");
        assertThat(code).containsPattern(
                "item\\.external\\}\"\\s+th:href=\"\\$\\{item\\.url\\}\"[^>]*target=\"_blank\"\\s+rel=\"noopener noreferrer\"");
    }

    @Test
    @DisplayName("스타일은 하위 메뉴를 숨기지 않는다(display:none·visibility:hidden·hover 펼침 금지 — 키보드·터치 접근)")
    void cssNeverHidesSubmenus() throws IOException {
        // 주석(설명 문구)은 제외하고 규칙만 본다.
        String css = read(CSS).replaceAll("(?s)/\\*.*?\\*/", "");

        assertThat(css).doesNotContain("display: none").doesNotContain("display:none")
                .doesNotContain("visibility: hidden").doesNotContain("visibility:hidden")
                .doesNotContain("focus-within").doesNotContain("hidden");
        // :hover는 링크 밑줄 같은 장식에는 쓸 수 있지만, 하위 목록(.site-menu-list .site-menu-list)을 여는 규칙에는 쓸 수 없다.
        assertThat(css).doesNotContainPattern("(?s):hover[^{]*\\.site-menu-list[^{]*\\.site-menu-list");
    }

    @Test
    @DisplayName("사이트를 아는 정상 공개 템플릿은 모두 메뉴 조각과 site-menu.css를 포함한다")
    void everyNormalPublicTemplateIncludesTheMenu() throws IOException {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(PUBLIC_TEMPLATES)) {
            for (Path template : stream.filter(path -> path.toString().endsWith(".html")).toList()) {
                if (EXEMPT.contains(template)) {
                    continue;
                }
                String source = read(template);
                if (!source.contains("~{public/fragments/site-menu :: siteMenu}")) {
                    missing.add(template + " (조각)");
                }
                if (!source.contains("@{/css/public/site-menu.css}")) {
                    missing.add(template + " (CSS)");
                }
            }
        }
        assertThat(missing).isEmpty();
    }
}
