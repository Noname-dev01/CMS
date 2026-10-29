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
 * V12(게시글 대표 이미지 + 이미지 썸네일)의 업그레이드 경로 시험(PLAN-board-definition.md PR 5). V11까지 적용한 스키마의 기존 게시글이
 * "대표 없음"으로 남는지, FK 동작(이미지 삭제 시 대표는 {@code SET NULL}·썸네일 행은 {@code CASCADE})이 설계대로인지, 재실행이 안전한지,
 * 그리고 {@code docs/deployment.md}의 <b>불변식 복구 SQL</b>이 참조 없는 대표만 해제하는지 확인한다.
 *
 * <p>첫 실배포 직전 마지막 합치기 때 V12가 V1에 흡수되면 이 시험은 대상이 없어 지운다(docs/migration-guide.md "시험 정책").
 */
class PostCoverImageUpgradeTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "post_cover_image_upgrade_test";

    /** docs/deployment.md "게시글 대표 이미지"의 재배포 직후 복구 SQL과 같아야 한다. */
    private static final String RECOVERY_SQL = "UPDATE post p SET p.cover_image_id = NULL WHERE p.cover_image_id IS NOT NULL"
            + " AND NOT EXISTS (SELECT 1 FROM content_image_ref r WHERE r.owner_type = 'POST' AND r.owner_id = p.id AND r.image_id = p.cover_image_id)";

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

    private static void freshSchema() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement s = root.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            s.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
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
    @DisplayName("V12 적용: 기존 게시글은 대표 없음으로 남고, 이미지 삭제 시 대표는 SET NULL·썸네일 행은 CASCADE로 정리된다")
    void upgradeAndForeignKeyBehavior() throws Exception {
        freshSchema();
        flyway("11").migrate();

        try (Connection c = DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('업그레이드', 1, 1, 0)");
            s.executeUpdate("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id)"
                    + " SELECT id, '기존 글', '<p>본문</p>', 1, 0, 'admin01' FROM board WHERE name = '업그레이드'");
            s.executeUpdate("INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, scope_type, namespace)"
                    + " VALUES ('k1', 'image/png', 10, 'admin01', 'LIBRARY', '')");
        }

        assertThat(flyway("12").migrate().migrationsExecuted).isEqualTo(1);

        try (Connection c = DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
             Statement s = c.createStatement()) {
            assertThat(rows(s, "SELECT success FROM flyway_schema_history WHERE version = '12'")).containsExactly("1");
            assertThat(rows(s, "SELECT title, cover_image_id FROM post")).containsExactly("기존 글|null");

            s.executeUpdate("UPDATE post SET cover_image_id = (SELECT id FROM content_image WHERE storage_key = 'k1')");
            s.executeUpdate("INSERT INTO content_image_thumb (image_id, storage_key, content_type, file_size)"
                    + " SELECT id, 't1', 'image/png', 5 FROM content_image WHERE storage_key = 'k1'");
            // 같은 이미지의 썸네일 행은 하나뿐이다(PK) — INSERT IGNORE가 두 번째를 조용히 건너뛰는 근거
            assertThat(s.executeUpdate("INSERT IGNORE INTO content_image_thumb (image_id, storage_key, content_type, file_size)"
                    + " SELECT id, 't2', 'image/png', 5 FROM content_image WHERE storage_key = 'k1'")).isZero();

            // 롤백한 이전 앱처럼 참조 없이 이미지를 지운다 — 대표는 비워지고 썸네일 행은 따라서 사라진다
            s.executeUpdate("DELETE FROM content_image WHERE storage_key = 'k1'");
            assertThat(rows(s, "SELECT cover_image_id FROM post")).containsExactly("null");
            assertThat(rows(s, "SELECT COUNT(*) FROM content_image_thumb")).containsExactly("0");
        }
    }

    @Test
    @DisplayName("복구 SQL: 본문 참조에서 빠진 대표만 해제하고 참조 중인 대표는 건드리지 않는다")
    void recoverySqlClearsOnlyUnreferencedCovers() throws Exception {
        freshSchema();
        flyway("12").migrate();

        try (Connection c = DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('복구', 1, 1, 0)");
            s.executeUpdate("INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, scope_type, namespace)"
                    + " VALUES ('a', 'image/png', 10, 'admin01', 'LIBRARY', ''), ('b', 'image/png', 10, 'admin01', 'LIBRARY', '')");
            s.executeUpdate("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, cover_image_id)"
                    + " SELECT b.id, p.title, '<p>x</p>', 1, 0, 'admin01', i.id FROM board b"
                    + " JOIN (SELECT '참조 유지' AS title, 'a' AS k UNION ALL SELECT '참조 빠짐', 'b') p"
                    + " JOIN content_image i ON i.storage_key = p.k WHERE b.name = '복구'");
            // '참조 유지' 글만 자기 대표(a)를 참조한다 — '참조 빠짐' 글은 롤백한 이전 앱이 본문에서 b를 뺀 상태
            s.executeUpdate("INSERT INTO content_image_ref (owner_type, owner_id, image_id)"
                    + " SELECT 'POST', p.id, i.id FROM post p JOIN content_image i ON i.storage_key = 'a' WHERE p.title = '참조 유지'");
            // 다른 글이 b를 참조하는 것은 이 글의 대표 소속이 아니다 — 소유자까지 맞아야 한다
            s.executeUpdate("INSERT INTO content_image_ref (owner_type, owner_id, image_id)"
                    + " SELECT 'POST', p.id, i.id FROM post p JOIN content_image i ON i.storage_key = 'b' WHERE p.title = '참조 유지'");

            assertThat(s.executeUpdate(RECOVERY_SQL)).isEqualTo(1);

            assertThat(rows(s, "SELECT p.title, i.storage_key FROM post p LEFT JOIN content_image i ON i.id = p.cover_image_id ORDER BY p.title DESC"))
                    .containsExactly("참조 유지|a", "참조 빠짐|null");
        }
    }

    @Test
    @DisplayName("V12를 한 번 더 실행해도(부분 적용 뒤 재실행) 실패하지 않는다 — 컬럼·FK·테이블 모두 IF NOT EXISTS")
    void rerunIsIdempotent() throws Exception {
        freshSchema();
        flyway("12").migrate();
        String sql = new String(getClass().getResourceAsStream("/db/migration/V12__post_cover_image_and_thumbnail.sql").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

        try (Connection c = DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
             Statement s = c.createStatement()) {
            for (String statement : sql.replaceAll("(?m)^--.*$", "").split(";")) {
                if (!statement.isBlank()) {
                    s.execute(statement);
                }
            }
            assertThat(rows(s, "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE table_schema = '" + SCHEMA
                    + "' AND table_name = 'post' AND constraint_name = 'fk_post_cover_image'")).containsExactly("1");
        }
    }
}
