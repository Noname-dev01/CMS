package com.cms.admin.permission;

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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V17~V19(회원별 권한 전환)의 업그레이드·복구 경로(PLAN-member-permission.md §7-1 ①·⑫·⑬). 운영 중인 V16 DB(역할 단위 허용 행 + 여러 상태의 회원)를
 * 별도 스키마에서 만든 뒤 최신을 적용한다. 컨텍스트가 실제로 뜨는지({@code ddl-auto: validate})는 공용 Testcontainers 구성이 V1부터 최신까지 적용한
 * DB로 모든 통합 테스트를 기동하는 것으로 이미 고정돼 있다. 최신까지 올리면 V22가 역할 단위 테이블을 지우므로, {@code role_permission}을 다시 읽는
 * 시험(V19 재실행)은 V21까지만 적용한다 — V22 자체는 {@code RolePermissionDropMigrationTest}.
 */
class MemberPermissionMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "upgrade_v19_test";

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
        return new String(MemberPermissionMigrationTest.class.getResourceAsStream("/db/migration/" + file).readAllBytes(),
                StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "").trim();
    }

    private static boolean columnExists(Statement st) throws Exception {
        return count(st, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '" + SCHEMA
                + "' AND table_name = 'member' AND column_name = 'permission_version'") > 0;
    }

    private static boolean tableExists(Statement st) throws Exception {
        return count(st, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + SCHEMA
                + "' AND table_name = 'member_permission'") > 0;
    }

    private static long success(Statement st, String version, int success) throws Exception {
        return count(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '" + version + "' AND success = " + success);
    }

    private static void insertMember(Statement st, String userId, String role, String status) throws Exception {
        st.execute("INSERT INTO member (user_id, pwd, user_name, email, user_type, status, password_changed_at) VALUES ('"
                + userId + "', 'x', '" + userId + "', '" + userId + "@example.com', '" + role + "', '" + status + "', NOW())");
    }

    private static long memberId(Statement st, String userId) throws Exception {
        return count(st, "SELECT id FROM member WHERE user_id = '" + userId + "'");
    }

    /** V16 DB: 여러 상태·역할의 회원을 넣고, 역할 허용 행을 시험이 정한 대로 바꾼다. */
    private static void v16Database(String... roleRowsSql) throws Exception {
        freshSchema();
        flyway("16").migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            insertMember(st, "m_active", "ROLE_MANAGER", "ACTIVE");
            insertMember(st, "m_locked", "ROLE_MANAGER", "LOCKED");
            insertMember(st, "m_pwexp", "ROLE_MANAGER", "PASSWORD_EXPIRED");
            insertMember(st, "m_deleted", "ROLE_MANAGER", "DELETED");
            insertMember(st, "a_admin", "ROLE_ADMIN", "ACTIVE");
            insertMember(st, "u_user", "ROLE_USER", "ACTIVE");
            if (roleRowsSql.length > 0) {
                st.execute("DELETE FROM role_permission");
                for (String sql : roleRowsSql) {
                    st.execute(sql);
                }
            }
        }
    }

    private static String roleRow(String feature, String action) {
        return "INSERT INTO role_permission (role, feature, action) VALUES ('ROLE_MANAGER', '" + feature + "', '" + action + "')";
    }

    private static List<String> copiedActions(Statement st, String userId) throws Exception {
        List<String> actions = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT feature, action FROM member_permission WHERE member_id = " + memberId(st, userId)
                + " ORDER BY action")) {
            while (rs.next()) {
                actions.add(rs.getString(1) + ":" + rs.getString(2));
            }
        }
        return actions;
    }

    // ── ① V16 → V19 업그레이드 ─────────────────────────────

    @Test
    @DisplayName("V16 DB를 최신까지 올리면 활성·잠금·비밀번호 만료 MANAGER 전원에게 공지 4동작이 복사되고 삭제된 MANAGER·ADMIN·USER는 제외되며 버전은 0이고, 복사가 끝난 뒤 역할 단위 테이블은 지워진다")
    void upgrade_copiesRoleGrantsToActiveManagersOnly() throws Exception {
        v16Database();

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (String copied : List.of("m_active", "m_locked", "m_pwexp")) {
                assertThat(copiedActions(st, copied)).as(copied)
                        .containsExactlyInAnyOrder("NOTICE:CREATE", "NOTICE:DELETE", "NOTICE:READ", "NOTICE:UPDATE");
            }
            for (String skipped : List.of("m_deleted", "a_admin", "u_user")) {
                assertThat(copiedActions(st, skipped)).as(skipped).isEmpty();
            }
            assertThat(count(st, "SELECT COUNT(*) FROM member WHERE permission_version <> 0")).as("신규 컬럼 기본값 0").isZero();
            for (String version : List.of("17", "18", "19")) {
                assertThat(success(st, version, 1)).as("V" + version).isEqualTo(1);
            }
            // V19 복사가 V22 DROP보다 먼저 실행되므로 복사된 회원별 행은 위에서 확인한 대로 남는다
            assertThat(count(st, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + SCHEMA
                    + "' AND table_name IN ('role_permission', 'permission_role')")).as("V22가 역할 단위 테이블을 지운다").isZero();
        }
    }

    @Test
    @DisplayName("NOTICE 조회(READ) 행이 없으면(쓰기만 있는 행) 아무것도 복사하지 않는다 — 판정기가 거부하던 것을 열지 않는다")
    void upgrade_withoutReadRow_copiesNothing() throws Exception {
        v16Database(roleRow("NOTICE", "CREATE"), roleRow("NOTICE", "DELETE"));

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM member_permission")).isZero();
        }
    }

    @Test
    @DisplayName("모르는 기능·동작, 위임 불가·상시 허용 기능 행은 복사하지 않고 NOTICE 유효 행만 복사한다")
    void upgrade_ignoresUnknownAndNonDelegableRows() throws Exception {
        v16Database(roleRow("NOTICE", "READ"), roleRow("NOTICE", "EXPORT"), roleRow("GONE_FEATURE", "READ"),
                roleRow("MENU", "READ"), roleRow("DASHBOARD", "READ"));

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(copiedActions(st, "m_active")).containsExactly("NOTICE:READ");
            assertThat(count(st, "SELECT COUNT(*) FROM member_permission")).as("활성·잠금·만료 3명 × 1행").isEqualTo(3);
        }
    }

    @Test
    @DisplayName("대소문자 변형 행(read)은 복사하지 않는다 — 판정기가 무시하던 행이다")
    void upgrade_skipsVariantRows() throws Exception {
        v16Database(roleRow("NOTICE", "read"));

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM member_permission")).isZero();
        }
    }

    @Test
    @DisplayName("V19를 다시 실행해도 중복 행이 생기지 않는다(부분 실패 재시도 안전 — role_permission이 남아 있는 V22 이전 DB)")
    void v19_isRetrySafe() throws Exception {
        v16Database();
        flyway("21").migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            long before = count(st, "SELECT COUNT(*) FROM member_permission");
            st.execute(sqlOf("V19__copy_manager_permissions_to_members.sql").replace(";", ""));
            assertThat(count(st, "SELECT COUNT(*) FROM member_permission")).isEqualTo(before);
        }
    }

    // ── ⑫ 롤백 후 재배포 정리 SQL ───────────────────────────

    @Test
    @DisplayName("재배포 정리 트랜잭션: 허용 행을 모두 지우고 허용 행이 0개인 회원까지 전원의 permission_version을 올린다")
    void redeployReset_clearsRowsAndBumpsEveryVersion() throws Exception {
        v16Database();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            // 허용 행이 0개인 회원(ADMIN·USER·삭제된 MANAGER)도 있고 버전이 서로 다른 상태를 만든다
            st.execute("UPDATE member SET permission_version = 5 WHERE user_id = 'm_active'");
            long[] before = {count(st, "SELECT permission_version FROM member WHERE user_id = 'm_active'"),
                    count(st, "SELECT permission_version FROM member WHERE user_id = 'a_admin'")};

            // docs/deployment.md의 정리 절차와 같은 트랜잭션
            conn.setAutoCommit(false);
            st.execute("DELETE FROM member_permission");
            st.execute("UPDATE member SET permission_version = permission_version + 1");
            conn.commit();
            conn.setAutoCommit(true);

            assertThat(count(st, "SELECT COUNT(*) FROM member_permission")).isZero();
            assertThat(count(st, "SELECT permission_version FROM member WHERE user_id = 'm_active'")).isEqualTo(before[0] + 1);
            assertThat(count(st, "SELECT permission_version FROM member WHERE user_id = 'a_admin'"))
                    .as("허용 행이 0개인 회원도 버전이 올라 정리 전 화면의 PUT은 409가 된다").isEqualTo(before[1] + 1);
            assertThat(count(st, "SELECT COUNT(*) FROM member WHERE permission_version = 0")).isZero();
        }
    }

    // ── ⑬ V17·V18 실패 이력 복구(R-11·R-12) ─────────────────

    @Test
    @DisplayName("V17 성공 + V18만 DDL 적용 후 이력 실패: V18 객체만 DROP하고 repair하면 복구되며 V17의 permission_version은 보존된다")
    void v17Succeeded_v18Failed_recoversByDroppingOnlyV18Object() throws Exception {
        freshSchema();
        flyway("17").migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute(sqlOf("V18__create_member_permission.sql")); // DDL은 적용됐는데 이력 기록 전에 끊긴 상태를 모사
        }

        assertThatThrownBy(() -> flyway(null).migrate()).as("이미 존재하는 테이블이라 V18 재실행 실패").isInstanceOf(FlywayException.class);
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(success(st, "17", 1)).as("V17은 성공 이력").isEqualTo(1);
            assertThat(success(st, "18", 0)).as("V18은 실패 이력").isEqualTo(1);
        }
        assertThatThrownBy(() -> flyway(null).migrate()).hasMessageContaining("failed migration to version 18");

        // 절차: success=1인 V17의 객체는 건드리지 않고 V18의 잔여 객체만 DROP → repair → migrate
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE member_permission");
        }
        flyway(null).repair();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(columnExists(st)).as("V17의 permission_version 보존").isTrue();
            assertThat(tableExists(st)).isTrue();
            for (String version : List.of("17", "18", "19")) {
                assertThat(success(st, version, 1)).as("V" + version).isEqualTo(1);
            }
            assertThat(success(st, "18", 0)).isZero();
        }
    }

    @Test
    @DisplayName("반례(R-12): V17 성공 이력이 있는데 두 객체를 다 지우고 repair하면 V17은 재실행되지 않아 permission_version이 영영 없다 — 절차의 분기 조건을 고정")
    void wrongProcedure_dropsSucceededVersionObject_columnLostForever() throws Exception {
        freshSchema();
        flyway("17").migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute(sqlOf("V18__create_member_permission.sql"));
        }
        assertThatThrownBy(() -> flyway(null).migrate()).isInstanceOf(FlywayException.class);

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE member_permission");
            st.execute("ALTER TABLE member DROP COLUMN permission_version"); // 잘못된 절차: V17은 success=1이었다
        }
        flyway(null).repair();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(success(st, "17", 1)).as("V17은 성공 이력이라 재실행되지 않는다").isEqualTo(1);
            assertThat(columnExists(st)).as("→ 컬럼이 없어 앱 기동(validate)이 실패한다").isFalse();
        }
    }

    @Test
    @DisplayName("V17 DDL만 적용되고 이력이 실패(success=0)면 그 컬럼을 DROP → repair → migrate로 V17~V19가 정상 적용된다")
    void v17DdlAppliedWithoutHistory_recovers() throws Exception {
        freshSchema();
        flyway("16").migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute(sqlOf("V17__add_member_permission_version.sql")); // DDL 적용 후 이력 기록 전 중단 모사
        }

        assertThatThrownBy(() -> flyway(null).migrate()).as("컬럼이 이미 있어 V17 재실행 실패").isInstanceOf(FlywayException.class);
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(success(st, "17", 0)).isEqualTo(1);
            st.execute("ALTER TABLE member DROP COLUMN permission_version"); // 실패한 버전의 잔여 객체(비어 있어 안전)
        }
        flyway(null).repair();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(columnExists(st)).isTrue();
            assertThat(tableExists(st)).isTrue();
            for (String version : List.of("17", "18", "19")) {
                assertThat(success(st, version, 1)).as("V" + version).isEqualTo(1);
            }
        }
    }
}
