package com.cms.admin.log.migration;

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
 * V12(admin_action_log.target_label) 업그레이드 경로 검증.
 *
 * <p>공용 Testcontainers 구성은 빈 DB에서 V1부터 전체를 적용하므로, 이미 운영 중인 V11 DB가 V12로
 * 올라가는 경로(기존 행 보존·새 컬럼 NULL 기본값)는 별도 스키마에서 {@code target("11")}로 먼저 적용한 뒤
 * 나머지를 적용해 확인한다. 엔티티와 컬럼 정의의 일치(Hibernate {@code ddl-auto: validate})는
 * 전체 마이그레이션을 적용한 채 컨텍스트를 올리는 기존 통합 테스트가 이미 담당한다.
 */
class AdminActionLogTargetLabelMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "upgrade_v12_test";

    @Test
    @DisplayName("V11 DB에 기존 감사 행이 있어도 V12 적용 후 보존되고 target_label은 NULL, 새 행은 라벨 왕복이 된다")
    void upgradeFromV11_preservesRowsAndAddsNullableLabel() throws Exception {
        String host = MARIA_DB.getHost();
        int port = MARIA_DB.getFirstMappedPort();
        String rootUrl = "jdbc:mariadb://" + host + ":" + port + "/";
        String schemaUrl = rootUrl + SCHEMA;

        // 컨테이너 root 비밀번호는 일반 사용자 비밀번호와 같다 — 스키마 생성 권한이 필요해 root로 접속한다
        try (Connection root = DriverManager.getConnection(rootUrl, "root", MARIA_DB.getPassword());
             Statement st = root.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }

        Flyway toV11 = Flyway.configure()
                .dataSource(schemaUrl, "root", MARIA_DB.getPassword())
                .locations("classpath:db/migration")
                .target("11")
                .load();
        toV11.migrate();

        try (Connection conn = DriverManager.getConnection(schemaUrl, "root", MARIA_DB.getPassword());
             Statement st = conn.createStatement()) {
            // V11 시점에는 target_label 컬럼이 없다
            assertThat(columnExists(st, "target_label")).isFalse();
            st.execute("INSERT INTO admin_action_log (action_user_id, request_ip, request_uri, target_id, target_type,"
                    + " action_type, create_at, action_result, request_method)"
                    + " VALUES ('admin01', '127.0.0.1', '/admin/api/menus/1', 1, 'MENU',"
                    + " 'MENU_UPDATE', NOW(6), 'SUCCESS', 'PATCH')");
        }

        Flyway toLatest = Flyway.configure()
                .dataSource(schemaUrl, "root", MARIA_DB.getPassword())
                .locations("classpath:db/migration")
                .load();
        toLatest.migrate();

        try (Connection conn = DriverManager.getConnection(schemaUrl, "root", MARIA_DB.getPassword());
             Statement st = conn.createStatement()) {
            // 컬럼 정의: varchar(500), NULL 허용 (엔티티 @Column(length = 500)과 일치)
            try (ResultSet rs = st.executeQuery(
                    "SELECT data_type, character_maximum_length, is_nullable FROM information_schema.columns"
                            + " WHERE table_schema = '" + SCHEMA + "' AND table_name = 'admin_action_log'"
                            + " AND column_name = 'target_label'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("data_type")).isEqualTo("varchar");
                assertThat(rs.getInt("character_maximum_length")).isEqualTo(500);
                assertThat(rs.getString("is_nullable")).isEqualTo("YES");
            }

            // 기존 행 보존 + target_label NULL
            try (ResultSet rs = st.executeQuery(
                    "SELECT action_type, target_id, target_label FROM admin_action_log WHERE action_user_id = 'admin01'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("action_type")).isEqualTo("MENU_UPDATE");
                assertThat(rs.getLong("target_id")).isEqualTo(1L);
                assertThat(rs.getString("target_label")).isNull();
                assertThat(rs.next()).isFalse();
            }

            // 새 행: 라벨 왕복(한글·500자 상한)
            String label = "메뉴 (/menu)";
            st.execute("INSERT INTO admin_action_log (action_user_id, action_type, create_at, action_result, target_label)"
                    + " VALUES ('admin02', 'MENU_DELETE', NOW(6), 'SUCCESS', '" + label + "')");
            try (ResultSet rs = st.executeQuery(
                    "SELECT target_label FROM admin_action_log WHERE action_user_id = 'admin02'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("target_label")).isEqualTo(label);
            }
        }
    }

    private boolean columnExists(Statement st, String column) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '" + SCHEMA
                        + "' AND table_name = 'admin_action_log' AND column_name = '" + column + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }
}
