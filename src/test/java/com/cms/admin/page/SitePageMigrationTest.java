package com.cms.admin.page;

import com.cms.support.MariaDbContainerSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
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
 * V37(정적 페이지·개정 테이블)·V38(메뉴 시드) — PLAN-static-page.md 쟁점 2·13·R1-5. 테이블·제약, 메뉴 시드의 존재와 멱등, 정적 페이지 도입 전 앱
 * (V36 이하 파일만) 롤백 기동 호환, 그리고 <b>V37 부분 적용 실패 복구</b>(DDL 2문이 문마다 암묵 커밋이라 두 번째 문이 실패하면 첫 테이블이 남는다 —
 * {@code docs/migration-guide.md} "V37~V38")를 고정한다.
 */
class SitePageMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "site_page_v37_test";

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

    private static String sqlOf(String file) throws Exception {
        try (var in = new PathMatchingResourcePatternResolver().getResource("classpath:db/migration/" + file).getInputStream()) {
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
    @DisplayName("V36 → V38: 두 테이블이 생기고 정적 페이지 관리 메뉴가 최상위 맨 끝에 1개 시드된다(사용 중)")
    void upgradeFromV36() throws Exception {
        freshSchema();
        flyway("36").migrate();
        long topLevelBefore;
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            topLevelBefore = count(st, "SELECT COUNT(*) FROM menu WHERE up_menu_no IS NULL");
        }

        flyway("38").migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM site_page")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM site_page_revision")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/site-page/manage'")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE up_menu_no IS NULL")).isEqualTo(topLevelBefore + 1);
            assertThat(count(st, "SELECT use_yn FROM menu WHERE menu_url = '/admin/site-page/manage'")).isEqualTo(1);
            assertThat(count(st, "SELECT (SELECT ord FROM menu WHERE menu_url = '/admin/site-page/manage')"
                    + " - (SELECT MAX(ord) FROM menu WHERE up_menu_no IS NULL AND menu_url <> '/admin/site-page/manage')")).as("최상위 맨 끝").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("V38 시드는 멱등 — 다시 실행해도 메뉴가 늘지 않는다")
    void seedIsIdempotent() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (String statement : sqlOf("V38__seed_site_page_menu.sql").split(";")) {
                if (!statement.isBlank()) {
                    st.execute(statement);
                }
            }
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/site-page/manage'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("제약: 슬러그 유일, 개정 복합 PK 중복 거부, 개정은 존재하는 페이지만 가리킨다, 페이지 삭제 전 개정이 있으면 FK(RESTRICT)로 막힌다")
    void constraints() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn) VALUES ('terms', 1, 1, 1, 0)");
            long pageId = count(st, "SELECT id FROM site_page WHERE slug = 'terms'");

            assertThatThrownBy(() -> st.execute("INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn) VALUES ('terms', 1, 1, 1, 0)"))
                    .as("슬러그 유일").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO site_page (slug, version, use_yn, footer_yn) VALUES ('no-rev', 1, 1, 0)"))
                    .as("current_revision_no 필수").isInstanceOf(SQLException.class);

            st.execute("INSERT INTO site_page_revision (page_id, revision_no, title, content, create_date) VALUES (" + pageId + ", 1, 't', '<p>x</p>', NOW())");
            assertThatThrownBy(() -> st.execute("INSERT INTO site_page_revision (page_id, revision_no, title, content, create_date) VALUES (" + pageId + ", 1, 'dup', '<p>y</p>', NOW())"))
                    .as("(page_id, revision_no) 중복 거부").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO site_page_revision (page_id, revision_no, title, content, create_date) VALUES (999999, 1, 't', '<p>x</p>', NOW())"))
                    .as("없는 페이지를 가리키는 개정").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO site_page_revision (page_id, revision_no, content, create_date) VALUES (" + pageId + ", 2, '<p>x</p>', NOW())"))
                    .as("title 필수").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("DELETE FROM site_page WHERE id = " + pageId))
                    .as("개정이 남아 있으면 페이지 삭제는 FK로 거부(앱은 개정을 먼저 지운다)").isInstanceOf(SQLException.class);

            // 슬러그는 utf8mb4_general_ci라 대소문자를 구분하지 않는다 — 앱이 소문자만 허용하므로 영향 없음을 문서화한다
            assertThatThrownBy(() -> st.execute("INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn) VALUES ('TERMS', 1, 1, 1, 0)"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("정적 페이지 도입 전 앱 롤백 호환: 최신 DB를 V36 이하 파일만 가진 위치로 validate·migrate해도 실패하지 않고, 페이지 데이터와 메뉴 행은 그대로 남는다")
    void rollbackToAppWithoutSitePage_startsAgainstLatestDatabase() throws Exception {
        freshSchema();
        flyway(null).migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn) VALUES ('terms', 1, 1, 1, 0)");
            st.execute("INSERT INTO site_page_revision (page_id, revision_no, title, content, create_date) SELECT id, 1, 't', '<p>x</p>', NOW() FROM site_page");
        }

        Path upToV36 = copyMigrationsUpTo(36);
        try {
            Flyway previousApp = Flyway.configure()
                    .dataSource(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword())
                    .locations("filesystem:" + upToV36.toAbsolutePath())
                    .load();
            previousApp.validate();
            previousApp.migrate();

            try (Connection conn = connect(); Statement st = conn.createStatement()) {
                assertThat(count(st, "SELECT COUNT(*) FROM site_page")).isEqualTo(1);
                assertThat(count(st, "SELECT COUNT(*) FROM site_page_revision")).isEqualTo(1);
                assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/site-page/manage'")).isEqualTo(1);
            }
        } finally {
            deleteQuietly(upToV36);
        }
    }

    @Test
    @DisplayName("V37 부분 적용 실패 복구: 첫 테이블만 만들어진 채 이력이 없으면 단순 재실행은 실패하고, 문서 절차(잔여 객체 DROP 후 repair)로 복구된다. 성공 이력이 있는 객체는 건드리지 않는다")
    void partialFailureRecovery() throws Exception {
        freshSchema();
        flyway("36").migrate();
        // V37 첫 DDL만 적용된 상태 + 실패 이력(success=0)을 재현한다 — 두 번째 DDL 실패(예: 잠금·연결 중단)로 남는 모양
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            String ddl = sqlOf("V37__create_site_page.sql").split(";")[0];
            st.execute(ddl);
            st.execute("INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) "
                    + "SELECT MAX(installed_rank) + 1, '37', 'create site page', 'SQL', 'V37__create_site_page.sql', 0, 'test', 1, 0 FROM flyway_schema_history");
        }

        assertThatThrownBy(() -> flyway(null).migrate()).as("실패 이력이 있으면 단순 재기동은 복구되지 않는다").isInstanceOf(FlywayException.class);

        // 문서 절차: 이력이 없거나 success=0인 버전의 잔여 객체만 자식→부모 순으로 DROP → flyway repair → 재기동
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '37' AND success = 1")).isZero();
            st.execute("DROP TABLE IF EXISTS site_page_revision");
            st.execute("DROP TABLE IF EXISTS site_page");
        }
        flyway(null).repair();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '37' AND success = 1")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + SCHEMA + "' AND table_name IN ('site_page', 'site_page_revision')"))
                    .isEqualTo(2);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/site-page/manage'")).isEqualTo(1);
        }
    }
}
