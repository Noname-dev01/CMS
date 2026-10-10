package com.cms.support;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 락 대기 관측 단일화 컨벤션(이슈 #119·#121) 강제: 시험은 {@code information_schema.INNODB_LOCK_WAITS}·{@code INNODB_TRX}·{@code INNODB_LOCKS}를
 * 직접 폴링하지 않고 {@link LockWaitObserver}를 쓴다. 이 뷰들은 캐시로 돌려져 짧은 간격으로 읽으면 대기를 놓치는데(폴링 간격은 100ms를 넘겨야 한다),
 * 복제본이 시험마다 따로 있으면 한 곳만 고쳐지고 나머지가 남는다 — #119에서 한 시험만 고쳤을 때 같은 결함이 세 곳에 남았던 것이 #121이다.
 *
 * <p>보장 범위: {@code src/test/java}의 주석이 아닌 줄에서 위 뷰를 가리키는 코드는 {@code LockWaitObserver.java}(와 이 검사의 자체 회귀 시험)에만 있을 수 있다. 정적 스키마 조회처럼
 * 이 캐시와 무관한 {@code information_schema} 사용(테이블·컬럼 메타데이터)은 대상이 아니다.
 */
class LockWaitPollingConventionTest {

    private static final Path TEST_SOURCE = Paths.get("src", "test", "java");

    /** 락 관련 동적 뷰 — 공백·대소문자 우회도 잡는다. */
    private static final Pattern LOCK_VIEW = Pattern.compile(
            "information_schema\\s*\\.\\s*innodb_(lock_waits|trx|locks)\\b", Pattern.CASE_INSENSITIVE);

    /** 이 뷰를 직접 조회하는 코드가 있어도 되는 파일: 관측 헬퍼 본체, 그리고 이 검사의 자체 회귀 시험(위반 예시 문자열을 담고 있다). */
    private static final java.util.Set<String> ALLOWED_FILES = java.util.Set.of("LockWaitObserver.java", "LockWaitPollingConventionTest.java");

    private static final String OWNER = "LockWaitObserver.java";

    @Test
    @DisplayName("src/test/java에서 InnoDB 락 뷰를 직접 조회하는 코드는 LockWaitObserver뿐이다")
    void onlyLockWaitObserverQueriesLockViews() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(TEST_SOURCE)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                for (String violation : findViolations(file.getFileName().toString(), Files.readString(file))) {
                    violations.add(file + " → " + violation);
                }
            }
        }

        assertThat(violations)
                .as("InnoDB 락 뷰를 직접 폴링하는 시험이 있다 — LockWaitObserver를 쓰세요(이슈 #119·#121, 폴링 간격은 100ms 초과):\n"
                        + String.join("\n", violations))
                .isEmpty();
    }

    @Test
    @DisplayName("검사 자체 회귀: 공백·대소문자 우회는 위반으로 잡고, 주석·소유 파일·정적 스키마 조회는 통과시킨다")
    void checkerDetectsBypassesAndAllowsPermittedUsage() {
        // 위반이어야 하는 사례
        assertThat(findViolations("FooTest.java", "\"SELECT COUNT(*) FROM information_schema.INNODB_LOCK_WAITS\"")).hasSize(1);
        assertThat(findViolations("FooTest.java", "\"... FROM INFORMATION_SCHEMA . innodb_trx t\"")).hasSize(1);
        assertThat(findViolations("FooTest.java", "\"\"\"\n JOIN information_schema.INNODB_TRX holding ON ...\n\"\"\"")).hasSize(1);
        assertThat(findViolations("FooTest.java", "var q = \"select * from information_schema.innodb_locks\";")).hasSize(1);
        // 스키마명과 뷰명 사이에 개행이 있는 text block 서식(줄 단위 검사로는 놓친다) — 시작 줄 번호로 보고한다
        assertThat(findViolations("FooTest.java", "var a = 1;\n\"FROM information_schema.\nINNODB_LOCK_WAITS\"")).containsExactly("줄 2: \"FROM information_schema.");
        assertThat(findViolations("FooTest.java", "\"\"\"\n SELECT 1 FROM information_schema\n    .INNODB_TRX t\n\"\"\"")).hasSize(1);

        // 위반이 아니어야 하는 사례
        assertThat(findViolations(OWNER, "\"SELECT COUNT(*) FROM information_schema.INNODB_LOCK_WAITS\"")).isEmpty();
        assertThat(findViolations("FooTest.java", " * information_schema.INNODB_LOCK_WAITS 관측은 LockWaitObserver가 한다")).isEmpty();
        assertThat(findViolations("FooTest.java", "// information_schema.INNODB_TRX")).isEmpty();
        assertThat(findViolations("FooTest.java", "/**\n * information_schema.\n * INNODB_LOCK_WAITS 설명\n */")).isEmpty();
        assertThat(findViolations("FooTest.java", "\"SELECT table_name FROM information_schema.TABLES WHERE table_schema = ?\"")).isEmpty();
        assertThat(findViolations("FooTest.java", "\"SELECT column_name FROM information_schema.COLUMNS\"")).isEmpty();
    }

    private static List<String> findViolations(String fileName, String source) {
        List<String> violations = new ArrayList<>();
        if (ALLOWED_FILES.contains(fileName)) {
            return violations;
        }
        // 줄로 쪼개지 않고 전체 소스에서 매치한다 — 스키마명과 뷰명 사이의 개행(text block 서식)도 잡기 위해서다. 매치의 시작 줄이 주석 줄이면 건너뛴다.
        String[] lines = source.split("\\R", -1);
        Matcher matcher = LOCK_VIEW.matcher(source);
        while (matcher.find()) {
            int lineIndex = lineIndexOf(source, matcher.start());
            String trimmed = lines[lineIndex].trim();
            boolean comment = trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*");
            if (!comment) {
                violations.add("줄 " + (lineIndex + 1) + ": " + trimmed);
            }
        }
        return violations;
    }

    /** {@code offset}이 속한 줄의 0 기준 번호(앞에 나온 개행 수). */
    private static int lineIndexOf(String source, int offset) {
        int lineIndex = 0;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                lineIndex++;
            }
        }
        return lineIndex;
    }
}
