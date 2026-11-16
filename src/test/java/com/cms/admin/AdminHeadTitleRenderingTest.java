package com.cms.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.linkbuilder.StandardLinkBuilder;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 화면의 브라우저 탭 제목을 고정한다(이슈 #262).
 * <p>
 * 화면은 {@code <head th:replace="~{admin/fragments/head :: adminHead(~{::title})}">}로 공용 head 조각을 쓰고,
 * 자기 head에 적은 {@code <title>}을 조각에 넘긴다. 예전에는 조각이 매개변수 없이 고정 제목을 가져
 * 모든 화면의 탭 제목이 "관리자 대시보드"였다.
 * <p>
 * 실제 Thymeleaf 엔진으로 각 화면의 head만(템플릿 선택자 {@code head}) 렌더링해 확인한다 —
 * 본문은 모델·보안 방언이 필요해 이 시험의 범위가 아니다.
 */
class AdminHeadTitleRenderingTest {

    private static final Path TEMPLATES = Paths.get("src/main/resources/templates");
    private static final String ADMIN_HEAD_CALL = "admin/fragments/head :: adminHead";
    private static final Pattern SOURCE_HEAD = Pattern.compile("<head\\b[^>]*>(.*?)</head>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title\\b[^>]*>(.*?)</title>", Pattern.DOTALL);

    private static final TemplateEngine ENGINE = createEngine();

    static List<String> adminHeadTemplates() throws IOException {
        try (Stream<Path> stream = Files.walk(TEMPLATES)) {
            return stream.filter(path -> path.toString().endsWith(".html"))
                    .filter(path -> read(path).contains(ADMIN_HEAD_CALL))
                    .map(path -> TEMPLATES.relativize(path).toString().replace('\\', '/'))
                    .map(name -> name.substring(0, name.length() - ".html".length()))
                    .filter(name -> !name.equals("admin/fragments/head"))
                    .sorted()
                    .toList();
        }
    }

    @Test
    @DisplayName("공용 head 조각을 쓰는 관리자 화면을 모두 찾는다")
    void scan_findsAllAdminScreens() throws IOException {
        // 탐색이 깨져 0개가 되면 아래 매개변수 시험이 아무것도 검사하지 않고 통과한다.
        assertThat(adminHeadTemplates()).hasSizeGreaterThanOrEqualTo(19)
                .contains("admin/index", "admin/member/admin-settings");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminHeadTemplates")
    @DisplayName("렌더링된 탭 제목은 화면 head에 적은 제목 하나다")
    void renderedTitle_isScreenOwnTitle(String template) {
        String expected = sourceTitle(template);
        assertThat(expected).as("%s의 head에 <title>을 적어야 한다", template).isNotBlank();

        String rendered = ENGINE.process(template, Set.of("head"), csrfContext());

        assertThat(titles(rendered)).as("%s 렌더링 결과의 <title>", template).containsExactly(expected);
        // 공용 head의 나머지(CSRF meta·공용 CSS)가 함께 렌더링되는지 확인한다.
        assertThat(rendered).contains("name=\"_csrf\"", "/css/sb-admin-2.min.css");
    }

    @Test
    @DisplayName("대시보드는 '관리자 대시보드', 다른 화면은 그 화면 이름이다")
    void dashboardAndOtherScreens_haveDistinctTitles() {
        assertThat(titles(ENGINE.process("admin/index", Set.of("head"), csrfContext())))
                .containsExactly("관리자 대시보드");
        assertThat(titles(ENGINE.process("admin/member/admin-settings", Set.of("head"), csrfContext())))
                .containsExactly("보안 설정");
    }

    @Test
    @DisplayName("본문의 속성 있는 <title>이 head로 끌려 들어가 제목이 중복되면 검출한다")
    void duplicatedTitleFromBody_isDetected() {
        // ~{::title}은 템플릿 전체에서 고른다. 검출이 <title>만 세면 <title id=…>를 놓친다(코드 리뷰 지적).
        String rendered = ENGINE.process("admin-head-fixture/duplicate-title", Set.of("head"), csrfContext());

        assertThat(titles(rendered)).containsExactly("픽스처 화면", "아이콘");
    }

    private static String sourceTitle(String template) {
        Matcher head = SOURCE_HEAD.matcher(read(TEMPLATES.resolve(template + ".html")));
        if (!head.find()) {
            return "";
        }
        List<String> titles = titles(head.group(1));
        return titles.size() == 1 ? titles.get(0) : "";
    }

    private static List<String> titles(String html) {
        return TITLE.matcher(html).results().map(result -> result.group(1).trim()).toList();
    }

    private static Context csrfContext() {
        Context context = new Context();
        context.setVariable("_csrf", Map.of("token", "test-token", "headerName", "X-CSRF-TOKEN"));
        return context;
    }

    private static TemplateEngine createEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(false);

        // 운영과 같은 SpEL 표현식 엔진을 쓴다(기본 TemplateEngine은 클래스패스에 없는 OGNL을 찾는다).
        TemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        // 웹 컨텍스트가 없으면 기본 링크 빌더가 @{/...}(컨텍스트 상대 경로)를 거부하므로 컨텍스트 경로를 비운다.
        engine.setLinkBuilder(new StandardLinkBuilder() {
            @Override
            protected String computeContextPath(IExpressionContext context, String base, Map<String, Object> parameters) {
                return "";
            }
        });
        return engine;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(path.toString(), e);
        }
    }
}
