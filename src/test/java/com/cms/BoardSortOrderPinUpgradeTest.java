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
 * V8(게시판 표시 순서·상단 고정)의 업그레이드 경로 시험(PLAN-board-definition.md PR 3). 기존 테이블에 {@code NOT NULL} 컬럼을 더하는
 * 마이그레이션이라, V7까지 적용한 스키마에 게시판·게시글 행을 넣고 V8을 적용해 <b>기존 행</b>이 현행 동작(순서 0·고정 끔·고정 아님)으로 채워지고
 * 고정 인덱스가 생기는지 확인한다.
 *
 * <p>첫 실배포 직전 마지막 합치기 때 V8이 V1에 흡수되면 이 시험은 대상이 없어 지운다(docs/migration-guide.md "시험 정책").
 */
class BoardSortOrderPinUpgradeTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "board_sort_order_pin_upgrade_test";

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
    @DisplayName("V8 적용: 기존 게시판은 순서 0·고정 끔, 기존 게시글은 고정 아님으로 채워지고 V7 설정과 기존 값은 보존되며 고정 인덱스가 생긴다")
    void upgrade_fillsDefaultsForExistingRows() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement s = root.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            s.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }
        flyway("7").migrate();

        try (Connection c = DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO board (name, public_yn, attachment_yn, deleted, list_page_size, attach_max_count)"
                    + " VALUES ('업그레이드 공개', 1, 1, 0, 30, 2)");
            s.executeUpdate("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('업그레이드 비공개', 0, 0, 0)");
            s.executeUpdate("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id)"
                    + " SELECT id, '기존 글', '<p>본문</p>', 1, 0, 'admin01' FROM board WHERE name = '업그레이드 공개'");
            // 시드 공통 공지 게시판(V2)도 기존 행이다
            assertThat(rows(s, "SELECT COUNT(*) FROM board WHERE board_key = 'NOTICE'")).containsExactly("1");
        }

        assertThat(flyway("8").migrate().migrationsExecuted).isEqualTo(1);

        try (Connection c = DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
             Statement s = c.createStatement()) {
            assertThat(rows(s, "SELECT success FROM flyway_schema_history WHERE version = '8'")).containsExactly("1");
            assertThat(rows(s, "SELECT name, public_yn + 0, list_page_size, attach_max_count, sort_order, pin_max_count FROM board ORDER BY id"))
                    .containsExactly(
                            "공지사항|1|10|5|0|0",
                            "업그레이드 공개|1|30|2|0|0",
                            "업그레이드 비공개|0|10|5|0|0");
            assertThat(rows(s, "SELECT title, pinned + 0, use_yn + 0, author_id FROM post")).containsExactly("기존 글|0|1|admin01");
            assertThat(rows(s, "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.STATISTICS"
                    + " WHERE table_schema = '" + SCHEMA + "' AND table_name = 'post' AND index_name = 'idx_post_board_pinned'"))
                    .containsExactly("board_id,pinned,deleted,create_date");
        }
    }

    @Test
    @DisplayName("V8을 한 번 더 실행해도(부분 적용 뒤 재실행) 컬럼·인덱스 추가가 실패하지 않는다 — 모든 절이 IF NOT EXISTS")
    void rerunIsIdempotent() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement s = root.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            s.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }
        flyway("8").migrate();
        String sql = new String(getClass().getResourceAsStream("/db/migration/V8__board_sort_order_and_pin.sql").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

        try (Connection c = DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
             Statement s = c.createStatement()) {
            for (String statement : sql.replaceAll("(?m)^--.*$", "").split(";")) {
                if (!statement.isBlank()) {
                    s.execute(statement);
                }
            }
            assertThat(rows(s, "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE table_schema = '" + SCHEMA
                    + "' AND table_name = 'post' AND index_name = 'idx_post_board_pinned'")).containsExactly("4");
        }
    }
}
