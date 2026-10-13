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
 * V55(배너·팝업·FAQ 카테고리·정적 페이지의 site_id + 배너·FAQ 카테고리 version) — PLAN-site-menu.md PR ③ S1. 기존 행은 전부 공통(NULL)이고
 * version은 1이며, 이전 앱처럼 site_id 없이 INSERT해도 공통으로 들어가고(롤백 호환), FK가 사이트 삭제·없는 사이트 참조를 막고, 재실행해도 제약이 하나뿐이다.
 */
class ContentSiteMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "content_site_v55_test";
    private static final String[][] SITE_ID_TABLES = {
            {"banner", "fk_banner_site"}, {"popup", "fk_popup_site"},
            {"faq_category", "fk_faq_category_site"}, {"site_page", "fk_site_page_site"}};

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

    private static void insertLegacyRows(Statement st) throws SQLException {
        // V54 시점 스키마(site_id 없음)의 행 — 이전 앱이 만들던 모양
        st.execute("INSERT INTO banner (title, storage_key, content_type, file_size, use_yn, ord) VALUES ('b', 'legacy-banner-key', 'image/png', 1, 1, 0)");
        st.execute("INSERT INTO popup (title, popup_type, content_html, pos_x, pos_y, width, use_yn, revision) VALUES ('p', 'HTML', '<p>x</p>', 1, 1, 300, 1, 0)");
        st.execute("INSERT INTO faq_category (name, ord, use_yn) VALUES ('c', 0, 1)");
        st.execute("INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn) VALUES ('legacy-page', 1, 1, 1, 0)");
    }

    @Test
    @DisplayName("V54 → V55: 기존 행은 전부 공통(site_id NULL)이고 배너·FAQ 카테고리 version은 1이다")
    void upgradeFromV54() throws Exception {
        freshSchema();
        flyway("54").migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            insertLegacyRows(st);
        }

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (String[] table : SITE_ID_TABLES) {
                assertThat(count(st, "SELECT COUNT(*) FROM " + table[0])).as(table[0] + " 기존 행").isEqualTo(1);
                assertThat(count(st, "SELECT COUNT(*) FROM " + table[0] + " WHERE site_id IS NULL")).as(table[0] + " 공통").isEqualTo(1);
            }
            assertThat(count(st, "SELECT version FROM banner")).isEqualTo(1);
            assertThat(count(st, "SELECT version FROM faq_category")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("이전 앱처럼 site_id·version 없이 INSERT해도 공통(NULL)·version 1로 들어간다 — 롤백 호환")
    void legacyInsertsStayCommon() throws Exception {
        freshSchema();
        flyway(null).migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            insertLegacyRows(st);
            for (String[] table : SITE_ID_TABLES) {
                assertThat(count(st, "SELECT COUNT(*) FROM " + table[0] + " WHERE site_id IS NULL")).as(table[0]).isEqualTo(1);
            }
            assertThat(count(st, "SELECT version FROM banner")).isEqualTo(1);
            assertThat(count(st, "SELECT version FROM faq_category")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("FK: 없는 사이트를 가리킬 수 없고, 소속 콘텐츠가 있는 사이트는 지울 수 없다(RESTRICT)")
    void foreignKeysRestrict() throws Exception {
        freshSchema();
        flyway(null).migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn) VALUES ('fk-test', 'FK', 'PUBLIC', 0, 1)");
            long siteId = count(st, "SELECT id FROM site WHERE site_key = 'fk-test'");

            assertThatThrownBy(() -> st.execute("INSERT INTO banner (title, use_yn, ord, site_id) VALUES ('x', 1, 0, 99999)"))
                    .as("없는 사이트 참조").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn, site_id) VALUES ('x-page', 1, 1, 1, 0, 99999)"))
                    .isInstanceOf(SQLException.class);

            st.execute("INSERT INTO popup (title, popup_type, content_html, pos_x, pos_y, width, use_yn, revision, site_id) VALUES ('p', 'HTML', '<p>x</p>', 1, 1, 300, 1, 0, " + siteId + ")");
            assertThatThrownBy(() -> st.execute("DELETE FROM site WHERE id = " + siteId))
                    .as("전용 팝업이 있는 사이트 삭제").isInstanceOf(SQLException.class);
            st.execute("DELETE FROM popup WHERE site_id = " + siteId);
            st.execute("DELETE FROM site WHERE id = " + siteId);
        }
    }

    @Test
    @DisplayName("V55는 멱등 — 컬럼·FK가 이미 있는 상태에서 다시 실행해도 실패하지 않고 제약이 하나뿐이다")
    void isIdempotent() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            // 문 단위 암묵 커밋 DDL이라 스크립트 전체를 한 번에 보내지 않고 세미콜론으로 나눠 실행한다
            for (int round = 0; round < 2; round++) {
                for (String statement : sql("V55__add_content_site_id.sql").split(";")) {
                    if (!statement.isBlank()) {
                        st.execute(statement);
                    }
                }
            }
            for (String[] table : SITE_ID_TABLES) {
                assertThat(count(st, "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = '" + SCHEMA
                        + "' AND TABLE_NAME = '" + table[0] + "' AND CONSTRAINT_NAME = '" + table[1] + "'")).as(table[1]).isEqualTo(1);
                assertThat(count(st, "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '" + SCHEMA
                        + "' AND TABLE_NAME = '" + table[0] + "' AND COLUMN_NAME = 'site_id'")).as(table[0] + ".site_id").isEqualTo(1);
            }
        }
    }
}
