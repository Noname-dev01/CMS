package com.cms.admin.session;

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
 * V35(세션 관리 메뉴 시드) — PLAN-session-management.md 쟁점 10. 최상위 맨 끝에 정확히 1개 추가되고(기존 메뉴·순서 불변), 재실행해도 늘지 않으며,
 * 스키마는 바꾸지 않는다(순수 DML). 배너 시드 시험(V34)과 서로 독립이도록 이 시험은 V34→V35만 본다.
 */
class SessionMenuMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "session_v35_test";
    private static final String MENU_URL = "/admin/session/manage";

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
        try (var in = new PathMatchingResourcePatternResolver().getResource("classpath:db/migration/V35__seed_session_menu.sql").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(line -> !line.startsWith("--")).reduce("", (a, b) -> a + "\n" + b);
        }
    }

    @Test
    @DisplayName("V34 → V35: 세션 관리 메뉴가 최상위 맨 끝에 정확히 1개 시드되고 기존 최상위 메뉴 수는 +1뿐이다")
    void upgradeFromV34() throws Exception {
        freshSchema();
        flyway("34").migrate();
        long topLevelBefore;
        long maxOrdBefore;
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            topLevelBefore = count(st, "SELECT COUNT(*) FROM menu WHERE up_menu_no IS NULL");
            maxOrdBefore = count(st, "SELECT MAX(ord) FROM menu WHERE up_menu_no IS NULL");
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '" + MENU_URL + "'")).isZero();
        }

        flyway("35").migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '" + MENU_URL + "'")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE up_menu_no IS NULL")).isEqualTo(topLevelBefore + 1);
            assertThat(count(st, "SELECT use_yn FROM menu WHERE menu_url = '" + MENU_URL + "'")).isEqualTo(1);
            assertThat(count(st, "SELECT ord FROM menu WHERE menu_url = '" + MENU_URL + "'")).as("최상위 맨 끝").isEqualTo(maxOrdBefore + 1);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '" + MENU_URL + "' AND up_menu_no IS NULL"
                    + " AND menu_name = '세션 관리'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("V35 시드는 멱등 — 다시 실행해도 메뉴가 늘지 않는다")
    void seedIsIdempotent() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (String statement : seedSql().split(";")) {
                if (!statement.isBlank()) {
                    st.execute(statement);
                }
            }
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '" + MENU_URL + "'")).isEqualTo(1);
        }
    }
}
