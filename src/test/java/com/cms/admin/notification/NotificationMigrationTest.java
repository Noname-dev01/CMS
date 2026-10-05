package com.cms.admin.notification;

import com.cms.support.MariaDbContainerSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V20(알림 테이블)의 적용·제약·실패 복구 경로(PLAN-admin-notification.md §7 ①, v3 R-10). 별도 스키마에서 V19까지 만든 뒤 V20을 적용한다.
 * 컨텍스트가 실제로 뜨는지({@code ddl-auto: validate})는 공용 Testcontainers 구성이 V1부터 최신까지 적용한 DB로 모든 통합 테스트를 기동하는 것으로 고정돼 있다.
 */
class NotificationMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "upgrade_v20_test";

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
        return new String(NotificationMigrationTest.class.getResourceAsStream("/db/migration/" + file).readAllBytes(),
                StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "").trim();
    }

    private static boolean tableExists(Statement st) throws Exception {
        return count(st, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + SCHEMA
                + "' AND table_name = 'notification'") > 0;
    }

    private static long success(Statement st, int success) throws Exception {
        return count(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '20' AND success = " + success);
    }

    private static void insertMember(Statement st, String userId) throws SQLException {
        st.execute("INSERT INTO member (user_id, pwd, user_name, email, user_type, status, create_date, update_date, "
                + "password_changed_at, failed_login_count, permission_version) VALUES ('" + userId + "', 'x', '이름', '"
                + userId + "@t.example', 'ROLE_ADMIN', 'ACTIVE', NOW(6), NOW(6), NOW(6), 0, 0)");
    }

    @Test
    @DisplayName("V19 DB에 V20을 올리면 notification 테이블이 만들어지고 (member_id, dedupe_key) 유니크·NULL 중복 허용·회원 삭제 시 알림 연쇄 삭제가 동작한다")
    void upgrade_createsTable_withUniqueAndCascade() throws Exception {
        freshSchema();
        flyway("19").migrate();
        flyway(null).migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(tableExists(st)).isTrue();
            assertThat(success(st, 1)).isEqualTo(1);

            insertMember(st, "notif-a");
            long memberId = count(st, "SELECT id FROM member WHERE user_id = 'notif-a'");
            String insert = "INSERT INTO notification (member_id, type, message, dedupe_key, create_date) VALUES (" + memberId
                    + ", 'PASSWORD_EXPIRY', 'm', %s, NOW(6))";

            st.execute(String.format(insert, "'PASSWORD_EXPIRY:2026-07-01'"));
            // 같은 (member_id, dedupe_key)는 중복
            assertThatThrownBy(() -> st.execute(String.format(insert, "'PASSWORD_EXPIRY:2026-07-01'")))
                    .isInstanceOf(SQLException.class);
            // dedupe_key가 NULL이면 중복 제약 대상이 아니다(E1·E2는 매번 생성)
            st.execute(String.format(insert, "NULL"));
            st.execute(String.format(insert, "NULL"));
            assertThat(count(st, "SELECT COUNT(*) FROM notification WHERE member_id = " + memberId)).isEqualTo(3);

            // FK: 없는 회원의 알림은 거부
            assertThatThrownBy(() -> st.execute("INSERT INTO notification (member_id, type, message, create_date) "
                    + "VALUES (999999999, 'ACCOUNT_STATUS', 'm', NOW(6))")).isInstanceOf(SQLException.class);

            // 회원을 지우면 알림이 연쇄 삭제된다(앱은 회원을 하드 삭제하지 않지만 시험·운영 SQL이 조용히 실패하지 않게)
            st.execute("DELETE FROM member WHERE id = " + memberId);
            assertThat(count(st, "SELECT COUNT(*) FROM notification WHERE member_id = " + memberId)).isZero();
        }
    }

    @Test
    @DisplayName("V20 DDL만 적용되고 이력이 없으면(DDL 암묵 커밋 후 중단) 그 테이블만 DROP → repair → migrate로 정상 적용된다")
    void ddlAppliedWithoutHistory_recoversByDroppingOnlyTheTable() throws Exception {
        freshSchema();
        flyway("19").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(sqlOf("V20__create_notification.sql"));
            assertThat(tableExists(st)).isTrue();
            assertThat(success(st, 1)).isZero();
        }

        // 잔여 테이블이 있는 채로 migrate하면 CREATE가 실패한다
        assertThatThrownBy(() -> flyway(null).migrate()).isInstanceOf(Exception.class);

        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS notification");
        }
        flyway(null).repair();
        flyway(null).migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(tableExists(st)).isTrue();
            assertThat(success(st, 1)).isEqualTo(1);
            assertThat(success(st, 0)).isZero();
        }
    }

    @Test
    @DisplayName("V20이 success=1이면 repair는 성공한 마이그레이션을 되감지 않아 테이블·데이터가 보존된다 — 성공 이력 테이블을 일괄 DROP하지 않는다")
    void succeededVersion_repairKeepsTableAndData() throws Exception {
        freshSchema();
        flyway(null).migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            insertMember(st, "notif-keep");
            long memberId = count(st, "SELECT id FROM member WHERE user_id = 'notif-keep'");
            st.execute("INSERT INTO notification (member_id, type, message, create_date) VALUES (" + memberId
                    + ", 'ACCOUNT_STATUS', 'm', NOW(6))");
        }

        flyway(null).repair();
        flyway(null).migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM notification")).isEqualTo(1);
            assertThat(success(st, 1)).isEqualTo(1);
        }
    }
}
