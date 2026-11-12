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
 * V16(로그인 상태 유지 토큰 테이블 삭제, PLAN-remember-me-removal.md 쟁점 3)의 업그레이드 경로 시험. V15까지 적용한 스키마에 토큰 행을 넣고
 * V16을 적용해 테이블(평문 토큰 포함)이 사라지는지, 테이블을 이미 손으로 지운 DB에서도 V16이 성공하는지 확인한다.
 *
 * <p>첫 실배포 직전 마지막 합치기 때 V15·V16은 함께 사라지므로 이 시험도 지운다(docs/migration-guide.md "시험 정책").
 */
class PersistentLoginsDropUpgradeTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "persistent_logins_drop_upgrade_test";

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

    private static Connection open() throws Exception {
        return DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
    }

    private static List<String> rows(Statement s, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static List<String> persistentLoginsTables(Statement s) throws Exception {
        return rows(s, "SELECT table_name FROM information_schema.TABLES WHERE table_schema = '" + SCHEMA
                + "' AND table_name = 'persistent_logins'");
    }

    @Test
    @DisplayName("V16 적용: 토큰 행이 있는 persistent_logins 테이블이 사라지고 이력은 성공이다")
    void upgrade_dropsTokenTable() throws Exception {
        recreateSchema();
        flyway("15").migrate();
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO persistent_logins (series, username, token, last_used)"
                    + " VALUES ('series-1', 'admin', 'plain-token', '2026-11-12 10:00:00')");
            assertThat(persistentLoginsTables(s)).containsExactly("persistent_logins");
        }

        assertThat(flyway("16").migrate().migrationsExecuted).isEqualTo(1);

        try (Connection c = open(); Statement s = c.createStatement()) {
            assertThat(rows(s, "SELECT success FROM flyway_schema_history WHERE version = '16'")).containsExactly("1");
            assertThat(persistentLoginsTables(s)).isEmpty();
        }
    }

    @Test
    @DisplayName("V16 적용: 테이블을 이미 손으로 지운 DB에서도 실패하지 않는다(IF EXISTS)")
    void upgrade_succeedsWhenTableAlreadyGone() throws Exception {
        recreateSchema();
        flyway("15").migrate();
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE persistent_logins");
        }

        assertThat(flyway("16").migrate().migrationsExecuted).isEqualTo(1);

        try (Connection c = open(); Statement s = c.createStatement()) {
            assertThat(rows(s, "SELECT success FROM flyway_schema_history WHERE version = '16'")).containsExactly("1");
            assertThat(persistentLoginsTables(s)).isEmpty();
        }
    }
}
