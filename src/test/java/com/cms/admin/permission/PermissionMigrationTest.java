package com.cms.admin.permission;

import com.cms.support.MariaDbContainerSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V13(권한 테이블)·V14(MANAGER 공지 시드)·V15(권한 관리 메뉴 시드, 상세는 PermissionMenuMigrationTest) 업그레이드 경로(PLAN-menu-permission-management.md §4). 공용 Testcontainers 구성은 빈 DB에서
 * V1부터 전부 적용하므로, 이미 운영 중인 V12 DB가 올라가는 경로는 별도 스키마에서 {@code target("12")}로 먼저 적용한 뒤 나머지를 적용해 확인한다.
 */
class PermissionMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "upgrade_v14_test";

    private static Flyway flyway(String url, String target) {
        var configuration = Flyway.configure()
                .dataSource(url, "root", MARIA_DB.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    @Test
    @DisplayName("V12 DB(메뉴·감사 로그 존재)를 올리면 MANAGER 공지 4동작 시드·버전 0·FK가 생기고, 기존 데이터는 그대로다")
    void upgradeFromV12_seedsManagerNoticePermissions() throws Exception {
        String rootUrl = "jdbc:mariadb://" + MARIA_DB.getHost() + ":" + MARIA_DB.getFirstMappedPort() + "/";
        String schemaUrl = rootUrl + SCHEMA;

        try (Connection root = DriverManager.getConnection(rootUrl, "root", MARIA_DB.getPassword());
             Statement st = root.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }

        flyway(schemaUrl, "12").migrate();

        long menusBefore;
        try (Connection conn = DriverManager.getConnection(schemaUrl, "root", MARIA_DB.getPassword());
             Statement st = conn.createStatement()) {
            assertThat(tableExists(st, "role_permission")).as("V12에는 권한 테이블이 없다").isFalse();
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM menu")) {
                rs.next();
                menusBefore = rs.getLong(1);
            }
        }

        flyway(schemaUrl, null).migrate();

        try (Connection conn = DriverManager.getConnection(schemaUrl, "root", MARIA_DB.getPassword());
             Statement st = conn.createStatement()) {
            assertThat(tableExists(st, "permission_role")).isTrue();
            assertThat(tableExists(st, "role_permission")).isTrue();

            try (ResultSet rs = st.executeQuery("SELECT version, update_date FROM permission_role WHERE role = 'ROLE_MANAGER'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("version")).isZero();
                assertThat(rs.getObject("update_date")).isNull();
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT feature, action FROM role_permission WHERE role = 'ROLE_MANAGER' ORDER BY action")) {
                StringBuilder rows = new StringBuilder();
                while (rs.next()) {
                    rows.append(rs.getString("feature")).append(':').append(rs.getString("action")).append(' ');
                }
                assertThat(rows.toString().trim().split(" "))
                        .containsExactlyInAnyOrder("NOTICE:CREATE", "NOTICE:DELETE", "NOTICE:READ", "NOTICE:UPDATE");
            }
            // FK: permission_role에 없는 역할의 허용 행은 넣을 수 없다
            try {
                st.execute("INSERT INTO role_permission (role, feature, action) VALUES ('ROLE_NOBODY', 'NOTICE', 'READ')");
                throw new AssertionError("permission_role에 없는 역할 행이 FK 없이 들어갔다");
            } catch (java.sql.SQLException expected) {
                // FK 위반 — 정상
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM menu")) {
                rs.next();
                // V15가 권한 관리 메뉴를 한 행 추가한다 — 기존 행은 그대로이고 늘어난 것은 그 메뉴 하나뿐이어야 한다
                assertThat(rs.getLong(1)).as("기존 메뉴 데이터 보존 + 권한 관리 메뉴 1행(V15)").isEqualTo(menusBefore + 1);
            }
        }
    }

    @Test
    @DisplayName("V14는 일회성 초기화다 — Flyway는 성공한 V14를 다시 실행하지 않지만, SQL을 수동 재실행하면 회수한 행이 되살아난다(복구 수단으로 쓰지 않는다)")
    void seedIsOneTimeInitialization() throws Exception {
        String rootUrl = "jdbc:mariadb://" + MARIA_DB.getHost() + ":" + MARIA_DB.getFirstMappedPort() + "/";
        String schemaUrl = rootUrl + SCHEMA + "_rerun";

        try (Connection root = DriverManager.getConnection(rootUrl, "root", MARIA_DB.getPassword());
             Statement st = root.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA + "_rerun");
            st.execute("CREATE DATABASE " + SCHEMA + "_rerun CHARACTER SET utf8mb4");
        }
        flyway(schemaUrl, null).migrate();

        try (Connection conn = DriverManager.getConnection(schemaUrl, "root", MARIA_DB.getPassword());
             Statement st = conn.createStatement()) {
            // 정상 Flyway 경로: 같은 마이그레이션을 다시 migrate해도 V14는 재실행되지 않는다(이력에 성공으로 남음)
            st.execute("DELETE FROM role_permission WHERE action = 'DELETE'");
            flyway(schemaUrl, null).migrate();
            assertThat(count(st, "SELECT COUNT(*) FROM role_permission WHERE action = 'DELETE'"))
                    .as("Flyway는 성공한 V14를 다시 실행하지 않으므로 회수한 행이 되살아나지 않는다").isZero();

            // 알려진 동작 고정(경고): V14 SQL을 수동으로 다시 실행하면 WHERE NOT EXISTS가 회수한 행을 다시 만든다.
            // 그래서 누락·삭제된 권한의 복구는 권한관리 화면/API로만 하고, 운영자가 V14 재실행을 복구 수단으로 쓰지 않게 문서화한다.
            String seedSql = new String(getClass().getResourceAsStream("/db/migration/V14__seed_manager_notice_permissions.sql")
                    .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (String statement : seedSql.replaceAll("(?m)^--.*$", "").split(";")) {
                if (!statement.isBlank()) {
                    st.execute(statement);
                }
            }
            assertThat(count(st, "SELECT COUNT(*) FROM role_permission WHERE action = 'DELETE'"))
                    .as("수동 재실행은 회수한 DELETE 행을 되살린다 — 복구 수단으로 쓰지 않는다").isEqualTo(1L);
        }
    }

    private static boolean tableExists(Statement st, String table) throws Exception {
        return count(st, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + SCHEMA
                + "' AND table_name = '" + table + "'") > 0;
    }

    private static long count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
