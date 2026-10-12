package com.cms.admin.faq;

import com.cms.support.MariaDbContainerSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V41(FAQ 카테고리·FAQ·가드 테이블)·V42(가드 행·FAQ 관리 메뉴 시드) — PLAN-faq.md 쟁점 14. 테이블·가드 행·메뉴 시드의 존재와 멱등, 컬럼·FK 제약,
 * 그리고 <b>FAQ 도입 전 앱(V40 이하 파일만)으로의 롤백 기동 호환</b>을 고정한다.
 */
class FaqMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "faq_v41_test";

    private static String rootUrl() {
        return "jdbc:mariadb://" + MARIA_DB.getHost() + ":" + MARIA_DB.getFirstMappedPort() + "/";
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
    }

    private static long count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void freshSchema() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement st = root.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }
    }

    private static String seedSql() throws Exception {
        try (var in = new PathMatchingResourcePatternResolver().getResource("classpath:db/migration/V42__seed_faq_menu.sql").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(line -> !line.startsWith("--")).reduce("", (a, b) -> a + "\n" + b);
        }
    }

    private static Path copyMigrationsUpTo(int maxVersion) throws Exception {
        Path dir = Files.createTempDirectory("migration-up-to-v" + maxVersion);
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql")) {
            String name = resource.getFilename();
            if (name != null && Integer.parseInt(name.substring(1, name.indexOf("__"))) <= maxVersion) {
                Files.write(dir.resolve(name), resource.getInputStream().readAllBytes());
            }
        }
        return dir;
    }

    private static void deleteQuietly(Path dir) throws Exception {
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
        Files.deleteIfExists(dir);
    }

    @Test
    @DisplayName("V40 → V42: FAQ 테이블이 생기고(0행), 가드 행(id=1, version 0)과 FAQ 관리 메뉴가 최상위 맨 끝에 1개 시드된다")
    void upgradeFromV40() throws Exception {
        freshSchema();
        flyway("40").migrate();
        long topLevelBefore;
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            topLevelBefore = count(st, "SELECT COUNT(*) FROM menu WHERE up_menu_no IS NULL");
        }

        // 최신이 아니라 V42에 고정한다 — 이후 마이그레이션이 최상위 메뉴를 더 추가해도 "FAQ 메뉴가 맨 끝" 단언이 깨지지 않게
        flyway("42").migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM faq")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM faq_category")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM faq_lock")).isEqualTo(1);
            assertThat(count(st, "SELECT version FROM faq_lock WHERE id = 1")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/faq/manage'")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE up_menu_no IS NULL")).isEqualTo(topLevelBefore + 1);
            assertThat(count(st, "SELECT use_yn FROM menu WHERE menu_url = '/admin/faq/manage'")).isEqualTo(1);
            assertThat(count(st, "SELECT (SELECT ord FROM menu WHERE menu_url = '/admin/faq/manage')"
                    + " - (SELECT MAX(ord) FROM menu WHERE up_menu_no IS NULL AND menu_url <> '/admin/faq/manage')")).as("최상위 맨 끝").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("V42 시드는 멱등 — 다시 실행해도 가드 행과 메뉴가 늘지 않고 가드 버전이 되돌아가지 않는다")
    void seedIsIdempotent() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("UPDATE faq_lock SET version = 7 WHERE id = 1");
            for (String statement : seedSql().split(";")) {
                if (!statement.isBlank()) {
                    st.execute(statement);
                }
            }
            assertThat(count(st, "SELECT COUNT(*) FROM faq_lock")).isEqualTo(1);
            assertThat(count(st, "SELECT version FROM faq_lock WHERE id = 1")).as("재실행이 버전을 덮지 않는다").isEqualTo(7);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/faq/manage'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("FAQ 제약: 필수 컬럼 NOT NULL, 카테고리 없는 FAQ와 FAQ가 있는 카테고리 삭제는 FK가 거부, 가드 행 version 기본 0")
    void constraints() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO faq_category (name, ord, use_yn) VALUES ('c', 0, 1)");
            long categoryId = count(st, "SELECT MAX(id) FROM faq_category");
            st.execute("INSERT INTO faq (category_id, question, answer_html, ord, use_yn) VALUES (" + categoryId + ", 'q', '<p>a</p>', 0, 1)");

            assertThatThrownBy(() -> st.execute("INSERT INTO faq (category_id, question, answer_html, ord, use_yn) VALUES (999999, 'q', 'a', 0, 1)"))
                    .as("없는 카테고리").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("DELETE FROM faq_category WHERE id = " + categoryId))
                    .as("FAQ가 있는 카테고리 삭제는 FK(RESTRICT)가 거부").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO faq (category_id, answer_html, ord, use_yn) VALUES (" + categoryId + ", 'a', 0, 1)"))
                    .as("question 필수").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO faq (category_id, question, ord, use_yn) VALUES (" + categoryId + ", 'q', 0, 1)"))
                    .as("answer_html 필수").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO faq_category (ord, use_yn) VALUES (0, 1)")).as("name 필수").isInstanceOf(SQLException.class);

            st.execute("INSERT INTO faq_lock (id) VALUES (2)");
            assertThat(count(st, "SELECT version FROM faq_lock WHERE id = 2")).as("version 기본 0").isZero();
        }
    }

    @Test
    @DisplayName("FAQ 도입 전 앱 롤백 호환: 최신 DB를 V40 이하 파일만 가진 위치로 validate·migrate해도 실패하지 않고, FAQ 데이터와 메뉴 행은 그대로 남는다")
    void rollbackToAppWithoutFaq_startsAgainstLatestDatabase() throws Exception {
        freshSchema();
        flyway(null).migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO faq_category (name, ord, use_yn) VALUES ('c', 0, 1)");
        }

        Path upToV40 = copyMigrationsUpTo(40);
        try {
            Flyway previousApp = Flyway.configure()
                    .dataSource(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword())
                    .locations("filesystem:" + upToV40.toAbsolutePath())
                    .load();
            previousApp.validate();
            previousApp.migrate();

            try (Connection conn = connect(); Statement st = conn.createStatement()) {
                assertThat(count(st, "SELECT COUNT(*) FROM faq_category")).isEqualTo(1);
                // 구 앱의 메뉴 관리는 FAQ 메뉴 행을 URL 그대로 가질 뿐 카탈로그 밖 URL이라 ADMIN 전용으로 취급한다(구 앱 동작)
                assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/faq/manage'")).isEqualTo(1);
            }
        } finally {
            deleteQuietly(upToV40);
        }
    }
}
