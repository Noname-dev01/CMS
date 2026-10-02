package com.cms.admin.permission;

import com.cms.support.MariaDbContainerSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V15 권한 관리 메뉴 시드(PLAN-permission-management-pr3.md §5-4·§7-1): 별도 스키마에서 V14까지 적용한 뒤 최신을 적용해 업그레이드 경로를 확인한다.
 * 같은 테이블을 서브쿼리로 읽는 {@code INSERT … SELECT}가 실제 MariaDB에서 동작하는지, 멱등성, ord 경계(빈 테이블·INT 상한)를 시험한다.
 */
class PermissionMenuMigrationTest extends MariaDbContainerSupport {

    private static final String URL_PATH = "/admin/permission/manage";

    private static String rootUrl() {
        return "jdbc:mariadb://" + MARIA_DB.getHost() + ":" + MARIA_DB.getFirstMappedPort() + "/";
    }

    private static Flyway flyway(String schema, String target) {
        var configuration = Flyway.configure()
                .dataSource(rootUrl() + schema, "root", MARIA_DB.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static void freshSchema(String schema) throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement st = root.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + schema);
            st.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
        }
    }

    private static Connection connect(String schema) throws Exception {
        return DriverManager.getConnection(rootUrl() + schema, "root", MARIA_DB.getPassword());
    }

    private static long count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void runV15(Statement st) throws Exception {
        String sql = new String(PermissionMenuMigrationTest.class.getResourceAsStream("/db/migration/V15__seed_permission_menu.sql")
                .readAllBytes(), StandardCharsets.UTF_8);
        for (String statement : sql.replaceAll("(?m)^--.*$", "").split(";")) {
            if (!statement.isBlank()) {
                st(st, statement);
            }
        }
    }

    private static void st(Statement st, String sql) throws Exception {
        st.execute(sql);
    }

    @Test
    @DisplayName("V14 DB(V3·V9 시드 메뉴)를 올리면 권한 관리 메뉴 1행이 최상위 맨 끝에 생긴다 — 이름·아이콘·활성·access_role/날짜 NULL")
    void upgradeFromV14_appendsPermissionMenu() throws Exception {
        String schema = "upgrade_v15_test";
        freshSchema(schema);
        flyway(schema, "14").migrate();
        long maxTopOrd;
        try (Connection conn = connect(schema); Statement st = conn.createStatement()) {
            maxTopOrd = count(st, "SELECT MAX(ord) FROM menu WHERE up_menu_no IS NULL");
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '" + URL_PATH + "'")).isZero();
        }

        flyway(schema, null).migrate();

        try (Connection conn = connect(schema); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT menu_name, menu_icon, use_yn, ord, up_menu_no, access_role, create_date, update_date "
                     + "FROM menu WHERE menu_url = '" + URL_PATH + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("menu_name")).isEqualTo("권한 관리");
            assertThat(rs.getString("menu_icon")).isEqualTo("fas fa-fw fa-user-lock");
            assertThat(rs.getInt("use_yn")).isEqualTo(1);
            assertThat(rs.getLong("ord")).isEqualTo(maxTopOrd + 1);
            assertThat(rs.getObject("up_menu_no")).isNull();
            assertThat(rs.getObject("access_role")).isNull();
            assertThat(rs.getObject("create_date")).isNull();
            assertThat(rs.getObject("update_date")).isNull();
            assertThat(rs.next()).as("한 행만").isFalse();
        }
    }

    @Test
    @DisplayName("멱등: SQL을 다시 실행해도 중복되지 않고, 같은 URL 메뉴가 이미 있으면 삽입하지 않는다")
    void idempotent_andSkipsWhenUrlExists() throws Exception {
        String schema = "upgrade_v15_idem_test";
        freshSchema(schema);
        flyway(schema, null).migrate();

        try (Connection conn = connect(schema); Statement st = conn.createStatement()) {
            runV15(st);
            runV15(st);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '" + URL_PATH + "'")).isEqualTo(1);

            st.execute("DELETE FROM menu WHERE menu_url = '" + URL_PATH + "'");
            st.execute("INSERT INTO menu (menu_name, menu_url, use_yn, ord) VALUES ('이미 있는 메뉴', '" + URL_PATH + "', 1, 7)");
            runV15(st);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '" + URL_PATH + "'")).isEqualTo(1);
            assertThat(count(st, "SELECT ord FROM menu WHERE menu_url = '" + URL_PATH + "'")).isEqualTo(7);
        }
    }

    @Test
    @DisplayName("최상위 메뉴가 없으면 ord = 0(COALESCE)")
    void noTopLevelMenus_ordZero() throws Exception {
        String schema = "upgrade_v15_empty_test";
        freshSchema(schema);
        flyway(schema, null).migrate();

        try (Connection conn = connect(schema); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM menu");
            runV15(st);
            assertThat(count(st, "SELECT ord FROM menu WHERE menu_url = '" + URL_PATH + "'")).isZero();
        }
    }

    @Test
    @DisplayName("최상위 MAX(ord)가 INT 상한이어도 마이그레이션이 성공하고 새 행은 (ord, menu_no) 표시 순서상 맨 끝이다")
    void maxOrdAtIntLimit_succeedsAndStaysLast() throws Exception {
        String schema = "upgrade_v15_intmax_test";
        freshSchema(schema);
        flyway(schema, "14").migrate();
        try (Connection conn = connect(schema); Statement st = conn.createStatement()) {
            st.execute("UPDATE menu SET ord = 2147483647 WHERE menu_url = '/admin/notice/manage'");
        }

        flyway(schema, null).migrate();

        try (Connection conn = connect(schema); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT menu_url FROM menu WHERE up_menu_no IS NULL ORDER BY ord ASC, menu_no ASC")) {
            String last = null;
            while (rs.next()) {
                last = rs.getString(1);
            }
            assertThat(last).isEqualTo(URL_PATH);
        }
    }
}
