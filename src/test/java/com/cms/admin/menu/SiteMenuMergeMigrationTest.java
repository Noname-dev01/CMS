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

/**
 * V57(사이드바의 "메뉴 관리"를 "정적 페이지 관리"에 합침) — PLAN-content-menu-unify.md "후속 PR — 사이드바 두 항목 통합" U2-2. 시드 그대로인 환경은 한 항목이
 * 되고, 관리자가 손본 환경은 <b>전부-아니면-전무</b>로 두 행의 모든 컬럼이 그대로임을 실제 MariaDB에서 고정한다(제외 시나리오는 삭제 여부가 아니라
 * {@code menu} 전체 행 스냅샷이 같은지로 단언한다). V58이 이 결과를 다시 나누므로 모든 시나리오는 V57 시점(target 57)에 고정한다.
 */
class SiteMenuMergeMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "menu_merge_v57_test";
    private static final String MERGED_NAME = "정적 페이지 · 메뉴 · 사이트 관리";
    private static final String A = "menu_url = '/admin/menu/manage' AND site_id = 1";
    private static final String B = "menu_url = '/admin/site-page/manage' AND site_id = 1";

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

    private static List<String> v57Statements() throws Exception {
        try (var in = new PathMatchingResourcePatternResolver().getResource("classpath:db/migration/V57__merge_menu_manage_into_site_page.sql").getInputStream()) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(line -> !line.startsWith("--")).reduce("", (a, b) -> a + "\n" + b);
            return List.of(sql.split(";\\s*\\n")).stream().filter(s -> !s.isBlank()).toList();
        }
    }

    /** V56까지 적용 → 시나리오 변형 → V57 적용 → 변형 직후와 같은 스냅샷인지 단언(전부-아니면-전무의 "전무"). */
    private static void assertV57LeavesUntouched(String... mutations) throws Exception {
        freshSchema();
        flyway("56").migrate();
        List<String> before;
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (String mutation : mutations) {
                st.execute(mutation);
            }
            before = snapshot(st);
        }
        flyway("57").migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(snapshot(st)).as("두 행과 나머지 전체 행이 그대로여야 한다").isEqualTo(before);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE " + A)).as("메뉴 관리 행 보존").isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("시드 그대로: '메뉴 관리' 행이 지워지고 '정적 페이지 관리' 행의 이름만 바뀌며 나머지는 그대로다")
    void seedIntact_mergesIntoOneEntry() throws Exception {
        freshSchema();
        flyway("56").migrate();
        List<String> before;
        long topBefore;
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE " + A)).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE " + B)).isEqualTo(1);
            before = snapshot(st);
            topBefore = count(st, "SELECT COUNT(*) FROM menu WHERE up_menu_no IS NULL");
        }

        flyway("57").migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE " + A)).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE " + B + " AND menu_name = '" + MERGED_NAME + "'")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE up_menu_no IS NULL")).isEqualTo(topBefore - 1);
            // 나머지 행은 전혀 바뀌지 않았다(삭제된 행과 개명된 행만 스냅샷에서 다르다)
            List<String> after = snapshot(st);
            assertThat(before).hasSize(after.size() + 1);
            assertThat(after).filteredOn(row -> !row.contains("/admin/site-page/manage")).isSubsetOf(before);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE " + B + " AND update_date IS NULL")).as("이름만 바꾸고 update_date는 건드리지 않는다").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("신규 설치(V1부터): /admin/menu/manage 행이 없고 합친 이름의 행이 정확히 1개다")
    void freshInstall_hasSingleMergedEntry() throws Exception {
        freshSchema();
        flyway("57").migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/menu/manage'")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_name = '" + MERGED_NAME + "' AND menu_url = '/admin/site-page/manage'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("재실행 멱등: V57 문장을 같은 연결에서 다시 실행해도 아무것도 바뀌지 않는다")
    void rerunIsIdempotent() throws Exception {
        freshSchema();
        flyway("57").migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            List<String> before = snapshot(st);
            for (String statement : v57Statements()) {
                st.execute(statement);
            }
            assertThat(snapshot(st)).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("관리자가 '메뉴 관리'를 개명·이동·활성 해제·아이콘 변경·설명 추가·자식 추가한 환경은 아무것도 바뀌지 않는다")
    void customizedMenuManageRow_isUntouched() throws Exception {
        assertV57LeavesUntouched("UPDATE menu SET menu_name = '메뉴 편집' WHERE " + A);
        assertV57LeavesUntouched("UPDATE menu SET up_menu_no = (SELECT menu_no FROM (SELECT menu_no FROM menu WHERE menu_name = '회원 관리') g) WHERE " + A);
        assertV57LeavesUntouched("UPDATE menu SET use_yn = 0 WHERE " + A);
        assertV57LeavesUntouched("UPDATE menu SET menu_icon = 'fas fa-fw fa-cog' WHERE " + A);
        assertV57LeavesUntouched("UPDATE menu SET menu_desc = '설명' WHERE " + A);
        assertV57LeavesUntouched("INSERT INTO menu (menu_name, menu_url, use_yn, ord, up_menu_no, site_id) "
                + "SELECT '자식', '/admin/child', 1, 0, menu_no, 1 FROM menu WHERE " + A);
    }

    @Test
    @DisplayName("남길 행('정적 페이지 관리')을 개명·이동·활성 해제·아이콘 변경·삭제한 환경은 두 행 모두 그대로다 — 개명도 없다")
    void customizedSitePageRow_isUntouched() throws Exception {
        assertV57LeavesUntouched("UPDATE menu SET menu_name = '페이지' WHERE " + B);
        assertV57LeavesUntouched("UPDATE menu SET up_menu_no = (SELECT menu_no FROM (SELECT menu_no FROM menu WHERE menu_name = '회원 관리') g) WHERE " + B);
        assertV57LeavesUntouched("UPDATE menu SET use_yn = 0 WHERE " + B);
        assertV57LeavesUntouched("UPDATE menu SET menu_icon = 'fas fa-fw fa-cog' WHERE " + B);
        assertV57LeavesUntouched("UPDATE menu SET menu_name = '" + MERGED_NAME + "' WHERE " + B);
    }

    @Test
    @DisplayName("남길 행에 활성·비활성 자식이 달린 환경은 두 항목이 그대로 남는다 — 그룹이 된 행은 링크가 그려지지 않아 진입점을 잃기 때문(코드 리뷰 P1)")
    void sitePageRowWithChildren_keepsBothEntries() throws Exception {
        assertV57LeavesUntouched("INSERT INTO menu (menu_name, menu_url, use_yn, ord, up_menu_no, site_id) "
                + "SELECT '활성 자식', '/admin/child', 1, 0, menu_no, 1 FROM menu WHERE " + B);
        assertV57LeavesUntouched("INSERT INTO menu (menu_name, menu_url, use_yn, ord, up_menu_no, site_id) "
                + "SELECT '비활성 자식', '/admin/child', 0, 0, menu_no, 1 FROM menu WHERE " + B);
    }

    @Test
    @DisplayName("같은 URL의 행이 하나 더 있는 환경(관리자가 직접 만든 중복)은 아무것도 바뀌지 않는다")
    void duplicateCandidates_areUntouched() throws Exception {
        assertV57LeavesUntouched("INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, ord, site_id) "
                + "VALUES ('메뉴 관리', '/admin/menu/manage', 'fas fa-fw fa-bars', 1, 90, 1)");
        assertV57LeavesUntouched("INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, ord, site_id) "
                + "VALUES ('정적 페이지 관리', '/admin/site-page/manage', 'fas fa-fw fa-file-alt', 1, 91, 1)");
    }

    @Test
    @DisplayName("대소문자·후행 공백이 다른 이름·URL은 시드와 같다고 보지 않는다(nopad_bin 비교) — general_ci라면 통과했을 환경")
    void caseAndTrailingSpace_areNotTreatedAsSeed() throws Exception {
        assertV57LeavesUntouched("UPDATE menu SET menu_name = '메뉴 관리 ' WHERE " + A);
        assertV57LeavesUntouched("UPDATE menu SET menu_name = '정적 페이지 관리 ' WHERE " + B);
        assertV57LeavesUntouched("UPDATE menu SET menu_url = '/admin/menu/Manage' WHERE " + A);
        assertV57LeavesUntouched("UPDATE menu SET menu_url = '/admin/Site-Page/manage' WHERE " + B);
        assertV57LeavesUntouched("UPDATE menu SET menu_icon = 'fas fa-fw fa-bars ' WHERE " + A);
    }

    @Test
    @DisplayName("공개 사이트(site_id>1)의 같은 URL 행은 건드리지 않고, 관리자 사이트의 합치기는 그대로 일어난다")
    void publicSiteRows_areNeverTouched() throws Exception {
        freshSchema();
        flyway("56").migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, default_yn) VALUES ('pub', 'pub', 'PUBLIC', 0, 1, 0)");
            long pub = count(st, "SELECT id FROM site WHERE site_key = 'pub'");
            st.execute("INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, ord, site_id) VALUES ('메뉴 관리', '/admin/menu/manage', 'fas fa-fw fa-bars', 1, 0, " + pub + ")");
            st.execute("INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, ord, site_id) VALUES ('정적 페이지 관리', '/admin/site-page/manage', 'fas fa-fw fa-file-alt', 1, 1, " + pub + ")");
        }

        flyway("57").migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE site_id = 1 AND menu_url = '/admin/menu/manage'")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE site_id = 1 AND menu_name = '" + MERGED_NAME + "'")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE site_id > 1 AND menu_name = '메뉴 관리'")).as("공개 사이트 행은 그대로").isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE site_id > 1 AND menu_name = '정적 페이지 관리'")).isEqualTo(1);
        }
    }
}
