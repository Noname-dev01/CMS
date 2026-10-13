package com.cms.admin.site;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사이트 키 예약어가 실제 최상위 경로를 모두 덮는지 강제한다(PLAN-site-menu.md D2·R4). 사이트 키는 공개 경로 접두어({@code /{siteKey}/…})가 될 값이라,
 * 새 공개·정적 경로를 추가하면서 {@link SiteKey#RESERVED}를 갱신하지 않으면 그 이름을 쓴 사이트가 기존 경로를 가리게 된다.
 *
 * <p>검사 대상(소스 텍스트 스캔, {@code ClockUsageConventionTest}와 같은 방식): {@code src/main/java} 전체의 요청 매핑 어노테이션 문자열과
 * {@code SecurityConfig}의 경로 리터럴의 <b>첫 세그먼트</b>. 사이트 키 형식을 만족하지 않는 세그먼트(점이 든 {@code favicon.ico}·{@code swagger-ui.html},
 * {@code {변수}}·와일드카드, 빈 값)는 사이트 키가 될 수 없으므로 제외한다.
 */
class SiteKeyReservedConventionTest {

    private static final Path MAIN_JAVA = Paths.get("src/main/java");
    private static final Path SECURITY_CONFIG = Paths.get("src/main/java/com/cms/config/SecurityConfig.java");

    /** {@code @GetMapping("/x")}·{@code @RequestMapping(value = "/x")}·{@code @PostMapping({"/x", "/y"})}의 첫 문자열. */
    private static final Pattern MAPPING = Pattern.compile(
            "@(?:Request|Get|Post|Put|Patch|Delete)Mapping\\(\\s*(?:(?:value|path)\\s*=\\s*)?\\{?\\s*\"(/[^\"]*)\"");

    /** 클래스 선언 앞의 {@code @RequestMapping("/x")} — 있으면 그 파일의 최상위 경로. */
    private static final Pattern CLASS_REQUEST_MAPPING = Pattern.compile(
            "@RequestMapping\\(\\s*(?:(?:value|path)\\s*=\\s*)?\\{?\\s*\"(/[^\"]*)\"");

    /** {@code SecurityConfig}의 {@code "/..."} 문자열 리터럴(requestMatchers 인자·정적 경로 배열 모두). */
    private static final Pattern PATH_LITERAL = Pattern.compile("\"(/[^\"\\s]*)\"");

    private static String firstSegment(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.indexOf('/');
        return slash < 0 ? trimmed : trimmed.substring(0, slash);
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> stream = Files.walk(MAIN_JAVA)) {
            return stream.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static List<String> firstSegmentsIn(String source, Pattern pattern) {
        List<String> segments = new ArrayList<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            segments.add(firstSegment(matcher.group(1)));
        }
        return segments;
    }

    /**
     * 컨트롤러 한 파일의 최상위 경로 첫 세그먼트. 클래스 수준 {@code @RequestMapping}이 있으면 그 경로만 최상위이고(메서드 매핑은 그 아래),
     * 없으면 메서드 수준 매핑이 절대 경로다.
     */
    private static List<String> topLevelSegments(String source) {
        int classAt = source.indexOf(" class ");
        String beforeClass = classAt < 0 ? source : source.substring(0, classAt);
        Matcher classLevel = CLASS_REQUEST_MAPPING.matcher(beforeClass);
        if (classLevel.find()) {
            return List.of(firstSegment(classLevel.group(1)));
        }
        return firstSegmentsIn(source, MAPPING);
    }

    private static List<String> keyEligible(List<String> segments) {
        return segments.stream().filter(SiteKey::isValid).distinct().sorted().toList();
    }

    @Test
    @DisplayName("요청 매핑(컨트롤러)의 최상위 경로 첫 세그먼트는 모두 사이트 키 예약어다")
    void controllerMappingsAreReserved() throws IOException {
        List<String> segments = new ArrayList<>();
        for (Path source : javaSources()) {
            segments.addAll(topLevelSegments(Files.readString(source, StandardCharsets.UTF_8)));
        }
        List<String> missing = keyEligible(segments).stream().filter(segment -> !SiteKey.isReserved(segment)).toList();
        assertThat(missing)
                .as("새 최상위 경로를 추가했다면 SiteKey.RESERVED에도 넣어야 한다(사이트 키가 그 경로를 가린다)")
                .isEmpty();
        // 검사가 아무것도 못 찾는 상태(정규식 고장)를 막는다 — 알려진 최상위 리터럴 경로가 실제로 잡혀야 한다.
        // 공지·게시판·정적 페이지·FAQ·문의는 PR ② 이후 /{siteKey} 아래(PublicSiteRoutes 상수)라 더는 최상위 리터럴이 아니다 — 아래 별도 단언이 맡는다.
        assertThat(keyEligible(segments)).contains("content-images", "banners", "popups", "admin", "error");
    }

    @Test
    @DisplayName("SecurityConfig의 경로 리터럴(requestMatchers·정적 경로) 첫 세그먼트는 모두 사이트 키 예약어다")
    void securityConfigPathsAreReserved() throws IOException {
        String source = Files.readString(SECURITY_CONFIG, StandardCharsets.UTF_8);
        List<String> segments = firstSegmentsIn(source, PATH_LITERAL);
        List<String> missing = keyEligible(segments).stream().filter(segment -> !SiteKey.isReserved(segment)).toList();
        assertThat(missing)
                .as("SecurityConfig에 새 최상위 경로를 열었다면 SiteKey.RESERVED에도 넣어야 한다")
                .isEmpty();
        assertThat(keyEligible(segments)).contains("admin", "actuator", "css", "js", "img", "vendor", "swagger-ui", "v3");
    }

    @Test
    @DisplayName("옛 최상위 공개 경로 이름(notices·boards·pages·faq·inquiry)은 사이트 범위 경로로 옮겨진 뒤에도 예약어로 남는다 — 옛 URL 404와 키 충돌 방지")
    void legacyTopLevelNamesStayReserved() {
        for (String name : List.of("notices", "boards", "pages", "faq", "inquiry")) {
            assertThat(SiteKey.isReserved(name)).as(name).isTrue();
        }
    }

    @Test
    @DisplayName("사이트 범위 경로(PublicSiteRoutes)의 두 번째 세그먼트도 예약어다 — 새 사이트 범위 경로를 추가하면 옛 이름과의 충돌을 같이 막는다")
    void siteScopedSecondSegmentsAreReserved() {
        String prefix = com.cms.publicweb.support.PublicSiteRoutes.HOME;
        List<String> constants = List.of(
                com.cms.publicweb.support.PublicSiteRoutes.NOTICES, com.cms.publicweb.support.PublicSiteRoutes.BOARDS,
                com.cms.publicweb.support.PublicSiteRoutes.PAGES, com.cms.publicweb.support.PublicSiteRoutes.FAQ,
                com.cms.publicweb.support.PublicSiteRoutes.INQUIRY);
        for (String route : constants) {
            assertThat(route).startsWith(prefix + "/");
            String second = firstSegment(route.substring(prefix.length()));
            assertThat(SiteKey.isReserved(second)).as("사이트 범위 경로 " + route + " 의 둘째 세그먼트 " + second).isTrue();
        }
    }

    @Test
    @DisplayName("예약어 집합에는 사이트 키 형식을 만족하지 않는 값이 없다(점 포함 이름은 형식에서 먼저 탈락하므로 넣지 않는다)")
    void reservedWordsAreWellFormed() {
        TreeSet<String> malformed = new TreeSet<>();
        for (String word : SiteKey.RESERVED) {
            if (!SiteKey.isValid(word)) {
                malformed.add(word);
            }
        }
        assertThat(malformed).isEmpty();
    }
}
