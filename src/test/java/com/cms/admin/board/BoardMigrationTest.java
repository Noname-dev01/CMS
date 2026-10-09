package com.cms.admin.board;

import com.cms.support.MariaDbContainerSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V26(게시판·게시판별 권한)·V27(본문 이미지 출처)·V28(게시판 관리 메뉴) — PLAN-board.md PR A, V29(게시글·첨부)·V30(게시글 관리 메뉴) — PR B.
 * 기존 이미지의 출처 기본값, 메뉴 시드 멱등, FK RESTRICT, 그리고 이전 앱으로의 롤백 기동 호환(새 테이블·컬럼을 몰라도 validate·INSERT가 된다)을 고정한다.
 * PR A 이전 앱(V25 이하 파일만)과 PR A 앱(V28 이하 파일만) 두 하한을 모두 확인한다.
 */
class BoardMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "board_v26_test";

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

    private static String string(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** V25 DB + 공지 편집기로 올린 이미지 한 장(출처 컬럼이 생기기 전). */
    private static void v25DatabaseWithImage() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement st = root.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }
        flyway("25").migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO content_image (storage_key, content_type, file_size, uploader_id)"
                    + " VALUES ('2026/10/08/old.png', 'image/png', 10, 'admin')");
        }
    }

    private static String seedSql(String file) throws Exception {
        try (var in = new PathMatchingResourcePatternResolver().getResource("classpath:db/migration/" + file).getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .lines().filter(line -> !line.startsWith("--")).reduce("", (a, b) -> a + "\n" + b);
        }
    }

    /** 이전 앱이 가진 마이그레이션 파일만(지정 버전 이하) 복사한 위치를 만든다. */
    private static Path copyMigrationsUpTo(int maxVersion, int expectedCount) throws Exception {
        Path dir = Files.createTempDirectory("migration-up-to-v" + maxVersion);
        int copied = 0;
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql")) {
            String name = resource.getFilename();
            if (name != null && Integer.parseInt(name.substring(1, name.indexOf("__"))) <= maxVersion) {
                Files.write(dir.resolve(name), resource.getInputStream().readAllBytes());
                copied++;
            }
        }
        assertThat(copied).isEqualTo(expectedCount);
        return dir;
    }

    private static void deleteQuietly(Path dir) throws Exception {
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
        Files.deleteIfExists(dir);
    }

    @Test
    @DisplayName("V25 → 최신: 기존 이미지는 NOTICE 출처(scope_id NULL), 게시판·게시글 테이블 생성, 게시판 관리·게시글 관리 메뉴는 최상위 맨 끝에 1개씩 이 순서로")
    void upgradeFromV25() throws Exception {
        v25DatabaseWithImage();

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(string(st, "SELECT scope_type FROM content_image WHERE storage_key = '2026/10/08/old.png'")).isEqualTo("NOTICE");
            assertThat(count(st, "SELECT COUNT(*) FROM content_image WHERE scope_id IS NOT NULL")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + SCHEMA
                    + "' AND table_name IN ('board', 'member_board_permission', 'post', 'post_attachment')")).isEqualTo(4);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/board/manage'")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/board/posts'")).isEqualTo(1);
            assertThat(string(st, "SELECT menu_url FROM menu WHERE up_menu_no IS NULL ORDER BY ord DESC, menu_no DESC LIMIT 1"))
                    .isEqualTo("/admin/board/posts");
            assertThat(count(st, "SELECT (SELECT ord FROM menu WHERE menu_url = '/admin/board/posts')"
                    + " - (SELECT ord FROM menu WHERE menu_url = '/admin/board/manage')")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("V28·V30 메뉴 시드는 멱등 — 다시 실행해도 같은 URL 메뉴가 늘지 않는다")
    void menuSeedsAreIdempotent() throws Exception {
        v25DatabaseWithImage();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute(seedSql("V28__seed_board_admin_menu.sql"));
            st.execute(seedSql("V30__seed_board_post_menu.sql"));
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/board/manage'")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/board/posts'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("V29: 게시글·첨부 FK는 RESTRICT — 게시글이 있는 게시판 행, 첨부가 있는 게시글 행은 하드 삭제할 수 없고, 첨부 storage_key는 유일하다")
    void postForeignKeysAreRestrictive() throws Exception {
        v25DatabaseWithImage();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('b', 1, 1, 0)");
            long boardId = count(st, "SELECT id FROM board WHERE name = 'b'");
            st.execute("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id) VALUES (" + boardId + ", 't', '<p>x</p>', 1, 0, 'admin')");
            long postId = count(st, "SELECT id FROM post WHERE board_id = " + boardId);
            st.execute("INSERT INTO post_attachment (post_id, original_filename, content_type, file_size, storage_key)"
                    + " VALUES (" + postId + ", 'a.pdf', 'application/pdf', 1, 'k1')");

            assertThatThrownBy(() -> st.execute("DELETE FROM board WHERE id = " + boardId)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("DELETE FROM post WHERE id = " + postId)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO post_attachment (post_id, original_filename, content_type, file_size, storage_key)"
                    + " VALUES (" + postId + ", 'b.pdf', 'application/pdf', 1, 'k1')")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id)"
                    + " VALUES (999999, 't', 'x', 1, 0, 'a')")).isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("PR A 이전 앱 롤백 호환: 최신 DB를 V25 이하 파일만 가진 위치로 validate·migrate해도 실패하지 않고, 출처를 모르는 INSERT도 기본값 NOTICE가 된다")
    void rollbackToAppWithoutBoard_startsAgainstLatestDatabase() throws Exception {
        v25DatabaseWithImage();
        flyway(null).migrate();

        Path upToV25 = copyMigrationsUpTo(25, 25);
        try {
            Flyway previousApp = Flyway.configure()
                    .dataSource(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword())
                    .locations("filesystem:" + upToV25.toAbsolutePath())
                    .load();
            previousApp.validate();
            previousApp.migrate();

            try (Connection conn = connect(); Statement st = conn.createStatement()) {
                // 구버전 앱의 이미지 INSERT(출처 컬럼 없음)
                st.execute("INSERT INTO content_image (storage_key, content_type, file_size, uploader_id)"
                        + " VALUES ('2026/10/08/rollback.png', 'image/png', 10, 'admin')");
                assertThat(string(st, "SELECT scope_type FROM content_image WHERE storage_key = '2026/10/08/rollback.png'"))
                        .isEqualTo("NOTICE");
            }
        } finally {
            deleteQuietly(upToV25);
        }
    }

    @Test
    @DisplayName("PR B → PR A 롤백 호환(롤백 하한): 최신 DB를 V28 이하 파일만 가진 위치로 validate·migrate해도 실패하지 않고, 게시글 데이터는 그대로 남는다")
    void rollbackFromPostFeatureToBoardDefinitionApp_startsAgainstLatestDatabase() throws Exception {
        v25DatabaseWithImage();
        flyway(null).migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('b', 1, 1, 0)");
            long boardId = count(st, "SELECT id FROM board WHERE name = 'b'");
            st.execute("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id) VALUES (" + boardId + ", 't', '<p>x</p>', 1, 0, 'admin')");
        }

        Path upToV28 = copyMigrationsUpTo(28, 28);
        try {
            Flyway previousApp = Flyway.configure()
                    .dataSource(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword())
                    .locations("filesystem:" + upToV28.toAbsolutePath())
                    .load();
            previousApp.validate();
            previousApp.migrate();

            try (Connection conn = connect(); Statement st = conn.createStatement()) {
                assertThat(count(st, "SELECT COUNT(*) FROM post")).isEqualTo(1);
                // PR A 앱의 게시판 수정은 게시글 테이블을 모르고도 된다
                st.execute("UPDATE board SET name = 'renamed' WHERE name = 'b'");
                assertThat(string(st, "SELECT name FROM board")).isEqualTo("renamed");
            }
        } finally {
            deleteQuietly(upToV28);
        }
    }
}
