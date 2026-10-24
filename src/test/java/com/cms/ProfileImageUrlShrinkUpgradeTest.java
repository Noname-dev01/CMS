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
 * V4(프로필 이미지 URL 축소)의 업그레이드 경로 시험(PLAN-profile-image-url-shrink.md). V3까지 적용한 DB에 옛 형태의 데이터를 넣고 V4를
 * 적용해, 값이 그대로 보존되고 255자를 넘는 값이 있으면 아무것도 바꾸지 않고 멈추는지 확인한다.
 *
 * <p>첫 실배포 직전 마지막 합치기 때 V4가 V1에 흡수되면 이 시험은 대상이 없어 지운다(docs/migration-guide.md "시험 정책").
 */
class ProfileImageUrlShrinkUpgradeTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "profile_url_shrink_upgrade_test";
    private static final String COLUMN_TYPE_SQL = "SELECT column_type FROM information_schema.COLUMNS WHERE table_schema = '" + SCHEMA
            + "' AND table_name = 'member' AND column_name = 'profile_image_url'";

    private static String rootUrl() {
        return "jdbc:mariadb://" + MARIA_DB.getHost() + ":" + MARIA_DB.getFirstMappedPort() + "/";
    }

    private static Flyway flyway(String sessionVariables, String target) {
        String url = rootUrl() + SCHEMA + (sessionVariables == null ? "" : "?sessionVariables=" + sessionVariables);
        return Flyway.configure().dataSource(url, "root", MARIA_DB.getPassword()).locations("classpath:db/migration").target(target).load();
    }

    /** 새 스키마를 만들고 V3까지 적용한 뒤, V4까지 적용하는 Flyway를 돌려준다. */
    private static Flyway migrateToV3(String sessionVariables) throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement s = root.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            s.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }
        flyway(sessionVariables, "3").migrate();
        return flyway(sessionVariables, "4");
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

    /** {@code urlExpr}은 SQL 식이다(문자열 리터럴이면 따옴표 포함, NULL 가능). */
    private static void insertMember(Statement s, String userId, String kind, String contentType, String urlExpr) throws Exception {
        s.executeUpdate("INSERT INTO member (email, status, user_id, user_name, user_type, password_changed_at,"
                + " profile_image_kind, profile_image_content_type, profile_image_url)"
                + " VALUES ('" + userId + "@example.com', 'ACTIVE', '" + userId + "', '" + userId + "', 'ROLE_MANAGER', NOW(6), '"
                + kind + "', " + (contentType == null ? "NULL" : "'" + contentType + "'") + ", " + urlExpr + ")");
    }

    @Test
    @DisplayName("V4 적용: 컬럼이 varchar(255)·NULL 허용·기본값 없음이 되고, 업로드 키·프리셋·NULL·정확히 255자 값이 그대로 보존된다")
    void upgrade_shrinksColumnAndKeepsValues() throws Exception {
        Flyway flyway = migrateToV3(null);
        try (Connection c = open(); Statement s = c.createStatement()) {
            assertThat(rows(s, COLUMN_TYPE_SQL)).containsExactly("longtext");
            insertMember(s, "kind_none", "NONE", null, "NULL");
            insertMember(s, "kind_preset", "PRESET", null, "'/img/undraw_profile_1.svg'");
            insertMember(s, "kind_uploaded", "UPLOADED", "image/png", "'2026/10/22/123e4567-e89b-12d3-a456-426614174000.png'");
            insertMember(s, "kind_max", "UPLOADED", "image/png", "REPEAT('k', 255)");
        }

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);

        try (Connection c = open(); Statement s = c.createStatement()) {
            assertThat(rows(s, "SELECT success FROM flyway_schema_history WHERE version = '4'")).containsExactly("1");
            assertThat(rows(s, "SELECT CONCAT(column_type, '|', is_nullable, '|', IFNULL(column_default, 'NULL')) FROM information_schema.COLUMNS"
                    + " WHERE table_schema = '" + SCHEMA + "' AND table_name = 'member' AND column_name = 'profile_image_url'"))
                    .containsExactly("varchar(255)|YES|NULL");
            assertThat(rows(s, "SELECT user_id, profile_image_kind, profile_image_content_type, CHAR_LENGTH(profile_image_url), profile_image_url"
                    + " FROM member WHERE user_id <> 'kind_max' ORDER BY user_id")).containsExactly(
                    "kind_none|NONE|null|null|null",
                    "kind_preset|PRESET|null|25|/img/undraw_profile_1.svg",
                    "kind_uploaded|UPLOADED|image/png|51|2026/10/22/123e4567-e89b-12d3-a456-426614174000.png");
            assertThat(rows(s, "SELECT CHAR_LENGTH(profile_image_url), profile_image_url = REPEAT('k', 255) FROM member WHERE user_id = 'kind_max'"))
                    .as("정확히 255자는 잘리지 않는다").containsExactly("255|1");
        }
    }

    @Test
    @DisplayName("255자를 넘는 값이 있으면 V4가 실패하고 컬럼·값은 그대로이며, 세 컬럼을 정리한 뒤 repair → migrate 하면 끝난다")
    void upgrade_failsClosedOnOverlongValue_thenRecovers() throws Exception {
        Flyway flyway = migrateToV3(null);
        try (Connection c = open(); Statement s = c.createStatement()) {
            insertMember(s, "kind_ok", "PRESET", null, "'/img/undraw_profile.svg'");
            insertMember(s, "kind_long", "UPLOADED", "image/png", "REPEAT('k', 256)");
        }

        assertThatThrownBy(flyway::migrate).isInstanceOf(Exception.class);

        try (Connection c = open(); Statement s = c.createStatement()) {
            assertThat(rows(s, COLUMN_TYPE_SQL)).as("컬럼 그대로").containsExactly("longtext");
            assertThat(rows(s, "SELECT CHAR_LENGTH(profile_image_url) FROM member WHERE user_id = 'kind_long'")).as("값이 잘리지 않았다").containsExactly("256");

            // 운영자 조치 — Member.resetProfileImage()와 같게 세 컬럼을 함께 초기화한다
            s.executeUpdate("UPDATE member SET profile_image_kind = 'NONE', profile_image_url = NULL, profile_image_content_type = NULL"
                    + " WHERE CHAR_LENGTH(profile_image_url) > 255");
            assertThat(rows(s, "SELECT profile_image_kind, profile_image_url, profile_image_content_type FROM member WHERE user_id = 'kind_long'"))
                    .containsExactly("NONE|null|null");
        }

        flyway.repair();
        flyway.migrate();

        try (Connection c = open(); Statement s = c.createStatement()) {
            assertThat(rows(s, "SELECT success FROM flyway_schema_history WHERE version = '4'")).containsExactly("1");
            assertThat(rows(s, COLUMN_TYPE_SQL)).containsExactly("varchar(255)");
            assertThat(rows(s, "SELECT profile_image_url FROM member WHERE user_id = 'kind_ok'")).containsExactly("/img/undraw_profile.svg");
        }
    }

    @Test
    @DisplayName("서버 sql_mode가 비엄격이어도 255자를 넘는 값이 있으면 V4는 실패한다 — 문장에 STRICT_ALL_TABLES를 고정했기 때문이다")
    void upgrade_failsEvenWhenSessionSqlModeIsLenient() throws Exception {
        Flyway flyway = migrateToV3("sql_mode=''");
        try (Connection c = open(); Statement s = c.createStatement()) {
            insertMember(s, "kind_long", "UPLOADED", "image/png", "REPEAT('k', 256)");
        }

        assertThatThrownBy(flyway::migrate).isInstanceOf(Exception.class);

        try (Connection c = open(); Statement s = c.createStatement()) {
            assertThat(rows(s, COLUMN_TYPE_SQL)).containsExactly("longtext");
            assertThat(rows(s, "SELECT CHAR_LENGTH(profile_image_url) FROM member WHERE user_id = 'kind_long'")).containsExactly("256");
        }
    }

    @Test
    @DisplayName("[알려진 한계] 255자 뒤에 후행 공백만 더 있는 값은 엄격 모드에서도 오류 없이 255자로 잘린다 — 앱 경로로는 공백이 든 값을 만들 수 없다")
    void upgrade_trailingSpaceOverflowIsTruncatedSilently() throws Exception {
        Flyway flyway = migrateToV3(null);
        try (Connection c = open(); Statement s = c.createStatement()) {
            // 픽스처 증명: `||`는 PIPES_AS_CONCAT 없이는 논리 OR라 CONCAT으로 만든다
            insertMember(s, "kind_space", "UPLOADED", "image/png", "CONCAT(REPEAT('x', 255), ' ')");
            assertThat(rows(s, "SELECT CHAR_LENGTH(profile_image_url), OCTET_LENGTH(profile_image_url), HEX(RIGHT(profile_image_url, 1))"
                    + " FROM member WHERE user_id = 'kind_space'")).containsExactly("256|256|20");
        }

        // MariaDB는 잘리는 부분이 공백뿐이면 STRICT_ALL_TABLES에서도 NOTE로만 처리한다(비공백이면 위 시험처럼 오류)
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);

        try (Connection c = open(); Statement s = c.createStatement()) {
            assertThat(rows(s, COLUMN_TYPE_SQL)).containsExactly("varchar(255)");
            assertThat(rows(s, "SELECT CHAR_LENGTH(profile_image_url), profile_image_url = REPEAT('x', 255) FROM member WHERE user_id = 'kind_space'"))
                    .containsExactly("255|1");
        }
    }
}
