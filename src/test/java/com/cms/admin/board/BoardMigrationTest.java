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
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V26(게시판·게시판별 권한)·V27(본문 이미지 출처)·V28(게시판 관리 메뉴) — PLAN-board.md PR A. 기존 이미지의 출처 기본값, 메뉴 시드 멱등,
 * 그리고 PR A 이전 앱(V25 이하 파일만)으로의 롤백 기동 호환(새 테이블·컬럼을 몰라도 validate·INSERT가 된다)을 고정한다.
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

    @Test
    @DisplayName("V25 → 최신: 기존 이미지는 NOTICE 출처(scope_id NULL), 게시판 테이블 생성, 게시판 관리 메뉴는 최상위 맨 끝에 1개")
    void upgradeFromV25() throws Exception {
        v25DatabaseWithImage();

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(string(st, "SELECT scope_type FROM content_image WHERE storage_key = '2026/10/08/old.png'")).isEqualTo("NOTICE");
            assertThat(count(st, "SELECT COUNT(*) FROM content_image WHERE scope_id IS NOT NULL")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + SCHEMA
                    + "' AND table_name IN ('board', 'member_board_permission')")).isEqualTo(2);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/board/manage'")).isEqualTo(1);
            assertThat(string(st, "SELECT menu_url FROM menu WHERE up_menu_no IS NULL ORDER BY ord DESC, menu_no DESC LIMIT 1"))
                    .isEqualTo("/admin/board/manage");
        }
    }

    @Test
    @DisplayName("V28 메뉴 시드는 멱등 — 다시 실행해도 같은 URL 메뉴가 늘지 않는다")
    void boardMenuSeedIsIdempotent() throws Exception {
        v25DatabaseWithImage();
        flyway(null).migrate();
        String sql;
        try (var in = new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V28__seed_board_admin_menu.sql").getInputStream()) {
            sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .lines().filter(line -> !line.startsWith("--")).reduce("", (a, b) -> a + "\n" + b);
        }

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute(sql);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/board/manage'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("PR A 이전 앱 롤백 호환: 최신 DB를 V25 이하 파일만 가진 위치로 validate·migrate해도 실패하지 않고, 출처를 모르는 INSERT도 기본값 NOTICE가 된다")
    void rollbackToAppWithoutBoard_startsAgainstLatestDatabase() throws Exception {
        v25DatabaseWithImage();
        flyway(null).migrate();

        Path upToV25 = Files.createTempDirectory("migration-up-to-v25");
        try {
            int copied = 0;
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql")) {
                String name = resource.getFilename();
                if (name != null && Integer.parseInt(name.substring(1, name.indexOf("__"))) <= 25) {
                    Files.write(upToV25.resolve(name), resource.getInputStream().readAllBytes());
                    copied++;
                }
            }
            assertThat(copied).isEqualTo(25);

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
            try (var files = Files.list(upToV25)) {
                for (Path file : files.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(upToV25);
        }
    }
}
