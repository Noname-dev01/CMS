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
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V53·V54(site.default_yn, 기본 공개 사이트 지정) — PLAN-site-menu.md PR ② P2-5. 기본 사이트는 정확히 시드 {@code main}(id=2) 하나이고,
 * V54를 다시 실행해도 늘지 않으며, 이전 앱처럼 default_yn 없이 INSERT해도 기본이 아닌 행으로 들어간다(롤백 호환).
 */
class SiteDefaultMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "site_v53_test";

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

    private static String statements(String file) throws Exception {
        try (var in = new PathMatchingResourcePatternResolver().getResource("classpath:db/migration/" + file).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.startsWith("--")).reduce("", (a, b) -> a + "\n" + b).trim();
        }
    }

    @Test
    @DisplayName("V52 → V54: default_yn 컬럼이 생기고 기본 사이트는 시드 main(id=2) 정확히 하나다")
    void upgradeFromV52() throws Exception {
        freshSchema();
        flyway("52").migrate();
        flyway(null).migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM site WHERE default_yn = 1")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM site WHERE default_yn = 1 AND id = 2 AND site_key = 'main' AND site_type = 'PUBLIC' AND use_yn = 1"))
                    .as("기본 사이트는 활성 공개 시드 main").isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM site WHERE id = 1 AND default_yn = 0")).as("관리자 사이트는 기본이 아니다").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("V54를 다시 실행해도 기본 사이트가 늘지 않는다 — 다른 사이트가 이미 기본이면 건드리지 않는다")
    void seedIsIdempotentAndRespectsExistingDefault() throws Exception {
        freshSchema();
        flyway(null).migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute(statements("V54__seed_default_site.sql"));
            assertThat(count(st, "SELECT COUNT(*) FROM site WHERE default_yn = 1")).isEqualTo(1);

            // 기본 사이트를 다른 사이트로 옮긴 뒤 V54를 다시 실행해도 main을 되살리지 않는다.
            st.execute("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, default_yn) VALUES ('news', '소식', 'PUBLIC', 0, 1, 0)");
            st.execute("UPDATE site SET default_yn = 0 WHERE id = 2");
            st.execute("UPDATE site SET default_yn = 1 WHERE site_key = 'news'");
            st.execute(statements("V54__seed_default_site.sql"));
            assertThat(count(st, "SELECT COUNT(*) FROM site WHERE default_yn = 1")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM site WHERE default_yn = 1 AND site_key = 'news'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("이전 앱처럼 default_yn 없이 INSERT해도 기본이 아닌 행으로 들어간다(롤백 호환)")
    void insertWithoutDefaultColumn_isNotDefault() throws Exception {
        freshSchema();
        flyway(null).migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn) VALUES ('legacy', '이전 앱', 'PUBLIC', 0, 1)");
            assertThat(count(st, "SELECT COUNT(*) FROM site WHERE site_key = 'legacy' AND default_yn = 0")).isEqualTo(1);
        }
    }
}
