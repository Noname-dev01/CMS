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
 * V6(게시판 표시·식별 설정 + version)의 업그레이드 경로 시험(PLAN-board-definition.md PR 1, 리뷰 P1-R1-3). 기존 테이블에 {@code NOT NULL}
 * 컬럼을 더하는 마이그레이션이라, V5까지 적용한 스키마에 게시판 행을 넣고 V6를 적용해 <b>기존 행</b>이 현행 동작의 기본값으로 채워지는지 확인한다.
 * 새로 설치한 스키마에 INSERT하는 통합 시험은 새 행의 기본값만 보여 이 경로를 검증하지 못한다.
 *
 * <p>첫 실배포 직전 마지막 합치기 때 V6가 V1에 흡수되면 이 시험은 대상이 없어 지운다(docs/migration-guide.md "시험 정책").
 */
class BoardDisplaySettingsUpgradeTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "board_display_upgrade_test";

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
    @DisplayName("V6 적용: 기존 게시판 행(공개·비공개·공지)이 목록 10건·표시 안 함·배지 끔·쓰기 허용·version 1로 채워지고 기존 값은 보존된다")
    void upgrade_fillsDefaultsForExistingRows() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement s = root.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            s.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }
        Flyway toV5 = flyway("5");
        toV5.migrate();

        try (Connection c = DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('업그레이드 공개', 1, 1, 0)");
            s.executeUpdate("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('업그레이드 비공개', 0, 0, 0)");
            // 시드 공통 공지 게시판(V2)도 기존 행이다
            assertThat(rows(s, "SELECT COUNT(*) FROM board WHERE board_key = 'NOTICE'")).containsExactly("1");
        }

        assertThat(flyway("6").migrate().migrationsExecuted).isEqualTo(1);

        try (Connection c = DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
             Statement s = c.createStatement()) {
            assertThat(rows(s, "SELECT success FROM flyway_schema_history WHERE version = '6'")).containsExactly("1");
            assertThat(rows(s, "SELECT name, public_yn + 0, attachment_yn + 0, IFNULL(description, 'NULL'), list_page_size, IFNULL(author_label, 'NULL'),"
                    + " show_view_count + 0, new_badge_days, writable_yn + 0, version FROM board ORDER BY id")).containsExactly(
                    "공지사항|1|1|NULL|10|NULL|0|0|1|1",
                    "업그레이드 공개|1|1|NULL|10|NULL|0|0|1|1",
                    "업그레이드 비공개|0|0|NULL|10|NULL|0|0|1|1");
        }
    }
}
