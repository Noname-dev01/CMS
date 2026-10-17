package com.cms.admin.menu;

import com.cms.support.MariaDbContainerSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V58("정적 페이지 · 메뉴 · 사이트 관리" 한 항목을 "사이트 관리" 그룹 아래 세 항목으로 나눔) — PLAN-content-menu-unify.md 후속 PR 2 U3-3(U3-v2).
 * 적격 환경은 그룹 + 자식 3개가 되고, 적격하지 않은 환경은 {@code menu} 전체 행 스냅샷이 같다(전부-아니면-전무). 관리자가 이름·아이콘을 바꾼 환경은
 * 적격이되 바꾼 값을 보존한다. 실패 주입으로 중간 실패 시 되돌아감을, 비기본 AUTO_INCREMENT·이전 INSERT ID 잔존으로 {@code LAST_INSERT_ID} 사용의 안전을 고정한다.
 */
class SiteMenuGroupMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "menu_group_v58_test";
    private static final String MERGED_NAME = "정적 페이지 · 메뉴 · 사이트 관리";
    private static final String PAGE = "menu_url = '/admin/site-page/manage' AND site_id = 1";

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

    private static void freshSchema() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement st = root.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }
    }

    private static long count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String text(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** menu 전체 행의 모든 컬럼을 한 문자열 목록으로 — 제외 시나리오가 "아무것도 안 바뀜"을 단언하는 근거. */
    private static List<String> snapshot(Statement st) throws Exception {
        List<String> rows = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT menu_no, menu_name, menu_url, menu_icon, menu_desc, use_yn, ord, up_menu_no, site_id,"
                + " create_date, update_date FROM menu ORDER BY menu_no")) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    row.append(rs.getString(i)).append('|');
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }

    private static List<String> v58Statements() throws Exception {
        try (var in = new PathMatchingResourcePatternResolver().getResource("classpath:db/migration/V58__split_site_admin_menu_group.sql").getInputStream()) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(line -> !line.startsWith("--")).reduce("", (a, b) -> a + "\n" + b);
            return List.of(sql.split(";\\s*\\n")).stream().filter(s -> !s.isBlank()).toList();
        }
    }

    /** V57까지 적용 → 시나리오 변형 → V58 적용 → 변형 직후와 같은 스냅샷인지 단언(전부-아니면-전무의 "전무"). */
    private static void assertV58LeavesUntouched(String... mutations) throws Exception {
        freshSchema();
        flyway("57").migrate();
        List<String> before;
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (String mutation : mutations) {
                st.execute(mutation);
            }
            before = snapshot(st);
        }
        flyway(null).migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(snapshot(st)).as("menu 전체 행이 그대로여야 한다: " + String.join(" / ", mutations)).isEqualTo(before);
        }
    }

    private static void assertGroupShape(Statement st) throws Exception {
        assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE site_id = 1 AND menu_name = '사이트 관리' AND menu_url IS NULL AND up_menu_no IS NULL AND use_yn = 1"))
                .as("그룹 행 1개").isEqualTo(1);
        long group = count(st, "SELECT menu_no FROM menu WHERE site_id = 1 AND menu_name = '사이트 관리'");
        assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE up_menu_no = " + group)).as("자식 3개").isEqualTo(3);
        assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE up_menu_no = " + group + " AND ord = 0 AND " + PAGE)).isEqualTo(1);
        assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE up_menu_no = " + group + " AND ord = 1 AND menu_name = '메뉴 관리'"
                + " AND menu_url = '/admin/menu/manage' AND menu_icon = 'fas fa-fw fa-bars' AND use_yn = 1")).isEqualTo(1);
        assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE up_menu_no = " + group + " AND ord = 2 AND menu_name = '사이트 설정'"
                + " AND menu_url = '/admin/site/manage' AND menu_icon = 'fas fa-fw fa-cog' AND use_yn = 1")).isEqualTo(1);
    }

    @Test
    @DisplayName("시드 그대로(V57 결과): 그룹이 생기고 기존 행이 그 아래 '정적 페이지 관리'로 옮겨지며 메뉴 관리·사이트 설정이 추가되고 나머지는 그대로다")
    void seedIntact_splitsIntoGroup() throws Exception {
        freshSchema();
        flyway("57").migrate();
        List<String> before;
        long pageOrd;
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_name = '" + MERGED_NAME + "' AND " + PAGE)).isEqualTo(1);
            before = snapshot(st);
            pageOrd = count(st, "SELECT ord FROM menu WHERE " + PAGE);
        }

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertGroupShape(st);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE " + PAGE + " AND menu_name = '정적 페이지 관리' AND menu_icon = 'fas fa-fw fa-file-alt'")).isEqualTo(1);
            assertThat(count(st, "SELECT ord FROM menu WHERE site_id = 1 AND menu_name = '사이트 관리' AND up_menu_no IS NULL")).as("그룹은 기존 행의 최상위 순서를 이어받는다").isEqualTo(pageOrd);
            List<String> after = snapshot(st);
            assertThat(after).hasSize(before.size() + 3);
            assertThat(before).filteredOn(row -> !row.contains("/admin/site-page/manage")).isSubsetOf(after);
        }
    }

    @Test
    @DisplayName("신규 설치(V1부터): 그룹 1개·자식 3개이고 합친 이름의 행은 없다")
    void freshInstall_hasGroupAndThreeChildren() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertGroupShape(st);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_name = '" + MERGED_NAME + "'")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/site-page/manage'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("재실행 멱등: V58 문장을 같은 연결에서 다시 실행해도 아무것도 바뀌지 않는다")
    void rerunIsIdempotent() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            List<String> before = snapshot(st);
            for (String statement : v58Statements()) {
                st.execute(statement);
            }
            assertThat(snapshot(st)).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("관리자가 이름·아이콘·설명을 바꾼 환경도 진입점 세 개를 얻고, 바꾼 값은 보존된다(시드 이름일 때만 되돌린다)")
    void customizedLabels_arePreserved() throws Exception {
        freshSchema();
        flyway("57").migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("UPDATE menu SET menu_name = '페이지', menu_icon = 'fas fa-fw fa-book', menu_desc = '설명' WHERE " + PAGE);
        }

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertGroupShape(st);   // 아이콘이 다른 행의 모양 단언은 그룹·자식 구조만 본다
            assertThat(text(st, "SELECT menu_name FROM menu WHERE " + PAGE)).isEqualTo("페이지");
            assertThat(text(st, "SELECT menu_icon FROM menu WHERE " + PAGE)).isEqualTo("fas fa-fw fa-book");
            assertThat(text(st, "SELECT menu_desc FROM menu WHERE " + PAGE)).isEqualTo("설명");
        }
    }

    @Test
    @DisplayName("적격하지 않은 환경은 아무것도 바뀌지 않는다 — 활성 해제·이동·자식·삭제·중복·이미 있는 URL·같은 이름 그룹")
    void ineligibleEnvironments_areUntouched() throws Exception {
        assertV58LeavesUntouched("UPDATE menu SET use_yn = 0 WHERE " + PAGE);
        assertV58LeavesUntouched("UPDATE menu SET up_menu_no = (SELECT menu_no FROM (SELECT menu_no FROM menu WHERE menu_name = '회원 관리') g) WHERE " + PAGE);
        assertV58LeavesUntouched("INSERT INTO menu (menu_name, menu_url, use_yn, ord, up_menu_no, site_id) "
                + "SELECT '활성 자식', '/admin/child', 1, 0, menu_no, 1 FROM menu WHERE " + PAGE);
        assertV58LeavesUntouched("INSERT INTO menu (menu_name, menu_url, use_yn, ord, up_menu_no, site_id) "
                + "SELECT '비활성 자식', '/admin/child', 0, 0, menu_no, 1 FROM menu WHERE " + PAGE);
        assertV58LeavesUntouched("DELETE FROM menu WHERE " + PAGE);
        assertV58LeavesUntouched("INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, ord, site_id) "
                + "VALUES ('정적 페이지 관리', '/admin/site-page/manage', 'fas fa-fw fa-file-alt', 1, 91, 1)");
        assertV58LeavesUntouched("INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, ord, site_id) "
                + "VALUES ('메뉴 관리', '/admin/menu/manage', 'fas fa-fw fa-bars', 1, 92, 1)");
        assertV58LeavesUntouched("INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, ord, site_id) "
                + "VALUES ('사이트', '/admin/site/manage', 'fas fa-fw fa-cog', 1, 93, 1)");
        assertV58LeavesUntouched("INSERT INTO menu (menu_name, menu_url, use_yn, ord, site_id) VALUES ('사이트 관리', NULL, 1, 94, 1)");
        // 대소문자가 다른 URL은 같은 URL로 보지 않으므로 정확히 1개 조건이 깨진다
        assertV58LeavesUntouched("UPDATE menu SET menu_url = '/admin/Site-Page/manage' WHERE " + PAGE);
    }

    @Test
    @DisplayName("이름이 '사이트 관리 '(후행 공백)처럼 다르면 같은 이름으로 보지 않아 적격이다(nopad_bin) — general_ci라면 건너뛰었을 환경")
    void similarGroupName_isNotTheSameName() throws Exception {
        freshSchema();
        flyway("57").migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO menu (menu_name, menu_url, use_yn, ord, site_id) VALUES ('사이트 관리 ', NULL, 1, 94, 1)");
        }

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE site_id = 1 AND menu_url = '/admin/menu/manage'")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE site_id = 1 AND menu_url = '/admin/site/manage'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("공개 사이트(site_id>1)의 행은 건드리지 않고, 관리자 사이트의 분리는 그대로 일어난다")
    void publicSiteRows_areNeverTouched() throws Exception {
        freshSchema();
        flyway("57").migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, default_yn) VALUES ('pub', 'pub', 'PUBLIC', 0, 1, 0)");
            long pub = count(st, "SELECT id FROM site WHERE site_key = 'pub'");
            st.execute("INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, ord, site_id) VALUES ('메뉴 관리', '/admin/menu/manage', 'fas fa-fw fa-bars', 1, 0, " + pub + ")");
            st.execute("INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, ord, site_id) VALUES ('사이트 관리', NULL, 'fas fa-fw fa-bars', 1, 1, " + pub + ")");
        }

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertGroupShape(st);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE site_id > 1")).as("공개 사이트 행은 그대로 2개").isEqualTo(2);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE site_id > 1 AND menu_name = '사이트 관리' AND up_menu_no IS NULL")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("LAST_INSERT_ID: 비기본 AUTO_INCREMENT에서도 그룹 번호가 자식·기존 행의 부모로 정확히 들어간다")
    void nonDefaultAutoIncrement_parentIdIsExact() throws Exception {
        freshSchema();
        flyway("57").migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE menu AUTO_INCREMENT = 5000");
        }

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertGroupShape(st);
            assertThat(count(st, "SELECT menu_no FROM menu WHERE menu_name = '사이트 관리' AND site_id = 1")).isGreaterThanOrEqualTo(5000);
        }
    }

    @Test
    @DisplayName("LAST_INSERT_ID: 같은 연결에 이전 INSERT ID가 남은 비적격 실행은 그 값을 부모로 쓰지 않는다")
    void staleLastInsertId_isNeverUsedWhenIneligible() throws Exception {
        freshSchema();
        flyway(null).migrate();   // 이미 분리된 상태 = 비적격
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            // 이 연결의 LAST_INSERT_ID를 눈에 띄는 값으로 남긴다
            st.execute("INSERT INTO menu (menu_name, menu_url, use_yn, ord, site_id) VALUES ('임시', '/admin/tmp', 1, 99, 1)");
            st.execute("DELETE FROM menu WHERE menu_url = '/admin/tmp'");
            List<String> before = snapshot(st);
            for (String statement : v58Statements()) {
                st.execute(statement);
            }
            assertThat(snapshot(st)).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("실패 주입: 자식 INSERT가 실패하면 그룹 INSERT·이동까지 모두 되돌아가고 이력에 V58이 성공으로 남지 않으며, 원인을 없애면 다시 적용된다")
    void failureMidway_rollsBackEverything() throws Exception {
        freshSchema();
        flyway("57").migrate();
        List<String> before;
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE TRIGGER fail_site_setting BEFORE INSERT ON menu FOR EACH ROW "
                    + "BEGIN IF NEW.menu_name = '사이트 설정' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected'; END IF; END");
            before = snapshot(st);
        }

        assertThatThrownBy(() -> flyway(null).migrate()).hasMessageContaining("V58");

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(snapshot(st)).as("그룹 INSERT·이동이 남으면 안 된다").isEqualTo(before);
            assertThat(count(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '58' AND success = 1")).isZero();
            st.execute("DROP TRIGGER fail_site_setting");
        }

        // 실패한 마이그레이션은 이력에 실패 행이 남는다 — 원인을 없앤 뒤 repair(운영도 같은 절차)하면 다시 적용된다
        flyway(null).repair();
        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertGroupShape(st);
        }
    }
}
