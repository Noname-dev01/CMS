package com.cms.publicweb.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공개 템플릿의 링크 접두어화 규약(PLAN-site-menu.md PR ② P2-6, 리뷰 라운드 1 지적 4). 모든 공개 내부 링크는 {@code ${sitePrefix}}를 앞에 붙여 만들고,
 * 접두어 없는 옛 경로나 루트 {@code @{/}}가 남으면 두 번째 사이트에서 링크가 기본 사이트로 새거나 404가 된다. 허용 예외: 사이트 무관 경로(정적 리소스,
 * 배너·팝업·본문 이미지)와, 사이트를 알 수 없는 오류 템플릿의 {@code @{/}}(→ 기본 사이트로 302).
 */
class PublicSiteTemplateConventionTest {

    private static final Path PUBLIC_TEMPLATES = Paths.get("src/main/resources/templates/public");
    private static final List<Path> ERROR_TEMPLATES = List.of(
            Paths.get("src/main/resources/templates/public/error.html"),
            Paths.get("src/main/resources/templates/error/404.html"),
            Paths.get("src/main/resources/templates/error/429.html"),
            Paths.get("src/main/resources/templates/error/403.html"));

    /** {@code @{…}} 링크 표현식 전체(중첩 {@code {id}} 포함). */
    private static final Pattern LINK = Pattern.compile("@\\{((?:[^{}]|\\{[^{}]*})*)}");

    private static final Pattern SITE_NEUTRAL_TARGET = Pattern.compile("^/(css|js|img|vendor|banners|popups|content-images)(/|$)");

    private static List<Path> publicTemplates() throws IOException {
        try (Stream<Path> stream = Files.walk(PUBLIC_TEMPLATES)) {
            return stream.filter(path -> path.toString().endsWith(".html")).toList();
        }
    }

    @Test
    @DisplayName("공개 템플릿(정상 화면)의 내부 링크는 사이트 무관 경로가 아니면 모두 ${sitePrefix}로 시작한다 — 옛 경로·루트 @{/} 금지")
    void internalLinksCarryTheSitePrefix() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path template : publicTemplates()) {
            if (ERROR_TEMPLATES.contains(template)) {
                continue;
            }
            String source = Files.readString(template, StandardCharsets.UTF_8);
            Matcher matcher = LINK.matcher(source);
            while (matcher.find()) {
                String expression = matcher.group(1);
                String target = expression.startsWith("|") ? expression.substring(1) : expression;
                if (target.startsWith("${sitePrefix}") || SITE_NEUTRAL_TARGET.matcher(target).find()) {
                    continue;
                }
                violations.add(template.getFileName() + ": @{" + expression + "}");
            }
        }
        assertThat(violations)
                .as("내부 링크는 @{|${sitePrefix}/…|} 형태여야 한다(사이트 무관: /css /js /img /vendor /banners /popups /content-images)")
                .isEmpty();
    }

    @Test
    @DisplayName("옛 접두어 없는 경로 문자열(/notices·/boards·/pages·/faq·/inquiry)이 공개 템플릿과 오류 템플릿 어디에도 링크로 남아 있지 않다")
    void noLegacyPrefixlessPathsRemain() throws IOException {
        Pattern legacy = Pattern.compile("@\\{\\s*/(notices|boards|pages|faq|inquiry)(?=[/}(?|])");
        List<String> violations = new ArrayList<>();
        List<Path> all = new ArrayList<>(publicTemplates());
        all.addAll(ERROR_TEMPLATES);
        for (Path template : all) {
            String source = Files.readString(template, StandardCharsets.UTF_8);
            if (legacy.matcher(source).find()) {
                violations.add(template.toString());
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("루트 링크 @{/}는 사이트를 알 수 없는 오류 템플릿에서만 쓴다")
    void rootLinkOnlyInErrorTemplates() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path template : publicTemplates()) {
            if (ERROR_TEMPLATES.contains(template)) {
                continue;
            }
            if (Files.readString(template, StandardCharsets.UTF_8).contains("@{/}")) {
                violations.add(template.toString());
            }
        }
        assertThat(violations).isEmpty();
        for (Path error : ERROR_TEMPLATES) {
            String source = Files.readString(error, StandardCharsets.UTF_8);
            assertThat(source).as(error + " 는 기본 사이트로 302되는 @{/}를 쓴다").contains("@{/}").doesNotContain("${sitePrefix}");
        }
    }
}
