package com.cms.admin.media;

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
 * V43~V46(미디어 라이브러리) 마이그레이션 — PLAN-media-library.md D1·D2·D11, 리뷰 R1-1·R3-3·R4-1·R4-3.
 * 빈 DB·이미지 없는 V42 DB·다른 네임스페이스에 <b>같은 키 문자열</b>이 있는 V42 DB의 업그레이드, V44의 부분 상태 재실행 수렴, 제약 변화를 고정한다.
 */
class MediaMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "media_v43_test";

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

    private static String[] statementsOf(String file) throws Exception {
        try (var in = new PathMatchingResourcePatternResolver().getResource("classpath:db/migration/" + file).getInputStream()) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.startsWith("--")).reduce("", (a, b) -> a + "\n" + b);
            return sql.split(";");
        }
    }

    private static void runFile(Statement st, String file) throws Exception {
        for (String statement : statementsOf(file)) {
            if (!statement.isBlank()) {
                st.execute(statement);
            }
        }
    }

    /** V42 시점 데이터 — 배너 2개, 이미지형 팝업 1개(배너와 같은 키 문자열), HTML형 팝업 1개, 본문 이미지 1개(역시 같은 키 문자열). */
    private static void seedV42Data(Statement st) throws Exception {
        st.execute("INSERT INTO banner (title, storage_key, content_type, file_size, use_yn, ord) VALUES ('가을', '2026/10/01/a.png', 'image/png', 100, 1, 0)");
        st.execute("INSERT INTO banner (title, storage_key, content_type, file_size, use_yn, ord) VALUES ('겨울', '2026/10/02/same.png', 'image/png', 200, 1, 1)");
        st.execute("INSERT INTO popup (title, popup_type, storage_key, content_type, file_size, pos_x, pos_y, width, use_yn) "
                + "VALUES ('팝업', 'IMAGE', '2026/10/02/same.png', 'image/gif', 300, 10, 10, 300, 1)");
        st.execute("INSERT INTO popup (title, popup_type, content_html, pos_x, pos_y, width, use_yn) VALUES ('html', 'HTML', '<p>x</p>', 10, 10, 300, 1)");
        st.execute("INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, scope_type, scope_id) "
                + "VALUES ('2026/10/02/same.png', 'image/png', 400, 'u', 'BOARD', 1)");
        st.execute("UPDATE content_image_usage SET total_bytes = 400, total_count = 1 WHERE id = 1");
    }

    @Test
    @DisplayName("V42 → 최신: 배너·이미지형 팝업이 이미지 행+참조로 이관되고(같은 키 문자열도 네임스페이스로 구분), HTML형은 참조가 없으며, 카운터는 원본 재계산 값이다")
    void upgradeFromV42WithData() throws Exception {
        freshSchema();
        flyway("42").migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            seedV42Data(st);
        }

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM content_image")).isEqualTo(4);
            assertThat(count(st, "SELECT COUNT(*) FROM content_image WHERE namespace = 'banner' AND scope_type = 'BANNER'")).isEqualTo(2);
            assertThat(count(st, "SELECT COUNT(*) FROM content_image WHERE namespace = 'popup' AND scope_type = 'POPUP'")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM content_image WHERE namespace = '' AND scope_type = 'BOARD'")).as("본문 이미지는 그대로").isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM content_image WHERE storage_key = '2026/10/02/same.png'")).as("같은 키 문자열이 세 네임스페이스에").isEqualTo(3);
            assertThat(count(st, "SELECT COUNT(*) FROM content_image_ref WHERE owner_type = 'BANNER'")).isEqualTo(2);
            assertThat(count(st, "SELECT COUNT(*) FROM content_image_ref WHERE owner_type = 'POPUP'")).as("HTML형 팝업은 참조가 없다").isEqualTo(1);
            // 참조가 올바른 네임스페이스의 이미지를 가리킨다
            assertThat(count(st, "SELECT COUNT(*) FROM content_image_ref r JOIN banner b ON r.owner_type = 'BANNER' AND r.owner_id = b.id "
                    + "JOIN content_image i ON i.id = r.image_id WHERE i.namespace = 'banner' AND i.storage_key = b.storage_key")).isEqualTo(2);
            assertThat(count(st, "SELECT COUNT(*) FROM content_image_ref r JOIN popup p ON r.owner_type = 'POPUP' AND r.owner_id = p.id "
                    + "JOIN content_image i ON i.id = r.image_id WHERE i.namespace = 'popup' AND i.storage_key = p.storage_key")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM content_image WHERE original_name = '가을' AND namespace = 'banner'")).isEqualTo(1);
            assertThat(count(st, "SELECT total_bytes FROM content_image_usage WHERE id = 1")).isEqualTo(100 + 200 + 300 + 400);
            assertThat(count(st, "SELECT total_count FROM content_image_usage WHERE id = 1")).isEqualTo(4);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/media/manage'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("빈 DB·이미지 없는 V42 DB: 카운터는 (0, 0) — 빈 집합의 SUM(NULL)이 NOT NULL 컬럼에 쓰이지 않는다(COALESCE)")
    void emptyDatabases() throws Exception {
        freshSchema();
        flyway(null).migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT total_bytes FROM content_image_usage WHERE id = 1")).isZero();
            assertThat(count(st, "SELECT total_count FROM content_image_usage WHERE id = 1")).isZero();
        }

        freshSchema();
        flyway("42").migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            // 배너·팝업 행은 있지만 이미지가 없는 경우는 없다 — 이미지형이 없는 V42(HTML형 팝업만)
            st.execute("INSERT INTO popup (title, popup_type, content_html, pos_x, pos_y, width, use_yn) VALUES ('html', 'HTML', '<p>x</p>', 10, 10, 300, 1)");
        }
        flyway(null).migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM content_image")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM content_image_ref WHERE owner_type IN ('BANNER', 'POPUP')")).isZero();
            assertThat(count(st, "SELECT total_count FROM content_image_usage WHERE id = 1")).isZero();
        }
    }

    @Test
    @DisplayName("V44는 문장별로 독립 멱등 — 이미지 행만 있고 참조가 없는 부분 상태·참조까지 있는 상태 모두에서 다시 실행해도 중복 없이 수렴하고 카운터는 같다")
    void backfillIsIdempotentAndConvergesFromPartialState() throws Exception {
        freshSchema();
        flyway("42").migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            seedV42Data(st);
        }
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            long images = count(st, "SELECT COUNT(*) FROM content_image");
            long refs = count(st, "SELECT COUNT(*) FROM content_image_ref");

            runFile(st, "V44__media_library_backfill.sql");      // 완료 상태에서 재실행
            assertThat(count(st, "SELECT COUNT(*) FROM content_image")).isEqualTo(images);
            assertThat(count(st, "SELECT COUNT(*) FROM content_image_ref")).isEqualTo(refs);

            // 부분 상태: 이관된 배너 이미지의 참조만 사라졌다(이미지 행은 남음) → 기존 이미지를 찾아 참조만 다시 만든다(UNIQUE 충돌 없이)
            st.execute("DELETE FROM content_image_ref WHERE owner_type = 'BANNER'");
            runFile(st, "V44__media_library_backfill.sql");
            assertThat(count(st, "SELECT COUNT(*) FROM content_image")).isEqualTo(images);
            assertThat(count(st, "SELECT COUNT(*) FROM content_image_ref")).isEqualTo(refs);

            // 부분 상태: 팝업 이미지 행과 참조가 모두 없다 → 둘 다 다시 만든다
            st.execute("DELETE FROM content_image_ref WHERE owner_type = 'POPUP'");
            st.execute("DELETE FROM content_image WHERE namespace = 'popup'");
            st.execute("UPDATE content_image_usage SET total_bytes = 0, total_count = 0 WHERE id = 1");
            runFile(st, "V44__media_library_backfill.sql");
            assertThat(count(st, "SELECT COUNT(*) FROM content_image")).isEqualTo(images);
            assertThat(count(st, "SELECT COUNT(*) FROM content_image_ref")).isEqualTo(refs);
            assertThat(count(st, "SELECT total_count FROM content_image_usage WHERE id = 1")).as("카운터는 덧셈이 아니라 원본 재계산").isEqualTo(images);
        }
    }

    @Test
    @DisplayName("제약: (namespace, storage_key) 유일 — 같은 네임스페이스의 중복만 거부, 루트('')도 NULL이 아니라 유일. 배너 사본 컬럼은 NULL 허용(여러 행 가능)")
    void constraintsAfterMigration() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, scope_type) VALUES ('k', 'image/png', 1, 'u', 'LIBRARY')");
            st.execute("INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, scope_type, namespace) VALUES ('k', 'image/png', 1, 'u', 'BANNER', 'banner')");
            assertThat(count(st, "SELECT COUNT(*) FROM content_image WHERE storage_key = 'k'")).isEqualTo(2);
            assertThatThrownBy(() -> st.execute("INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, scope_type) VALUES ('k', 'image/png', 1, 'u', 'LIBRARY')"))
                    .as("루트 영역 키 유일").isInstanceOf(SQLException.class);
            assertThat(count(st, "SELECT COUNT(*) FROM content_image WHERE namespace = ''")).as("기본값은 빈 문자열(루트)").isEqualTo(1);

            st.execute("INSERT INTO banner (title, use_yn, ord) VALUES ('a', 1, 0)");
            st.execute("INSERT INTO banner (title, use_yn, ord) VALUES ('b', 1, 1)");
            assertThat(count(st, "SELECT COUNT(*) FROM banner WHERE storage_key IS NULL AND content_type IS NULL AND file_size IS NULL")).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("V46 메뉴 시드는 멱등 — 다시 실행해도 메뉴가 늘지 않는다")
    void menuSeedIsIdempotent() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            runFile(st, "V46__seed_media_menu.sql");
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/media/manage'")).isEqualTo(1);
        }
    }
}
