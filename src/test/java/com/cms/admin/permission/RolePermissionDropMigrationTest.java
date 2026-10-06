package com.cms.admin.permission;

import com.cms.support.MariaDbContainerSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V22(역할 단위 테이블 {@code role_permission}·{@code permission_role} 제거)의 적용·부분 적용·실패 복구·롤백 호환
 * (PLAN-member-permission.md "PR B 계획" D-B4). 운영 중인 V21 DB를 별도 스키마에서 만든 뒤 V22를 적용한다.
 */
class RolePermissionDropMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "drop_v22_test";

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

    private static long roleTables(Statement st) throws Exception {
        return count(st, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + SCHEMA
                + "' AND table_name IN ('role_permission', 'permission_role')");
    }

    private static long v22(Statement st, int success) throws Exception {
        return count(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '22' AND success = " + success);
    }

    /** V21 DB: V14 시드(MANAGER 공지 4동작)가 든 역할 테이블 + 회원별 권한을 가진 MANAGER 한 명. */
    private static void v21Database() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement st = root.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }
        flyway("21").migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO member (user_id, pwd, user_name, email, user_type, status, password_changed_at, permission_version)"
                    + " VALUES ('m_drop', 'x', 'm_drop', 'm_drop@example.com', 'ROLE_MANAGER', 'ACTIVE', NOW(), 3)");
            st.execute("INSERT INTO member_permission (member_id, feature, action)"
                    + " SELECT id, 'NOTICE', 'READ' FROM member WHERE user_id = 'm_drop'");
            st.execute("INSERT INTO member_permission (member_id, feature, action)"
                    + " SELECT id, 'NOTICE', 'UPDATE' FROM member WHERE user_id = 'm_drop'");
            assertThat(roleTables(st)).as("V21에는 두 테이블이 있다").isEqualTo(2);
            assertThat(count(st, "SELECT COUNT(*) FROM role_permission")).as("V14 시드").isEqualTo(4);
        }
    }

    @Test
    @DisplayName("V21 DB에 V22를 적용하면 두 역할 테이블만 사라지고 회원별 허용 행·권한 버전은 그대로다")
    void v22_dropsRoleTablesOnly() throws Exception {
        v21Database();

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(roleTables(st)).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM member_permission")).isEqualTo(2);
            assertThat(count(st, "SELECT permission_version FROM member WHERE user_id = 'm_drop'")).isEqualTo(3);
            assertThat(v22(st, 1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("첫 DROP만 커밋되고 이력 기록 전에 끊긴 상태(이력 없음)에서도 재기동 migrate가 그대로 성공한다(IF EXISTS)")
    void partiallyAppliedWithoutHistory_rerunSucceeds() throws Exception {
        v21Database();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE role_permission"); // MariaDB DDL 암묵 커밋 — 첫 문장만 반영된 중단을 모사
        }

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(roleTables(st)).isZero();
            assertThat(v22(st, 1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("두 번째 DROP이 실패하면 success=0이 남아 재기동이 거부되고, 원인 제거 → repair → migrate로 복구된다")
    void secondDropFails_recoversByRepair() throws Exception {
        v21Database();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            // 실패 주입: permission_role을 참조하는 FK가 남아 있으면 부모 DROP이 거부된다(첫 DROP은 이미 커밋된 실제 부분 실패)
            st.execute("CREATE TABLE v22_blocker (role varchar(30) NOT NULL, "
                    + "CONSTRAINT fk_v22_blocker FOREIGN KEY (role) REFERENCES permission_role (role))");
        }

        assertThatThrownBy(() -> flyway(null).migrate()).isInstanceOf(FlywayException.class);
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(v22(st, 0)).as("V22 실패 이력").isEqualTo(1);
            assertThat(roleTables(st)).as("자식은 지워지고 부모만 남은 부분 실패").isEqualTo(1);
        }
        assertThatThrownBy(() -> flyway(null).migrate()).as("원인을 없애기 전에도 SQL 실행 전에 거부된다")
                .hasMessageContaining("failed migration to version 22");

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE v22_blocker"); // ① 원인 제거
        }
        flyway(null).repair(); // ② 실패 이력 정리
        flyway(null).migrate(); // ③ 재실행 — 이미 없는 role_permission은 IF EXISTS로 통과

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(roleTables(st)).isZero();
            assertThat(v22(st, 1)).isEqualTo(1);
            assertThat(v22(st, 0)).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM member_permission")).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("이전 앱 롤백 호환: V22가 적용된 DB를 V21 이하 파일만 가진 마이그레이션 위치로 validate·migrate해도 실패하지 않는다(미래 버전은 기본값으로 무시)")
    void rollbackToAppWithoutV22_startsAgainstV22Database() throws Exception {
        v21Database();
        flyway(null).migrate();

        // 이전 앱은 자기 버전 이하 파일만 가진다 — V22 이후 파일을 하나라도 남기면 적용 이력과의 관계가 실제와 달라진다
        Path upToV21 = Files.createTempDirectory("migration-up-to-v21");
        try {
            int copied = 0;
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql")) {
                String name = resource.getFilename();
                if (name != null && Integer.parseInt(name.substring(1, name.indexOf("__"))) <= 21) {
                    Files.write(upToV21.resolve(name), resource.getInputStream().readAllBytes());
                    copied++;
                }
            }
            assertThat(copied).isEqualTo(21);

            Flyway previousApp = Flyway.configure()
                    .dataSource(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword())
                    .locations("filesystem:" + upToV21.toAbsolutePath())
                    .load();
            previousApp.validate();
            previousApp.migrate();

            try (Connection conn = connect(); Statement st = conn.createStatement()) {
                assertThat(roleTables(st)).as("이전 앱은 테이블을 되살리지 않는다 — 매핑하지 않으므로 필요도 없다").isZero();
                assertThat(v22(st, 1)).isEqualTo(1);
            }
        } finally {
            try (var files = Files.list(upToV21)) {
                for (Path file : files.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(upToV21);
        }
    }
}
