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
 * V36(게시글 노출 기간 컬럼) — PLAN-post-publish-period.md 쟁점 5. 기존 행이 둘 다 NULL(= 제한 없음)로 남아 <b>기존 공개 동작이 그대로</b>이고
 * 컬럼이 {@code datetime(6) NULL}임을 고정한다. V35 상태 DB에 게시글을 넣고 최신까지 올리는 실제 업그레이드 경로다.
 */
class PostPublishPeriodMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "post_period_v36_test";

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

    private static void freshSchema() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement st = root.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }
    }

    @Test
    @DisplayName("V35 → V36: 기존 게시글은 publish_start·publish_end가 NULL로 남고 use_yn·삭제 상태·본문은 그대로다 (기존 공개 동작 불변)")
    void upgradeFromV35_existingPostsKeepUnrestrictedPeriod() throws Exception {
        freshSchema();
        flyway("35").migrate();
        long boardId;
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO board (name, public_yn, attachment_yn, deleted, create_date, update_date)"
                    + " VALUES ('이관 전 게시판', 1, 1, 0, NOW(6), NOW(6))");
            boardId = Long.parseLong(scalar(st, "SELECT LAST_INSERT_ID()"));
            st.execute("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, create_date, update_date) VALUES"
                    + " (" + boardId + ", '노출 글', '<p>a</p>', 1, 0, 'tester', NOW(6), NOW(6)),"
                    + " (" + boardId + ", '비노출 글', '<p>b</p>', 0, 0, 'tester', NOW(6), NOW(6)),"
                    + " (" + boardId + ", '삭제 글', '<p>c</p>', 1, 1, 'tester', NOW(6), NOW(6))");
            assertThat(scalar(st, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '" + SCHEMA
                    + "' AND table_name = 'post' AND column_name IN ('publish_start', 'publish_end')")).as("V35에는 기간 컬럼이 없다").isEqualTo("0");
        }

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(scalar(st, "SELECT COUNT(*) FROM post WHERE board_id = " + boardId)).isEqualTo("3");
            assertThat(scalar(st, "SELECT COUNT(*) FROM post WHERE board_id = " + boardId
                    + " AND publish_start IS NULL AND publish_end IS NULL")).as("기존 행은 둘 다 NULL = 제한 없음").isEqualTo("3");
            assertThat(scalar(st, "SELECT CAST(use_yn AS UNSIGNED) FROM post WHERE title = '노출 글'")).isEqualTo("1");
            assertThat(scalar(st, "SELECT CAST(use_yn AS UNSIGNED) FROM post WHERE title = '비노출 글'")).isEqualTo("0");
            assertThat(scalar(st, "SELECT CAST(deleted AS UNSIGNED) FROM post WHERE title = '삭제 글'")).isEqualTo("1");
            // 기존 공개 조건(use_yn ∧ ¬deleted ∧ 기간 NULL)이 그대로 노출 글 하나만 고른다
            assertThat(scalar(st, "SELECT COUNT(*) FROM post WHERE board_id = " + boardId
                    + " AND deleted = 0 AND use_yn = 1 AND (publish_start IS NULL OR publish_start <= NOW(6))"
                    + " AND (publish_end IS NULL OR publish_end > NOW(6))")).isEqualTo("1");
        }
    }

    @Test
    @DisplayName("V36 컬럼: publish_start·publish_end는 datetime(6) NULL 허용이고 기본값이 없다")
    void columnDefinitions() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (String column : new String[]{"publish_start", "publish_end"}) {
                String where = " FROM information_schema.columns WHERE table_schema = '" + SCHEMA
                        + "' AND table_name = 'post' AND column_name = '" + column + "'";
                assertThat(scalar(st, "SELECT column_type" + where)).as(column).isEqualTo("datetime(6)");
                assertThat(scalar(st, "SELECT is_nullable" + where)).as(column).isEqualTo("YES");
                assertThat(String.valueOf(scalar(st, "SELECT column_default" + where))).as(column + " 기본값(MariaDB는 DEFAULT NULL을 문자열 NULL로 보고한다)").isEqualToIgnoringCase("null");
            }
        }
    }

    @Test
    @DisplayName("V36 저장 범위: 2000-01-01~2099-12-31 값이 왕복하고 DB는 1000~9999년을 허용한다 — 앱의 제품 범위(2000~2099)는 그 안이다")
    void storableRange() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO board (name, public_yn, attachment_yn, deleted, create_date, update_date) VALUES ('범위', 1, 1, 0, NOW(6), NOW(6))");
            String boardId = scalar(st, "SELECT LAST_INSERT_ID()");
            st.execute("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, create_date, update_date, publish_start, publish_end)"
                    + " VALUES (" + boardId + ", '범위', '<p>x</p>', 1, 0, 'tester', NOW(6), NOW(6), '2000-01-01 00:00:00.000000', '2099-12-31 23:59:00.000000')");
            assertThat(scalar(st, "SELECT DATE_FORMAT(publish_start, '%Y-%m-%d %H:%i') FROM post WHERE title = '범위'")).isEqualTo("2000-01-01 00:00");
            assertThat(scalar(st, "SELECT DATE_FORMAT(publish_end, '%Y-%m-%d %H:%i') FROM post WHERE title = '범위'")).isEqualTo("2099-12-31 23:59");
        }
    }
}
