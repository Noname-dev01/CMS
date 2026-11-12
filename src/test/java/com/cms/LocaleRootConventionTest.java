package com.cms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 대소문자 변환 로케일 컨벤션 강제: {@code toLowerCase()}·{@code toUpperCase()}는 인자 없이 쓰지 않는다.
 *
 * <p>인자 없는 호출은 JVM 기본 로케일을 따르므로 tr/az 로케일에서 {@code "GIF".toLowerCase()}가 {@code "gıf"}가 되는 식으로
 * 확장자·이메일·키 비교가 깨진다(2026-10-07 전수 정리, {@code adversarial-review/plan/PLAN-extension-locale-root.md}).
 * 그때까지는 리뷰로만 지켰으므로 다시 들어오는 것을 여기서 막는다.
 *
 * <p>보장 범위: {@code src/main/java}에서 주석을 뺀 소스의 인자 없는 {@code toLowerCase}/{@code toUpperCase} 호출(점·괄호 앞뒤 공백·개행 포함).
 * 한계: 메서드 참조({@code String::toLowerCase})와 시험 코드는 검사하지 않는다 — 메서드 참조는 인자 없는 오버로드로 해석되므로
 * 쓰지 말고 람다로 {@code Locale.ROOT}를 넘긴다.
 */
class LocaleRootConventionTest {

    private static final Path MAIN_SOURCE = Paths.get("src", "main", "java");

    // `.toLowerCase()`·`. toUpperCase ( )` 등 인자 없는 호출
    private static final Pattern NO_ARG_CASE_CALL = Pattern.compile(
            "\\.\\s*(toLowerCase|toUpperCase)\\s*\\(\\s*\\)");

    @Test
    @DisplayName("src/main/java의 toLowerCase·toUpperCase 호출은 모두 로케일 인자를 넘긴다")
    void mainCodePassesLocaleToCaseConversion() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(MAIN_SOURCE)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                for (String violation : findViolations(Files.readString(file))) {
                    violations.add(file + " → " + violation);
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "로케일 없이 대소문자를 바꾸는 호출이 있다 — Locale.ROOT를 넘긴다:\n" + String.join("\n", violations));
    }

    @Test
    @DisplayName("검사 자체 회귀: 공백·개행 우회는 위반으로 잡고, 로케일 인자·주석은 통과시킨다")
    void checkerDetectsBypassesAndAllowsLocaleArgument() {
        assertViolation("var e = ext.toLowerCase();");
        assertViolation("var e = ext.toUpperCase();");
        assertViolation("var e = ext . toLowerCase ( );");
        assertViolation("var e = ext\n    .toLowerCase\n    ();");

        assertNoViolation("var e = ext.toLowerCase(Locale.ROOT);");
        assertNoViolation("var e = ext.toUpperCase(java.util.Locale.ROOT);");
        assertNoViolation("// ext.toLowerCase() 는 주석이라 무시\n/* ext.toUpperCase() */");
        assertNoViolation("boolean same = a.equalsIgnoreCase(b);");
    }

    private static void assertViolation(String source) {
        assertTrue(!findViolations(source).isEmpty(), "위반으로 잡혀야 한다: " + source);
    }

    private static void assertNoViolation(String source) {
        List<String> found = findViolations(source);
        assertTrue(found.isEmpty(), "위반이 아니어야 한다: " + source + " → " + found);
    }

    private static List<String> findViolations(String rawSource) {
        List<String> violations = new ArrayList<>();
        Matcher call = NO_ARG_CASE_CALL.matcher(stripComments(rawSource));
        while (call.find()) {
            violations.add(call.group().replaceAll("\\s+", ""));
        }
        return violations;
    }

    private static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "");
    }
}
