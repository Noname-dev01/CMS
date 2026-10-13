package com.cms.admin.board;

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
 * V49(조회수·다운로드 수 컬럼) — PLAN-view-download-count.md 쟁점 1. 기존 행이 0으로 시작하고, <b>이전 앱이 새 컬럼을 모른 채 INSERT해도</b>
 * 기본값 0으로 들어가는지(롤백 호환)를 고정한다. V48 상태 DB에 게시글·첨부를 넣고 최신까지 올리는 실제 업그레이드 경로다.
 */
class ContentCountMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "content_count_v49_test";

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

    private static String scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    @DisplayName("V48 → V49: 기존 게시글·첨부는 0으로 시작하고, 새 컬럼을 모르는 INSERT(이전 앱)도 기본값 0으로 들어간다")
    void upgradeFromV48_existingRowsStartAtZero_andLegacyInsertWorks() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement st = root.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }
        flyway("48").migrate();
        long postId;
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO board (name, public_yn, attachment_yn, deleted, create_date, update_date)"
                    + " VALUES ('이관 전 게시판', 1, 1, 0, NOW(6), NOW(6))");
            long boardId = Long.parseLong(scalar(st, "SELECT LAST_INSERT_ID()"));
            st.execute("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, create_date, update_date)"
                    + " VALUES (" + boardId + ", '글', '<p>a</p>', 1, 0, 'tester', NOW(6), NOW(6))");
            postId = Long.parseLong(scalar(st, "SELECT LAST_INSERT_ID()"));
            st.execute("INSERT INTO post_attachment (post_id, original_filename, content_type, file_size, storage_key, create_date)"
                    + " VALUES (" + postId + ", 'a.pdf', 'application/pdf', 1, 'k1', NOW(6))");
            assertThat(scalar(st, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '" + SCHEMA
                    + "' AND column_name IN ('view_count', 'download_count')")).as("V48에는 카운터 컬럼이 없다").isEqualTo("0");
        }

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(scalar(st, "SELECT view_count FROM post WHERE id = " + postId)).isEqualTo("0");
            assertThat(scalar(st, "SELECT download_count FROM post_attachment WHERE post_id = " + postId)).isEqualTo("0");
            // 이전 앱의 INSERT(카운터 컬럼 없음)가 그대로 성공하고 0이다 — 롤백 호환
            st.execute("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, create_date, update_date)"
                    + " SELECT board_id, '이전 앱 글', '<p>b</p>', 1, 0, 'tester', NOW(6), NOW(6) FROM post WHERE id = " + postId);
            assertThat(scalar(st, "SELECT view_count FROM post WHERE title = '이전 앱 글'")).isEqualTo("0");
            assertThat(scalar(st, "SELECT is_nullable FROM information_schema.columns WHERE table_schema = '" + SCHEMA
                    + "' AND table_name = 'post' AND column_name = 'view_count'")).isEqualTo("NO");
        }
    }
}
