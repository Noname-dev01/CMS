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
 * V7(게시판 작성 정책)의 업그레이드 경로 시험(PLAN-board-definition.md PR 2). 기존 테이블에 {@code NOT NULL} 컬럼을 더하는 마이그레이션이라,
 * V6까지 적용한 스키마에 게시판 행을 넣고 V7을 적용해 <b>기존 행</b>이 현행 동작(5개·10MB·전체 확장자·이미지 허용·예약 허용)으로 채워지는지 확인한다.
 *
 * <p>첫 실배포 직전 마지막 합치기 때 V7이 V1에 흡수되면 이 시험은 대상이 없어 지운다(docs/migration-guide.md "시험 정책").
 */
class BoardWritePolicyUpgradeTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "board_write_policy_upgrade_test";

    private static String rootUrl() {
        return "jdbc:mariadb://" + MARIA_DB.getHost() + ":" + MARIA_DB.getFirstMappedPort() + "/";
    }

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword())
                .locations("classpath:db/migration").target(target).load();
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

    @Test
    @DisplayName("V7 적용: 기존 게시판 행(공개·비공개·공지)이 5개·10MB·전체 확장자(NULL)·이미지 허용·예약 허용으로 채워지고 V6 설정과 기존 값은 보존된다")
    void upgrade_fillsDefaultsForExistingRows() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement s = root.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            s.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }
        flyway("6").migrate();

        try (Connection c = DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO board (name, public_yn, attachment_yn, deleted, list_page_size, writable_yn)"
                    + " VALUES ('업그레이드 공개', 1, 1, 0, 30, 0)");
            s.executeUpdate("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('업그레이드 비공개', 0, 0, 0)");
            // 시드 공통 공지 게시판(V2)도 기존 행이다
            assertThat(rows(s, "SELECT COUNT(*) FROM board WHERE board_key = 'NOTICE'")).containsExactly("1");
        }

        assertThat(flyway("7").migrate().migrationsExecuted).isEqualTo(1);

        try (Connection c = DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
             Statement s = c.createStatement()) {
            assertThat(rows(s, "SELECT success FROM flyway_schema_history WHERE version = '7'")).containsExactly("1");
            assertThat(rows(s, "SELECT name, public_yn + 0, attachment_yn + 0, list_page_size, writable_yn + 0, attach_max_count, attach_max_size_mb,"
                    + " IFNULL(attach_extensions, 'NULL'), body_image_yn + 0, publish_period_yn + 0 FROM board ORDER BY id")).containsExactly(
                    "공지사항|1|1|10|1|5|10|NULL|1|1",
                    "업그레이드 공개|1|1|30|0|5|10|NULL|1|1",
                    "업그레이드 비공개|0|0|10|1|5|10|NULL|1|1");
        }
    }
}
