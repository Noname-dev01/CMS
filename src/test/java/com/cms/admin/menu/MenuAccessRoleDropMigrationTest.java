package com.cms.admin.menu;

import com.cms.support.MariaDbContainerSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V16 {@code menu.access_role} 제거의 업그레이드 경로(PLAN-menu-permission-management.md §8, 테스트 계획 8). 운영 중인 V15 DB(노출 범위 값이
 * 들어 있는 메뉴)를 별도 스키마에서 만든 뒤 최신을 적용해, 컬럼만 사라지고 나머지 메뉴 데이터는 그대로인지 확인한다. 컨텍스트가 실제로 뜨는지
 * ({@code ddl-auto: validate})는 공용 Testcontainers 구성이 V1부터 V16까지 적용한 DB로 모든 통합 테스트를 기동하는 것으로 이미 고정돼 있다.
 */
class MenuAccessRoleDropMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "upgrade_v16_test";

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

    private static boolean accessRoleColumnExists(Statement st) throws Exception {
        return count(st, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '" + SCHEMA
                + "' AND table_name = 'menu' AND column_name = 'access_role'") > 0;
    }

    private static void freshSchema() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement st = root.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }
    }

    @Test
    @DisplayName("V15 DB(access_role 값이 있는 메뉴)를 올리면 컬럼만 사라지고 메뉴 행·URL·순서·부모는 그대로다")
    void upgradeFromV15_dropsOnlyTheColumn() throws Exception {
        freshSchema();
        flyway("15").migrate();

        long menusBefore;
        String snapshotBefore;
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(accessRoleColumnExists(st)).as("V15까지는 컬럼이 있다").isTrue();
            st.execute("UPDATE menu SET access_role = 'ADMIN' WHERE menu_url = '/admin/menu/manage'");
            st.execute("UPDATE menu SET access_role = 'ALL' WHERE menu_url = '/admin/notice/manage'");
            st.execute("UPDATE menu SET access_role = NULL WHERE menu_url = '/admin/permission/manage'");
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE access_role IS NOT NULL")).isGreaterThan(0);
            menusBefore = count(st, "SELECT COUNT(*) FROM menu");
            snapshotBefore = menuSnapshot(st);
        }

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(accessRoleColumnExists(st)).as("V16 이후 컬럼이 없다").isFalse();
            assertThat(count(st, "SELECT COUNT(*) FROM menu")).as("메뉴 행 수 보존").isEqualTo(menusBefore);
            assertThat(menuSnapshot(st)).as("이름·URL·아이콘·활성·순서·부모 보존").isEqualTo(snapshotBefore);
            assertThat(count(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '16' AND success = 1")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("V16 SQL은 멱등이다 — 컬럼이 이미 없어도(부분 실패 재시도·수동 삭제) 다시 실행해도 실패하지 않는다")
    void dropIsIdempotent() throws Exception {
        freshSchema();
        flyway(null).migrate();

        String sql = new String(getClass().getResourceAsStream("/db/migration/V16__drop_menu_access_role.sql").readAllBytes(),
                StandardCharsets.UTF_8);
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(accessRoleColumnExists(st)).isFalse();
            for (String statement : sql.replaceAll("(?m)^--.*$", "").split(";")) {
                if (!statement.isBlank()) {
                    st.execute(statement); // 예외 없이 통과해야 한다
                }
            }
            assertThat(accessRoleColumnExists(st)).isFalse();
        }
    }


    @Test
    @DisplayName("V16이 실패해 success=0 이력이 남으면 단순 재기동(migrate)은 거부되고, 원인을 없앤 뒤에도 repair 없이는 막힌다 — repair 후 성공한다")
    void failedV16_needsRepairBeforeRetry() throws Exception {
        freshSchema();
        flyway("15").migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            // 실패 유발 장치: access_role을 참조하는 가상 컬럼이 있으면 DROP COLUMN access_role이 실패한다
            st.execute("ALTER TABLE menu ADD COLUMN ar_copy VARCHAR(20) AS (access_role) VIRTUAL");
        }

        assertThatThrownBy(() -> flyway(null).migrate()).as("V16 실행 실패").isInstanceOf(FlywayException.class);
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(accessRoleColumnExists(st)).as("실패했으니 컬럼이 남아 있다").isTrue();
            assertThat(count(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '16' AND success = 0"))
                    .as("실패 이력이 남는다").isEqualTo(1);
        }

        // 단순 재시도는 SQL을 실행하기 전에 실패 이력 때문에 거부된다 — IF EXISTS는 이 경로를 구하지 못한다
        assertThatThrownBy(() -> flyway(null).migrate()).hasMessageContaining("failed migration to version 16");

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE menu DROP COLUMN ar_copy"); // 원인 제거
        }
        assertThatThrownBy(() -> flyway(null).migrate()).as("원인을 없애도 repair 없이는 막힌다")
                .hasMessageContaining("failed migration to version 16");

        flyway(null).repair();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(accessRoleColumnExists(st)).isFalse();
            assertThat(count(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '16' AND success = 1")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '16' AND success = 0")).isZero();
        }
    }
    /** 메뉴 데이터 지문 — access_role을 뺀 나머지 컬럼을 menu_no 순서로 이어 붙인다. */
    private static String menuSnapshot(Statement st) throws Exception {
        StringBuilder snapshot = new StringBuilder();
        try (ResultSet rs = st.executeQuery(
                "SELECT menu_no, menu_name, menu_url, menu_icon, use_yn, ord, up_menu_no FROM menu ORDER BY menu_no")) {
            while (rs.next()) {
                for (int i = 1; i <= 7; i++) {
                    snapshot.append(rs.getString(i)).append('|');
                }
                snapshot.append('\n');
            }
        }
        return snapshot.toString();
    }
}
