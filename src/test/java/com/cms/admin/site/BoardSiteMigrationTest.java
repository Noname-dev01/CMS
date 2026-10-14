package com.cms.admin.site;

import com.cms.support.MariaDbContainerSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V56(게시판 board.site_id) — PLAN-site-menu.md PR ④ T1. 기존 게시판(공지 게시판 포함)은 전부 공통(NULL)이고, 이전 앱처럼 site_id 없이 INSERT해도
 * 공통으로 들어가며(롤백 호환), FK가 없는 사이트 참조·소속 게시판이 있는 사이트 삭제를 막고, 재실행해도 제약이 하나뿐이다. 사이트 전용 공지 게시판 키는
 * 기존 UNIQUE(board_key)로 사이트당 하나가 보장된다.
 */
class BoardSiteMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "board_site_v56_test";

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

    private static String sql(String file) throws Exception {
        try (var in = new PathMatchingResourcePatternResolver().getResource("classpath:db/migration/" + file).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(line -> !line.startsWith("--")).reduce("", (a, b) -> a + "\n" + b);
        }
    }

    @Test
    @DisplayName("V55 → V56: 기존 게시판(공지 게시판 포함)은 전부 공통(site_id NULL)이다")
    void upgradeFromV55() throws Exception {
        freshSchema();
        flyway("55").migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('자료실', 1, 1, 0)");
        }

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM board")).isGreaterThanOrEqualTo(2);   // 공지 게시판(V32 시드) + 자료실
            assertThat(count(st, "SELECT COUNT(*) FROM board WHERE site_id IS NOT NULL")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM board WHERE board_key = 'NOTICE' AND site_id IS NULL")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("이전 앱처럼 site_id 없이 INSERT해도 공통(NULL)으로 들어간다 — 롤백 호환")
    void legacyInsertStaysCommon() throws Exception {
        freshSchema();
        flyway(null).migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('이전 앱 게시판', 1, 1, 0)");
            assertThat(count(st, "SELECT COUNT(*) FROM board WHERE name = '이전 앱 게시판' AND site_id IS NULL")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("FK: 없는 사이트를 가리킬 수 없고 소속 게시판이 있는 사이트는 지울 수 없다(RESTRICT). 전용 공지 키는 사이트당 하나뿐이다")
    void foreignKeyRestrictAndNoticeKeyUnique() throws Exception {
        freshSchema();
        flyway(null).migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn) VALUES ('fk-board', 'FK', 'PUBLIC', 0, 1)");
            long siteId = count(st, "SELECT id FROM site WHERE site_key = 'fk-board'");

            assertThatThrownBy(() -> st.execute("INSERT INTO board (name, public_yn, attachment_yn, deleted, site_id) VALUES ('x', 1, 1, 0, 99999)"))
                    .as("없는 사이트 참조").isInstanceOf(SQLException.class);

            st.execute("INSERT INTO board (name, public_yn, attachment_yn, deleted, board_key, site_id) VALUES ('전용 공지', 1, 1, 0, 'NOTICE:" + siteId + "', " + siteId + ")");
            assertThatThrownBy(() -> st.execute("INSERT INTO board (name, public_yn, attachment_yn, deleted, board_key, site_id) VALUES ('중복 공지', 1, 1, 0, 'NOTICE:" + siteId + "', " + siteId + ")"))
                    .as("같은 사이트의 전용 공지 키 중복").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("DELETE FROM site WHERE id = " + siteId))
                    .as("전용 게시판이 있는 사이트 삭제").isInstanceOf(SQLException.class);

            st.execute("DELETE FROM board WHERE site_id = " + siteId);
            st.execute("DELETE FROM site WHERE id = " + siteId);
        }
    }

    @Test
    @DisplayName("V56은 멱등 — 컬럼·FK가 이미 있는 상태에서 다시 실행해도 실패하지 않고 제약이 하나뿐이다")
    void isIdempotent() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (int round = 0; round < 2; round++) {
                for (String statement : sql("V56__add_board_site_id.sql").split(";")) {
                    if (!statement.isBlank()) {
                        st.execute(statement);
                    }
                }
            }
            assertThat(count(st, "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = '" + SCHEMA
                    + "' AND TABLE_NAME = 'board' AND CONSTRAINT_NAME = 'fk_board_site'")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '" + SCHEMA
                    + "' AND TABLE_NAME = 'board' AND COLUMN_NAME = 'site_id'")).isEqualTo(1);
        }
    }
}
