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
 * V13(게시판 유형)의 업그레이드 경로 시험(PLAN-board-definition.md PR 6). 기존 테이블에 {@code NOT NULL} 컬럼을 더하는 마이그레이션이라, V12까지 적용한
 * 스키마에 게시판 행을 넣고 V13을 적용해 <b>기존 행</b>이 현행 화면(LIST)으로 채워지고 기존 설정이 보존되는지 확인한다.
 *
 * <p>첫 실배포 직전 마지막 합치기 때 V13이 V1에 흡수되면 이 시험은 대상이 없어 지운다(docs/migration-guide.md "시험 정책").
 */
class BoardTypeUpgradeTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "board_type_upgrade_test";

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

    @Test
    @DisplayName("V13 적용: 기존 게시판(시드 공지 포함)은 모두 LIST로 채워지고 V12까지의 설정은 보존된다")
    void upgrade_fillsListForExistingRows() throws Exception {
        recreateSchema();
        flyway("12").migrate();

        try (Connection c = DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO board (name, public_yn, attachment_yn, deleted, list_page_size, pin_max_count, sort_order)"
                    + " VALUES ('업그레이드 공개', 1, 1, 0, 30, 2, 7)");
            s.executeUpdate("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('업그레이드 비공개', 0, 0, 0)");
            assertThat(rows(s, "SELECT COUNT(*) FROM board WHERE board_key = 'NOTICE'")).containsExactly("1");
        }

        assertThat(flyway("13").migrate().migrationsExecuted).isEqualTo(1);

        try (Connection c = DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
             Statement s = c.createStatement()) {
            assertThat(rows(s, "SELECT success FROM flyway_schema_history WHERE version = '13'")).containsExactly("1");
            assertThat(rows(s, "SELECT name, board_type, list_page_size, pin_max_count, sort_order FROM board ORDER BY id"))
                    .containsExactly(
                            "공지사항|LIST|10|0|0",
                            "업그레이드 공개|LIST|30|2|7",
                            "업그레이드 비공개|LIST|10|0|0");
            // 이전 앱이 만드는 INSERT(새 컬럼을 모름)도 DEFAULT로 LIST가 된다 — 롤백 호환
            s.executeUpdate("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('롤백 중 생성', 1, 1, 0)");
            assertThat(rows(s, "SELECT board_type FROM board WHERE name = '롤백 중 생성'")).containsExactly("LIST");
        }
    }

    @Test
    @DisplayName("V13을 한 번 더 실행해도(부분 적용 뒤 재실행) 컬럼 추가가 실패하지 않고 저장된 값을 건드리지 않는다 — IF NOT EXISTS")
    void rerunIsIdempotent() throws Exception {
        recreateSchema();
        flyway("13").migrate();
        String sql = new String(getClass().getResourceAsStream("/db/migration/V13__board_type.sql").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

        try (Connection c = DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO board (name, public_yn, attachment_yn, deleted, board_type) VALUES ('갤러리', 1, 1, 0, 'GALLERY')");
            for (String statement : sql.replaceAll("(?m)^--.*$", "").split(";")) {
                if (!statement.isBlank()) {
                    s.execute(statement);
                }
            }
            assertThat(rows(s, "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE table_schema = '" + SCHEMA
                    + "' AND table_name = 'board' AND column_name = 'board_type'")).containsExactly("1");
            assertThat(rows(s, "SELECT board_type FROM board WHERE name = '갤러리'")).containsExactly("GALLERY");
        }
    }
}
