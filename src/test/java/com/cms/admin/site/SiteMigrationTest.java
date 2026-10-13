package com.cms.admin.site;

import com.cms.support.MariaDbContainerSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V50~V52(site 테이블·시드·menu.site_id) — PLAN-site-menu.md D1. 기존 메뉴 행이 전부 관리자 사이트(id=1)로 채워지고, 시드는 고정 ID이며
 * 재실행해도 늘지 않고, 이전 앱처럼 site_id 없이 INSERT해도 관리자 사이트로 들어가며(롤백 호환), FK가 사이트 삭제·없는 사이트 참조를 막는다.
 */
class SiteMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "site_v50_test";

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

    private static String sql(String file) throws Exception {
        try (var in = new PathMatchingResourcePatternResolver().getResource("classpath:db/migration/" + file).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(line -> !line.startsWith("--")).reduce("", (a, b) -> a + "\n" + b);
        }
    }

    private static String seedSql() throws Exception {
        try (var in = new PathMatchingResourcePatternResolver().getResource("classpath:db/migration/V51__seed_site.sql").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(line -> !line.startsWith("--")).reduce("", (a, b) -> a + "\n" + b);
        }
    }

    @Test
    @DisplayName("V49 → V52: 기존 메뉴 행은 전부 site_id=1(관리자 사이트)이 되고 시드 사이트 2행이 고정 ID로 생긴다")
    void upgradeFromV49() throws Exception {
        freshSchema();
        flyway("49").migrate();
        long menusBefore;
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            menusBefore = count(st, "SELECT COUNT(*) FROM menu");
            assertThat(menusBefore).as("기존 시드 메뉴가 있어야 이 시험이 의미가 있다").isPositive();
        }

        flyway("52").migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM menu")).isEqualTo(menusBefore);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE site_id = 1")).as("전부 관리자 사이트").isEqualTo(menusBefore);
            assertThat(count(st, "SELECT COUNT(*) FROM site")).isEqualTo(2);
            assertThat(count(st, "SELECT COUNT(*) FROM site WHERE id = 1 AND site_key = 'admin' AND site_type = 'ADMIN'"
                    + " AND allow_external_link = 0 AND use_yn = 1")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM site WHERE id = 2 AND site_key = 'main' AND site_type = 'PUBLIC'"
                    + " AND allow_external_link = 1 AND use_yn = 1")).isEqualTo(1);
            assertThat(Site.ADMIN_SITE_ID).isEqualTo(1L);
            assertThat(Site.DEFAULT_PUBLIC_SITE_ID).isEqualTo(2L);
        }
    }

    @Test
    @DisplayName("V51 시드는 멱등 — 다시 실행해도 사이트가 늘지 않는다")
    void seedIsIdempotent() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (String statement : seedSql().split(";")) {
                if (!statement.isBlank()) {
                    st.execute(statement);
                }
            }
            assertThat(count(st, "SELECT COUNT(*) FROM site")).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("롤백 호환: site_id 없이 INSERT(이전 앱)하면 관리자 사이트로 들어가고, 새 사이트의 AUTO_INCREMENT는 3부터다")
    void insertWithoutSiteIdDefaultsToAdminSite() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO menu (menu_name, use_yn, ord) VALUES ('이전 앱 메뉴', 1, 99)");
            assertThat(count(st, "SELECT site_id FROM menu WHERE menu_name = '이전 앱 메뉴'")).isEqualTo(1);

            st.execute("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn) VALUES ('news', '소식', 'PUBLIC', 0, 1)");
            assertThat(count(st, "SELECT id FROM site WHERE site_key = 'news'")).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("제약: 사이트 키는 유일하고, 메뉴가 있는 사이트는 지울 수 없으며(RESTRICT), 없는 사이트를 가리키는 메뉴는 만들 수 없다")
    void constraints() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThatThrownBy(() -> st.execute(
                    "INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn) VALUES ('main', '중복', 'PUBLIC', 0, 1)"))
                    .isInstanceOf(SQLException.class);

            assertThatThrownBy(() -> st.execute("DELETE FROM site WHERE id = 1"))
                    .as("관리자 사이트에는 시드 메뉴가 있어 지울 수 없다").isInstanceOf(SQLException.class);

            assertThatThrownBy(() -> st.execute("INSERT INTO menu (menu_name, use_yn, ord, site_id) VALUES ('고아', 1, 0, 999)"))
                    .isInstanceOf(SQLException.class);

            // 메뉴가 없는 사이트는 지울 수 있다
            st.execute("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn) VALUES ('tmp', '임시', 'PUBLIC', 0, 1)");
            st.execute("DELETE FROM site WHERE site_key = 'tmp'");
            assertThat(count(st, "SELECT COUNT(*) FROM site WHERE site_key = 'tmp'")).isZero();
        }
    }

    @Test
    @DisplayName("V52는 멱등 — 컬럼·FK가 이미 있는 상태(flyway repair 뒤 재실행)에서 다시 실행해도 실패하지 않고 제약이 하나뿐이다")
    void addMenuSiteIdIsIdempotent() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute(sql("V52__add_menu_site_id.sql"));
            st.execute(sql("V52__add_menu_site_id.sql"));
            assertThat(count(st, "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = '" + SCHEMA
                    + "' AND TABLE_NAME = 'menu' AND CONSTRAINT_NAME = 'fk_menu_site'")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '" + SCHEMA
                    + "' AND TABLE_NAME = 'menu' AND COLUMN_NAME = 'site_id'")).isEqualTo(1);
        }
    }
}
