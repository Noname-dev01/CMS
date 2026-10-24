package com.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * V3(옛 스키마 정리)의 업그레이드 경로 시험(PLAN-legacy-schema-removal.md 설계 3). V2까지 적용한 DB에 옛 형태의 데이터를 넣고 V3를
 * 적용해, 지울 것만 지워지고 나머지는 그대로이며 {@code LEGACY_INLINE} 행이 있으면 아무것도 바꾸지 않고 멈추는지 확인한다.
 *
 * <p>첫 실배포 직전 마지막 합치기 때 V3가 V1에 흡수되면 이 시험은 대상이 없어 지운다(docs/migration-guide.md "시험 정책").
 */
class LegacySchemaRemovalUpgradeTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "legacy_removal_upgrade_test";

    private static String rootUrl() {
        return "jdbc:mariadb://" + MARIA_DB.getHost() + ":" + MARIA_DB.getFirstMappedPort() + "/";
    }

    private static Flyway flyway(String sessionVariables) {
        String url = rootUrl() + SCHEMA + (sessionVariables == null ? "" : "?sessionVariables=" + sessionVariables);
        // V3 시험이라 V4 이후는 적용하지 않는다(V3만 실행됐다는 단언이 뒤 마이그레이션과 무관하게 유지되도록)
        return Flyway.configure().dataSource(url, "root", MARIA_DB.getPassword()).locations("classpath:db/migration").target("3").load();
    }

    /** 새 스키마를 만들고 V2까지 적용한다. */
    private static Flyway migrateToV2(String sessionVariables) throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement s = root.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            s.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }
        Flyway flyway = Flyway.configure()
                .dataSource(rootUrl() + SCHEMA + (sessionVariables == null ? "" : "?sessionVariables=" + sessionVariables),
                        "root", MARIA_DB.getPassword())
                .locations("classpath:db/migration").target("2").load();
        flyway.migrate();
        return flyway(sessionVariables);
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

    private static void insertMember(Statement s, String userId, String kind, String url) throws Exception {
        s.executeUpdate("INSERT INTO member (email, status, user_id, user_name, user_type, password_changed_at, profile_image_kind, profile_image_url)"
                + " VALUES ('" + userId + "@example.com', 'ACTIVE', '" + userId + "', '" + userId + "', 'ROLE_MANAGER', NOW(6), '" + kind + "', "
                + (url == null ? "NULL" : "'" + url + "'") + ")");
    }

    /** V3가 건드리면 안 되는 데이터를 포함해 옛 형태의 데이터를 채운다(LEGACY_INLINE 회원은 넣지 않는다). */
    private static void seedLegacyData(Statement s) throws Exception {
        s.executeUpdate("INSERT INTO notice (title, content, use_yn, deleted, author_id) VALUES ('옛 공지', 'c', 1, 0, 'admin')");
        s.executeUpdate("INSERT INTO notice_attachment (notice_id, original_filename, content_type, file_size, storage_key)"
                + " SELECT id, 'a.txt', 'text/plain', 1, 'old-key' FROM notice");
        s.executeUpdate("INSERT INTO banner (title, link_url, storage_key, content_type, file_size, use_yn, ord) VALUES ('배너', 'https://example.com', 'bk', 'image/png', 5, 1, 0)");
        s.executeUpdate("INSERT INTO popup (title, popup_type, storage_key, content_type, file_size, pos_x, pos_y, width, use_yn)"
                + " VALUES ('팝업', 'IMAGE', 'pk', 'image/png', 5, 1, 2, 300, 1)");
        s.executeUpdate("INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, scope_type) VALUES ('ci', 'image/png', 7, 'admin', 'LIBRARY')");
        insertMember(s, "kind_none", "NONE", null);
        insertMember(s, "kind_preset", "PRESET", "/img/undraw_profile_1.svg");
        insertMember(s, "kind_uploaded", "UPLOADED", "2026/10/22/uuid.png");
    }

    @Test
    @DisplayName("V3 적용: 동결 테이블·옛 컬럼·유니크·LEGACY_INLINE·인덱스·scope_type 기본값만 사라지고, 남는 컬럼 값과 회원 프로필 종류는 그대로다")
    void upgrade_dropsLegacyAndKeepsTheRest() throws Exception {
        Flyway flyway = migrateToV2(null);
        try (Connection c = open(); Statement s = c.createStatement()) {
            seedLegacyData(s);
        }

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);

        try (Connection c = open(); Statement s = c.createStatement()) {
            assertThat(rows(s, "SELECT success FROM flyway_schema_history WHERE version = '3'")).containsExactly("1");
            assertThat(rows(s, "SHOW TABLES LIKE 'notice%'")).isEmpty();
            assertThat(rows(s, "SELECT CONCAT(table_name, '.', column_name) FROM information_schema.COLUMNS WHERE table_schema = '" + SCHEMA
                    + "' AND table_name IN ('banner', 'popup') AND column_name IN ('storage_key', 'content_type', 'file_size')")).isEmpty();
            assertThat(rows(s, "SELECT index_name FROM information_schema.STATISTICS WHERE table_schema = '" + SCHEMA
                    + "' AND index_name IN ('uk_banner_storage_key', 'uk_popup_storage_key', 'idx_member_profile_image_kind')")).isEmpty();
            assertThat(rows(s, "SELECT column_type FROM information_schema.COLUMNS WHERE table_schema = '" + SCHEMA
                    + "' AND table_name = 'member' AND column_name = 'profile_image_kind'")).containsExactly("enum('NONE','PRESET','UPLOADED')");

            assertThat(rows(s, "SELECT title, link_url, use_yn + 0, ord, version FROM banner")).containsExactly("배너|https://example.com|1|0|1");
            assertThat(rows(s, "SELECT title, popup_type, pos_x, pos_y, width, revision FROM popup")).containsExactly("팝업|IMAGE|1|2|300|0");
            assertThat(rows(s, "SELECT storage_key, scope_type FROM content_image")).containsExactly("ci|LIBRARY");
            assertThat(rows(s, "SELECT user_id, profile_image_kind, profile_image_url FROM member ORDER BY user_id")).containsExactly(
                    "kind_none|NONE|null", "kind_preset|PRESET|/img/undraw_profile_1.svg", "kind_uploaded|UPLOADED|2026/10/22/uuid.png");
        }
    }

    @Test
    @DisplayName("LEGACY_INLINE 회원이 있으면 V3가 실패하고 다른 대상은 하나도 바뀌지 않으며, 그 행을 정리한 뒤 repair → migrate 하면 끝난다")
    void upgrade_failsClosedOnLegacyInlineRow_thenRecovers() throws Exception {
        Flyway flyway = migrateToV2(null);
        try (Connection c = open(); Statement s = c.createStatement()) {
            seedLegacyData(s);
            insertMember(s, "kind_legacy", "LEGACY_INLINE", "data:image/png;base64,AAAA");
        }

        assertThatThrownBy(flyway::migrate).isInstanceOf(Exception.class);

        try (Connection c = open(); Statement s = c.createStatement()) {
            assertThat(rows(s, "SHOW TABLES LIKE 'notice%'")).as("동결 테이블 그대로").hasSize(2);
            assertThat(rows(s, "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE table_schema = '" + SCHEMA
                    + "' AND table_name IN ('banner', 'popup') AND column_name = 'storage_key'")).as("옛 컬럼 그대로").containsExactly("2");
            assertThat(rows(s, "SELECT column_type FROM information_schema.COLUMNS WHERE table_schema = '" + SCHEMA
                    + "' AND table_name = 'member' AND column_name = 'profile_image_kind'")).containsExactly("enum('NONE','PRESET','UPLOADED','LEGACY_INLINE')");
            assertThat(rows(s, "SELECT profile_image_kind FROM member WHERE user_id = 'kind_legacy'")).as("값이 ''로 바뀌지 않았다").containsExactly("LEGACY_INLINE");

            // 운영자 조치(옛 러너가 하던 판단 — 여기서는 이미지 없음으로 정리)
            s.executeUpdate("UPDATE member SET profile_image_kind = 'NONE', profile_image_url = NULL WHERE user_id = 'kind_legacy'");
        }

        flyway.repair();
        flyway.migrate();

        try (Connection c = open(); Statement s = c.createStatement()) {
            assertThat(rows(s, "SELECT success FROM flyway_schema_history WHERE version = '3'")).containsExactly("1");
            assertThat(rows(s, "SHOW TABLES LIKE 'notice%'")).isEmpty();
        }
    }

    @Test
    @DisplayName("서버 sql_mode가 비엄격이어도 LEGACY_INLINE 행이 있으면 V3는 실패한다 — 문장에 STRICT_ALL_TABLES를 고정했기 때문이다")
    void upgrade_failsEvenWhenSessionSqlModeIsLenient() throws Exception {
        Flyway flyway = migrateToV2("sql_mode=''");
        try (Connection c = open(); Statement s = c.createStatement()) {
            insertMember(s, "kind_legacy", "LEGACY_INLINE", "data:image/png;base64,AAAA");
        }

        assertThatThrownBy(flyway::migrate).isInstanceOf(Exception.class);

        try (Connection c = open(); Statement s = c.createStatement()) {
            assertThat(rows(s, "SELECT profile_image_kind FROM member WHERE user_id = 'kind_legacy'")).containsExactly("LEGACY_INLINE");
        }
    }
}
