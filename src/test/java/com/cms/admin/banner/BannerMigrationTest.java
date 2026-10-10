package com.cms.admin.banner;

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
 * V33(배너·가드 테이블)·V34(가드 행 + 배너 관리 메뉴 시드) — PLAN-public-home-banner.md 쟁점 2·5·15. 가드 행·메뉴 시드의 존재와 멱등,
 * 컬럼 제약(NULL 기간 허용, storage_key 유일), 그리고 <b>배너 도입 전 앱(V32 이하 파일만)으로의 롤백 기동 호환</b>을 고정한다 —
 * 새 테이블을 모르는 구버전이 최신 DB에 validate·migrate해도 실패하지 않아야 한다(roll-forward가 기본이지만 즉시 기동은 가능해야 함).
 */
class BannerMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "banner_v33_test";

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
        try (var in = new PathMatchingResourcePatternResolver().getResource("classpath:db/migration/V34__seed_banner_lock_and_menu.sql").getInputStream()) {
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
    @DisplayName("V32 → 최신: banner·banner_lock 테이블이 생기고 가드 행(id=1)이 정확히 1개, 배너 관리 메뉴가 최상위 맨 끝에 1개 시드된다")
    void upgradeFromV32() throws Exception {
        freshSchema();
        flyway("32").migrate();
        long topLevelBefore;
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            topLevelBefore = count(st, "SELECT COUNT(*) FROM menu WHERE up_menu_no IS NULL");
        }

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM banner")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM banner_lock")).isEqualTo(1);
            assertThat(count(st, "SELECT id FROM banner_lock")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/banner/manage'")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE up_menu_no IS NULL")).isEqualTo(topLevelBefore + 1);
            assertThat(count(st, "SELECT use_yn FROM menu WHERE menu_url = '/admin/banner/manage'")).isEqualTo(1);
            assertThat(count(st, "SELECT (SELECT ord FROM menu WHERE menu_url = '/admin/banner/manage')"
                    + " - (SELECT MAX(ord) FROM menu WHERE up_menu_no IS NULL AND menu_url <> '/admin/banner/manage')")).as("최상위 맨 끝").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("V34 시드는 멱등 — 다시 실행해도 가드 행·메뉴가 늘지 않는다")
    void seedIsIdempotent() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            String seed = seedSql();
            for (String statement : seed.split(";")) {
                if (!statement.isBlank()) {
                    st.execute(statement);
                }
            }
            assertThat(count(st, "SELECT COUNT(*) FROM banner_lock")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/banner/manage'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("banner 제약: 노출 기간·링크는 NULL 허용, storage_key는 유일, 필수 컬럼은 NOT NULL")
    void bannerConstraints() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO banner (title, storage_key, content_type, file_size, use_yn, ord) VALUES ('t', 'k1', 'image/png', 1, 1, 0)");
            assertThat(count(st, "SELECT COUNT(*) FROM banner WHERE display_start IS NULL AND display_end IS NULL AND link_url IS NULL")).isEqualTo(1);

            assertThatThrownBy(() -> st.execute("INSERT INTO banner (title, storage_key, content_type, file_size, use_yn, ord) VALUES ('t2', 'k1', 'image/png', 1, 1, 1)"))
                    .as("storage_key 유일").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO banner (title, storage_key, content_type, file_size, use_yn) VALUES ('t3', 'k3', 'image/png', 1, 1)"))
                    .as("ord 필수").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO banner (storage_key, content_type, file_size, use_yn, ord) VALUES ('k4', 'image/png', 1, 1, 2)"))
                    .as("title 필수").isInstanceOf(SQLException.class);

            // datetime(6) — 소수 초가 저장된다(서버가 분 단위로 절단한 값을 쓰지만 컬럼 자체는 V29 등과 같은 정밀도)
            st.execute("UPDATE banner SET display_start = '2026-10-10 09:00:00.123456' WHERE storage_key = 'k1'");
            assertThat(count(st, "SELECT MICROSECOND(display_start) FROM banner WHERE storage_key = 'k1'")).isEqualTo(123456);
        }
    }

    @Test
    @DisplayName("배너 도입 전 앱 롤백 호환: 최신 DB를 V32 이하 파일만 가진 위치로 validate·migrate해도 실패하지 않고, 배너 데이터와 메뉴 행은 그대로 남는다")
    void rollbackToAppWithoutBanner_startsAgainstLatestDatabase() throws Exception {
        freshSchema();
        flyway(null).migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO banner (title, storage_key, content_type, file_size, use_yn, ord) VALUES ('t', 'k1', 'image/png', 1, 1, 0)");
        }

        Path upToV32 = copyMigrationsUpTo(32);
        try {
            Flyway previousApp = Flyway.configure()
                    .dataSource(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword())
                    .locations("filesystem:" + upToV32.toAbsolutePath())
                    .load();
            previousApp.validate();
            previousApp.migrate();

            try (Connection conn = connect(); Statement st = conn.createStatement()) {
                assertThat(count(st, "SELECT COUNT(*) FROM banner")).isEqualTo(1);
                assertThat(count(st, "SELECT COUNT(*) FROM banner_lock")).isEqualTo(1);
                // 구 앱의 메뉴 관리는 배너 메뉴 행을 URL 그대로 가질 뿐 카탈로그 밖 URL이라 ADMIN 전용으로 취급한다(구 앱 동작)
                assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/banner/manage'")).isEqualTo(1);
            }
        } finally {
            deleteQuietly(upToV32);
        }
    }
}
