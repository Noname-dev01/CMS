package com.cms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 시각 원천 단일화 컨벤션(M-05) 강제: 저장·조회 코드는 주입된 {@code Clock}만 쓴다.
 *
 * <p>JVM 기본 시간대 의존 호출({@code LocalDateTime.now()} 등)이 다시 들어오면 실패한다.
 * {@code main()}이 기본 시간대를 KST로 고정하므로 운영에서는 당장 값이 같지만, 테스트 JVM(CI는 UTC)처럼
 * {@code main()}을 거치지 않는 경로에서 시각 원천이 갈라지는 것을 막는다.
 *
 * <p>보장 범위: 아래 허용 목록 외 모든 {@code now(...)} 호출은 인자가 {@code clock}이어야 한다.
 * 허용 목록 2건은 각각 이유가 있다 — 새 항목을 추가하려면 이유를 여기에 남긴다.
 */
class ClockUsageConventionTest {

    private static final Path MAIN_SOURCE = Paths.get("src", "main", "java");

    private static final String TIME_TYPES =
            "LocalDateTime|LocalDate|LocalTime|Instant|ZonedDateTime|OffsetDateTime|OffsetTime|Year|YearMonth";

    // 점·괄호 앞뒤 공백/개행을 허용한다(`LocalDateTime .now ()` 같은 우회 방지)
    private static final Pattern NOW_CALL = Pattern.compile(
            "\\b(" + TIME_TYPES + ")\\s*\\.\\s*now\\s*\\(([^)]*)\\)");

    private static final Pattern CLOCK_FACTORY = Pattern.compile(
            "\\bClock\\s*\\.\\s*(systemDefaultZone|systemUTC|system)\\s*\\(");

    // 정적 import(`import static java.time.Clock.systemUTC;` 등)는 위 호출 패턴을 우회하므로 금지한다
    private static final Pattern STATIC_IMPORT = Pattern.compile(
            "import\\s+static\\s+java\\s*\\.\\s*time\\s*\\.\\s*(" + TIME_TYPES + "|Clock)\\s*\\.\\s*"
                    + "(\\*|now|systemDefaultZone|systemUTC|system)\\s*;");

    /** 파일명 → 허용되는 now() 인자(공백 제거 후). */
    private static final Map<String, String> ALLOWED_NOW_ARGUMENT = Map.of(
            // 저장 디렉터리 샤딩 이름(yyyy/MM/dd)일 뿐 시각 비교·저장 의미가 없다
            "LocalDiskFileStorage.java", "",
            // static 팩토리라 Clock 주입이 불가능한 응답 전용 timestamp — 시간대만 AppConfig.KST로 통일
            "ApiErrorResponse.java", "AppConfig.KST"
    );

    /** Clock 팩토리 호출을 허용하는 유일한 파일(빈 정의). */
    private static final String CLOCK_FACTORY_OWNER = "AppConfig.java";

    @Test
    @DisplayName("src/main/java의 now() 호출은 주입된 clock을 쓰며, 허용 목록 외 시스템 기본 시각 호출이 없다")
    void mainCodeUsesInjectedClockOnly() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(MAIN_SOURCE)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                for (String violation : findViolations(file.getFileName().toString(), Files.readString(file))) {
                    violations.add(file + " → " + violation);
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "주입된 Clock 대신 시스템 기본 시각을 쓰는 호출이 있다(시각 원천 단일화 위반):\n"
                        + String.join("\n", violations));
    }

    @Test
    @DisplayName("검사 자체 회귀: 정적 import·공백·직접 Clock 생성 우회 사례는 위반으로 잡고, 허용 사례는 통과시킨다")
    void checkerDetectsBypassesAndAllowsPermittedUsage() {
        // 위반이어야 하는 우회 사례
        assertViolation("Foo.java", "var t = LocalDateTime.now();");
        assertViolation("Foo.java", "var t = LocalDateTime\n    .now\n    ();");
        assertViolation("Foo.java", "var t = LocalDate.now(ZoneId.of(\"UTC\"));");
        assertViolation("Foo.java", "import static java.time.Clock.systemUTC;\nClock c = systemUTC();");
        assertViolation("Foo.java", "import static java.time.LocalDateTime.now;\nvar t = now();");
        assertViolation("Foo.java", "import static java.time.Clock.*;");
        assertViolation("Foo.java", "Clock c = Clock . systemDefaultZone ();");
        assertViolation("Service.java", "Clock c = Clock.system(ZoneId.of(\"Asia/Seoul\"));");

        // 위반이 아니어야 하는 허용 사례
        assertNoViolation("Foo.java", "var t = LocalDateTime.now(clock);");
        assertNoViolation("Foo.java", "var t = LocalDateTime . now ( clock );");
        assertNoViolation("Foo.java", "// LocalDateTime.now() 는 주석이라 무시\n/* Instant.now() */");
        assertNoViolation("AppConfig.java", "return Clock.system(KST);");
        assertNoViolation("LocalDiskFileStorage.java", "var d = LocalDate.now();");
        assertNoViolation("ApiErrorResponse.java", "var t = LocalDateTime.now(AppConfig.KST);");
        // 허용 목록은 파일·인자가 함께 맞아야 한다
        assertViolation("LocalDiskFileStorage.java", "var d = LocalDate.now(clock2);");
        assertViolation("ApiErrorResponse.java", "var t = LocalDateTime.now();");
    }

    private static void assertViolation(String fileName, String source) {
        assertTrue(!findViolations(fileName, source).isEmpty(), "위반으로 잡혀야 한다: " + source);
    }

    private static void assertNoViolation(String fileName, String source) {
        List<String> found = findViolations(fileName, source);
        assertTrue(found.isEmpty(), "위반이 아니어야 한다: " + source + " → " + found);
    }

    private static List<String> findViolations(String fileName, String rawSource) {
        List<String> violations = new ArrayList<>();
        String source = stripComments(rawSource);

        Matcher now = NOW_CALL.matcher(source);
        while (now.find()) {
            String argument = now.group(2).replaceAll("\\s+", "");
            boolean injected = argument.equals("clock");
            boolean allowed = argument.equals(ALLOWED_NOW_ARGUMENT.get(fileName));
            if (!injected && !allowed) {
                violations.add(now.group().replaceAll("\\s+", " "));
            }
        }

        if (!fileName.equals(CLOCK_FACTORY_OWNER)) {
            Matcher factory = CLOCK_FACTORY.matcher(source);
            while (factory.find()) {
                violations.add(factory.group().replaceAll("\\s+", " ") + " (Clock 생성은 AppConfig에서만)");
            }
        }

        Matcher staticImport = STATIC_IMPORT.matcher(source);
        while (staticImport.find()) {
            violations.add(staticImport.group().replaceAll("\\s+", " ") + " (정적 import는 검사를 우회하므로 금지)");
        }
        return violations;
    }

    private static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "");
    }
}
