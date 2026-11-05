package com.cms;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.cms.support.MariaDbContainerSupport;

/**
 * V14(비밀번호 90일 만료 폐지)의 업그레이드 경로 시험. 기존 행을 바꾸는 DML이라 V13까지 적용한 스키마에 행을 넣고 V14를 적용해
 * 만료 계정만 ACTIVE가 되고 E3 알림만 지워지는지, 다시 실행해도 결과가 같은지 확인한다.
 *
 * <p>첫 실배포 직전 마지막 합치기 때 V14는 빈 DB에 할 일이 없어 지우고 이 시험도 함께 지운다(docs/migration-guide.md "시험 정책").
 */
class PasswordExpiryRetirementUpgradeTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "password_expiry_upgrade_test";

    private static String rootUrl() {
        return "jdbc:mariadb://" + MARIA_DB.getHost() + ":" + MARIA_DB.getFirstMappedPort() + "/";
    }

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword())
                .locations("classpath:db/migration").target(target).load();
    }

    private static void recreateSchema() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement s = root.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            s.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }
    }

    @AfterEach
    void dropSchema() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement s = root.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + SCHEMA);
        }
    }

    private static List<String> rows(Statement s, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = s.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    row.append(i > 1 ? "|" : "").append(rs.getString(i));
                }
                out.add(row.toString());
            }
        }
        return out;
    }

    private static void insertMember(Statement s, String userId, String status) throws Exception {
        s.executeUpdate("INSERT INTO member (user_id, pwd, user_name, email, user_type, status, failed_login_count, password_changed_at, profile_image_kind, permission_version)"
                + " VALUES ('" + userId + "', 'h', 'n', '" + userId + "@t.example', 'ROLE_MANAGER', '" + status + "', 0, '2026-01-01 00:00:00', 'NONE', 0)");
    }

    private static void insertNotification(Statement s, String userId, String type, String message) throws Exception {
        s.executeUpdate("INSERT INTO notification (member_id, type, message, create_date)"
                + " SELECT id, '" + type + "', '" + message + "', '2026-01-01 00:00:00' FROM member WHERE user_id = '" + userId + "'");
    }

    private static void seed(Statement s) throws Exception {
        insertMember(s, "expired", "PASSWORD_EXPIRED");
        insertMember(s, "active", "ACTIVE");
        insertMember(s, "locked", "LOCKED");
        insertMember(s, "disabled", "DISABLED");
        insertMember(s, "deleted", "DELETED");
        insertNotification(s, "expired", "PASSWORD_EXPIRY", "e3");
        insertNotification(s, "expired", "ACCOUNT_STATUS", "e1");
        insertNotification(s, "active", "PERMISSION", "e2");
    }

    @Test
    @DisplayName("V14 적용: 만료 계정만 ACTIVE가 되고 다른 상태는 그대로이며, E3 알림만 지워진다")
    void upgrade_retiresExpiryOnly() throws Exception {
        recreateSchema();
        flyway("13").migrate();
        try (Connection c = DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
             Statement s = c.createStatement()) {
            seed(s);
        }

        assertThat(flyway("14").migrate().migrationsExecuted).isEqualTo(1);

        try (Connection c = DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
             Statement s = c.createStatement()) {
            assertThat(rows(s, "SELECT success FROM flyway_schema_history WHERE version = '14'")).containsExactly("1");
            assertThat(rows(s, "SELECT user_id, status FROM member ORDER BY id")).containsExactly(
                    "expired|ACTIVE", "active|ACTIVE", "locked|LOCKED", "disabled|DISABLED", "deleted|DELETED");
            assertThat(rows(s, "SELECT type, message FROM notification ORDER BY id")).containsExactly(
                    "ACCOUNT_STATUS|e1", "PERMISSION|e2");
            // 상태 값 자체는 남는다 — 관리자의 변경 강제가 같은 상태를 쓴다
            s.executeUpdate("UPDATE member SET status = 'PASSWORD_EXPIRED' WHERE user_id = 'active'");
            assertThat(rows(s, "SELECT status FROM member WHERE user_id = 'active'")).containsExactly("PASSWORD_EXPIRED");
        }
    }
}
