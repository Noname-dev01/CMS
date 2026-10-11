package com.cms.admin.popup;

import com.cms.support.MariaDbContainerSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
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
 * V39(팝업 테이블)·V40(팝업 관리 메뉴 시드) — PLAN-popup.md 쟁점 2·14. 테이블·메뉴 시드의 존재와 멱등, 컬럼 제약(이미지형·HTML형이 서로 다른 컬럼을
 * 비우는 NULL 허용, storage_key 유일, 필수 컬럼 NOT NULL, revision 기본 0), 그리고 <b>팝업 도입 전 앱(V38 이하 파일만)으로의 롤백 기동 호환</b>을
 * 고정한다 — 새 테이블을 모르는 구버전이 최신 DB에 validate·migrate해도 실패하지 않아야 한다(roll-forward가 기본이지만 즉시 기동은 가능해야 함).
 */
class PopupMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "popup_v39_test";

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

    private static String seedSql() throws Exception {
        try (var in = new PathMatchingResourcePatternResolver().getResource("classpath:db/migration/V40__seed_popup_menu.sql").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(line -> !line.startsWith("--")).reduce("", (a, b) -> a + "\n" + b);
        }
    }

    private static Path copyMigrationsUpTo(int maxVersion) throws Exception {
        Path dir = Files.createTempDirectory("migration-up-to-v" + maxVersion);
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql")) {
            String name = resource.getFilename();
            if (name != null && Integer.parseInt(name.substring(1, name.indexOf("__"))) <= maxVersion) {
                Files.write(dir.resolve(name), resource.getInputStream().readAllBytes());
            }
        }
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
    @DisplayName("V38 → V40: popup 테이블이 생기고(0행), 팝업 관리 메뉴가 최상위 맨 끝에 1개 시드된다")
    void upgradeFromV38() throws Exception {
        freshSchema();
        flyway("38").migrate();
        long topLevelBefore;
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            topLevelBefore = count(st, "SELECT COUNT(*) FROM menu WHERE up_menu_no IS NULL");
        }

        // 최신이 아니라 V40에 고정한다 — 이후 마이그레이션이 최상위 메뉴를 더 추가해도 "팝업 메뉴가 맨 끝" 단언이 깨지지 않게
        flyway("40").migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM popup")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/popup/manage'")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE up_menu_no IS NULL")).isEqualTo(topLevelBefore + 1);
            assertThat(count(st, "SELECT use_yn FROM menu WHERE menu_url = '/admin/popup/manage'")).isEqualTo(1);
            assertThat(count(st, "SELECT (SELECT ord FROM menu WHERE menu_url = '/admin/popup/manage')"
                    + " - (SELECT MAX(ord) FROM menu WHERE up_menu_no IS NULL AND menu_url <> '/admin/popup/manage')")).as("최상위 맨 끝").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("V40 시드는 멱등 — 다시 실행해도 메뉴가 늘지 않는다")
    void seedIsIdempotent() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (String statement : seedSql().split(";")) {
                if (!statement.isBlank()) {
                    st.execute(statement);
                }
            }
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/popup/manage'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("popup 제약: 이미지형은 본문 없이, HTML형은 이미지 없이 저장되고(NULL 허용), storage_key는 유일하며(NULL 여러 개는 허용), 필수 컬럼은 NOT NULL, revision 기본 0")
    void popupConstraints() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO popup (title, popup_type, storage_key, content_type, file_size, pos_x, pos_y, width, use_yn) "
                    + "VALUES ('img', 'IMAGE', 'k1', 'image/png', 1, 10, 10, 300, 1)");
            st.execute("INSERT INTO popup (title, popup_type, content_html, pos_x, pos_y, width, use_yn) VALUES ('html', 'HTML', '<p>x</p>', 10, 10, 300, 1)");
            st.execute("INSERT INTO popup (title, popup_type, content_html, pos_x, pos_y, width, use_yn) VALUES ('html2', 'HTML', '<p>y</p>', 10, 10, 300, 1)");

            assertThat(count(st, "SELECT COUNT(*) FROM popup WHERE popup_type = 'HTML' AND storage_key IS NULL AND link_url IS NULL AND height IS NULL")).isEqualTo(2);
            assertThat(count(st, "SELECT COUNT(*) FROM popup WHERE display_start IS NULL AND display_end IS NULL")).isEqualTo(3);
            assertThat(count(st, "SELECT revision FROM popup WHERE title = 'img'")).as("revision 기본값").isZero();

            assertThatThrownBy(() -> st.execute("INSERT INTO popup (title, popup_type, storage_key, content_type, file_size, pos_x, pos_y, width, use_yn) "
                    + "VALUES ('dup', 'IMAGE', 'k1', 'image/png', 1, 10, 10, 300, 1)")).as("storage_key 유일").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO popup (title, popup_type, pos_y, width, use_yn) VALUES ('t', 'HTML', 10, 300, 1)"))
                    .as("pos_x 필수").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO popup (title, pos_x, pos_y, width, use_yn) VALUES ('t', 10, 10, 300, 1)"))
                    .as("popup_type 필수").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO popup (popup_type, pos_x, pos_y, width, use_yn) VALUES ('HTML', 10, 10, 300, 1)"))
                    .as("title 필수").isInstanceOf(SQLException.class);

            // datetime(6) — 소수 초가 저장된다(서버가 분 단위로 절단한 값을 쓰지만 컬럼 자체는 배너·게시글과 같은 정밀도)
            st.execute("UPDATE popup SET display_start = '2026-10-10 09:00:00.123456' WHERE title = 'img'");
            assertThat(count(st, "SELECT MICROSECOND(display_start) FROM popup WHERE title = 'img'")).isEqualTo(123456);
        }
    }

    @Test
    @DisplayName("팝업 도입 전 앱 롤백 호환: 최신 DB를 V38 이하 파일만 가진 위치로 validate·migrate해도 실패하지 않고, 팝업 데이터와 메뉴 행은 그대로 남는다")
    void rollbackToAppWithoutPopup_startsAgainstLatestDatabase() throws Exception {
        freshSchema();
        flyway(null).migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO popup (title, popup_type, content_html, pos_x, pos_y, width, use_yn) VALUES ('t', 'HTML', '<p>x</p>', 10, 10, 300, 1)");
        }

        Path upToV38 = copyMigrationsUpTo(38);
        try {
            Flyway previousApp = Flyway.configure()
                    .dataSource(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword())
                    .locations("filesystem:" + upToV38.toAbsolutePath())
                    .load();
            previousApp.validate();
            previousApp.migrate();

            try (Connection conn = connect(); Statement st = conn.createStatement()) {
                assertThat(count(st, "SELECT COUNT(*) FROM popup")).isEqualTo(1);
                // 구 앱의 메뉴 관리는 팝업 메뉴 행을 URL 그대로 가질 뿐 카탈로그 밖 URL이라 ADMIN 전용으로 취급한다(구 앱 동작)
                assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/popup/manage'")).isEqualTo(1);
            }
        } finally {
            deleteQuietly(upToV38);
        }
    }
}
