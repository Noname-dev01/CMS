package com.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.cms.common.html.HtmlContentSanitizer;
import com.cms.support.MariaDbContainerSupport;

/**
 * V10(저장된 숫자 게시판 링크 → 슬러그 링크 치환, Java 마이그레이션)의 업그레이드 경로 시험(PLAN-board-definition.md "PR 4a 상세 설계" D6).
 * V9까지 적용한 DB에 링크가 든 행을 넣고 V10을 적용해 치환 대상·비대상·버전 증가·fail-fast(전체 롤백 → 실패 이력 → 수정 → repair → 재실행)를 확인한다.
 *
 * <p>본문 픽스처는 PR L sanitizer가 실제로 저장하던 모양(내부 링크는 {@code rel} 다음 {@code href}, {@code target} 없음)을 손으로 쓴다 — 지금 sanitizer는
 * 숫자 게시판 {@code href}를 지우므로 그것으로 만들 수 없다. 치환 결과는 지금 sanitizer의 고정점이어야 한다(멱등).
 *
 * <p>첫 실배포 직전 마지막 합치기 때 V10은 빈 DB에 할 일이 없어 삭제되고 이 시험도 함께 지운다(docs/migration-guide.md "시험 정책").
 */
class BoardSlugLinkUpgradeTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "board_slug_link_upgrade_test";
    private static final String REL = "rel=\"noopener noreferrer nofollow\"";

    private static String rootUrl() {
        return "jdbc:mariadb://" + MARIA_DB.getHost() + ":" + MARIA_DB.getFirstMappedPort() + "/";
    }

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword())
                .locations("classpath:db/migration").target(target).load();
    }

    private static Connection open() throws Exception {
        return DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
    }

    @BeforeEach
    void createSchemaAtV9() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement s = root.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            s.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }
        flyway("9").migrate();
    }

    @AfterEach
    void dropSchema() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement s = root.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + SCHEMA);
        }
    }

    private static long insert(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    private static List<String> rows(Connection c, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
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

    private static String one(Connection c, String sql) throws Exception {
        List<String> result = rows(c, sql);
        assertThat(result).hasSize(1);
        return result.get(0);
    }

    private static long menu(Connection c, long siteId, String url) throws Exception {
        return insert(c, "INSERT INTO menu (menu_name, use_yn, site_id, menu_url) VALUES ('m', 1, ?, ?)", siteId, url);
    }

    private static String menuUrl(Connection c, long id) throws Exception {
        return one(c, "SELECT menu_url FROM menu WHERE menu_no = " + id);
    }

    private static long banner(Connection c, String url) throws Exception {
        return insert(c, "INSERT INTO banner (title, use_yn, ord, link_url) VALUES ('b', 1, 0, ?)", url);
    }

    private static long faq(Connection c, String answer) throws Exception {
        long category = insert(c, "INSERT INTO faq_category (name, ord, use_yn) VALUES ('c', 0, 1)");
        return insert(c, "INSERT INTO faq (category_id, question, answer_html, ord, use_yn) VALUES (?, 'q', ?, 0, 1)", category, answer);
    }

    private static String link(String href, String text) {
        return "<a " + REL + " href=\"" + href + "\">" + text + "</a>";
    }

    @Test
    @DisplayName("V10: 공개 사이트 키의 숫자 게시판 링크만 board-{id}로 치환하고 대상 아닌 값·관리자 메뉴·본문 텍스트는 그대로, 배너 version·팝업 revision은 행당 +1")
    void upgrade_replacesLinks() throws Exception {
        long menuPlain, menuSub, menuLong, menuPlus, menuZero, menuWide, menuArabic, menuNoSite, menuOverflow, menuSlug, menuQueryParam,
                menuDisabledSite, menuAdmin, menuPrefix;
        long bannerLink, bannerExternal, bannerProtocolRelative, popupImage, popupHtml, popupOther, post, faqId, page1, page2;
        String postBody = "<p>글 /main/boards/7 " + link("/main/boards/7/posts/1?a=1&amp;b=2", "링크") + " "
                + "<a href=\"https://x.test/main/boards/7\" " + REL + " target=\"_blank\">외부</a> "
                + link("/main/boards/board-3", "슬러그") + " " + link("/main/boards/70", "칠십") + "</p><p><img src=\"/content-images/7\"></p>";
        try (Connection c = open()) {
            long newsSite = insert(c, "INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, default_yn)"
                    + " VALUES ('news', '뉴스', 'PUBLIC', 1, 0, 0)");
            menuPlain = menu(c, 2, "/main/boards/12");
            menuSub = menu(c, 2, "/main/boards/12/posts/3/attachments/4?page=2#x");
            menuLong = menu(c, 2, "/main/boards/123");
            menuPlus = menu(c, 2, "/main/boards/+12");
            menuZero = menu(c, 2, "/main/boards/0012?x=1");
            menuWide = menu(c, 2, "/main/boards/１２");
            menuArabic = menu(c, 2, "/main/boards/١٢/posts/1");
            menuNoSite = menu(c, 2, "/old/boards/12");
            menuOverflow = menu(c, 2, "/main/boards/9223372036854775808");
            menuSlug = menu(c, 2, "/main/boards/board-3");
            menuQueryParam = menu(c, 2, "/main/pages/a?next=/main/boards/12");
            menuDisabledSite = menu(c, newsSite, "/news/boards/5");
            menuAdmin = menu(c, 1, "/main/boards/12");
            menuPrefix = menu(c, 2, "/main/boards/12x");
            bannerLink = banner(c, "/main/boards/7");
            bannerExternal = banner(c, "https://example.com/main/boards/7");
            bannerProtocolRelative = banner(c, "//host/main/boards/7");
            popupImage = insert(c, "INSERT INTO popup (title, popup_type, pos_x, pos_y, width, use_yn, link_url) VALUES ('p', 'IMAGE', 0, 0, 300, 1, ?)",
                    "/main/boards/7");
            popupHtml = insert(c, "INSERT INTO popup (title, popup_type, pos_x, pos_y, width, use_yn, link_url, content_html)"
                    + " VALUES ('p', 'HTML', 0, 0, 300, 1, ?, ?)", "/main/boards/8", "<p>" + link("/main/boards/7", "x") + "</p>");
            popupOther = insert(c, "INSERT INTO popup (title, popup_type, pos_x, pos_y, width, use_yn, link_url) VALUES ('p', 'IMAGE', 0, 0, 300, 1, ?)",
                    "/main/faq");
            long board = insert(c, "INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('b', 1, 1, 0)");
            post = insert(c, "INSERT INTO post (board_id, title, content, use_yn, deleted, author_id) VALUES (?, 't', ?, 1, 1, 'admin01')",
                    board, postBody);
            faqId = faq(c, "<p>" + link("/main/boards/9#q", "답") + "</p>");
            page1 = insert(c, "INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn) VALUES ('p-one', 2, 3, 1, 0)");
            page2 = insert(c, "INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn) VALUES ('p-two', 1, 1, 1, 0)");
            for (Object[] revision : new Object[][]{{page1, 1, "/main/boards/1"}, {page1, 2, "/main/boards/2"}, {page2, 1, "/main/boards/3"}}) {
                insert(c, "INSERT INTO site_page_revision (page_id, revision_no, title, content, create_date) VALUES (?, ?, 't', ?, NOW(6))",
                        revision[0], revision[1], "<p>" + link((String) revision[2], "r") + "</p>");
            }
        }

        assertThat(flyway("10").migrate().migrationsExecuted).isEqualTo(1);

        try (Connection c = open()) {
            assertThat(one(c, "SELECT success FROM flyway_schema_history WHERE version = '10'")).isEqualTo("1");
            assertThat(menuUrl(c, menuPlain)).isEqualTo("/main/boards/board-12");
            assertThat(menuUrl(c, menuSub)).isEqualTo("/main/boards/board-12/posts/3/attachments/4?page=2#x");
            assertThat(menuUrl(c, menuLong)).isEqualTo("/main/boards/board-123");
            assertThat(menuUrl(c, menuPlus)).isEqualTo("/main/boards/board-12");
            assertThat(menuUrl(c, menuZero)).isEqualTo("/main/boards/board-12?x=1");
            assertThat(menuUrl(c, menuWide)).isEqualTo("/main/boards/board-12");
            assertThat(menuUrl(c, menuArabic)).isEqualTo("/main/boards/board-12/posts/1");
            assertThat(menuUrl(c, menuDisabledSite)).as("비활성 공개 사이트도 치환").isEqualTo("/news/boards/board-5");
            assertThat(menuUrl(c, menuNoSite)).as("공개 사이트 키가 아님").isEqualTo("/old/boards/12");
            assertThat(menuUrl(c, menuOverflow)).as("long 범위 초과").isEqualTo("/main/boards/9223372036854775808");
            assertThat(menuUrl(c, menuSlug)).isEqualTo("/main/boards/board-3");
            assertThat(menuUrl(c, menuQueryParam)).as("쿼리 매개변수 안의 경로").isEqualTo("/main/pages/a?next=/main/boards/12");
            assertThat(menuUrl(c, menuAdmin)).as("관리자 사이트 메뉴는 대상 아님").isEqualTo("/main/boards/12");
            assertThat(menuUrl(c, menuPrefix)).as("id 뒤가 경계가 아님").isEqualTo("/main/boards/12x");

            assertThat(one(c, "SELECT CONCAT(link_url, '|', version) FROM banner WHERE id = " + bannerLink)).isEqualTo("/main/boards/board-7|2");
            assertThat(one(c, "SELECT CONCAT(link_url, '|', version) FROM banner WHERE id = " + bannerExternal))
                    .isEqualTo("https://example.com/main/boards/7|1");
            assertThat(one(c, "SELECT CONCAT(link_url, '|', version) FROM banner WHERE id = " + bannerProtocolRelative))
                    .isEqualTo("//host/main/boards/7|1");

            assertThat(one(c, "SELECT CONCAT(link_url, '|', revision) FROM popup WHERE id = " + popupImage)).isEqualTo("/main/boards/board-7|1");
            assertThat(one(c, "SELECT CONCAT(link_url, '|', revision, '|', content_html) FROM popup WHERE id = " + popupHtml))
                    .as("링크·본문이 함께 바뀌어도 revision은 한 번만").isEqualTo("/main/boards/board-8|1|<p>" + link("/main/boards/board-7", "x") + "</p>");
            assertThat(one(c, "SELECT CONCAT(link_url, '|', revision) FROM popup WHERE id = " + popupOther)).isEqualTo("/main/faq|0");

            String expectedPost = "<p>글 /main/boards/7 " + link("/main/boards/board-7/posts/1?a=1&amp;b=2", "링크") + " "
                    + "<a href=\"https://x.test/main/boards/7\" " + REL + " target=\"_blank\">외부</a> "
                    + link("/main/boards/board-3", "슬러그") + " " + link("/main/boards/board-70", "칠십") + "</p><p><img src=\"/content-images/7\"></p>";
            String migratedPost = one(c, "SELECT content FROM post WHERE id = " + post);
            assertThat(migratedPost).as("삭제된 글도, href만, 본문 텍스트·외부·img는 그대로").isEqualTo(expectedPost);
            assertThat(HtmlContentSanitizer.sanitize(migratedPost).html()).as("치환 결과는 지금 sanitizer의 고정점").isEqualTo(migratedPost);

            assertThat(one(c, "SELECT answer_html FROM faq WHERE id = " + faqId)).isEqualTo("<p>" + link("/main/boards/board-9#q", "답") + "</p>");
            assertThat(rows(c, "SELECT page_id, revision_no, content FROM site_page_revision ORDER BY page_id, revision_no"))
                    .as("같은 페이지의 여러 개정, 다른 페이지의 같은 개정 번호 모두 독립적으로")
                    .containsExactly(
                            page1 + "|1|<p>" + link("/main/boards/board-1", "r") + "</p>",
                            page1 + "|2|<p>" + link("/main/boards/board-2", "r") + "</p>",
                            page2 + "|1|<p>" + link("/main/boards/board-3", "r") + "</p>");
            assertThat(rows(c, "SELECT version, current_revision_no FROM site_page ORDER BY id")).as("페이지 version·개정 번호 불변")
                    .containsExactly("3|2", "1|1");
        }
    }

    @Test
    @DisplayName("V10: 치환하면 정확히 컬럼 길이(255)인 메뉴는 치환되고, 1 넘는 행이 있으면 전체 롤백 → 실패 이력 → 수정 → repair → 재실행 성공")
    void lengthOverflow_failsFastThenRepairs() throws Exception {
        long fits, overflow, other;
        try (Connection c = open()) {
            // board-12는 12보다 6자 길다 — 249자 → 255자(맞음), 250자 → 256자(초과)
            fits = menu(c, 2, pad("/main/boards/12?q=", 249));
            overflow = menu(c, 2, pad("/main/boards/12?q=", 250));
            other = menu(c, 2, "/main/boards/5");
        }

        assertThatThrownBy(() -> flyway("10").migrate()).hasStackTraceContaining("menu#" + overflow);

        try (Connection c = open()) {
            assertThat(menuUrl(c, other)).as("전체 롤백 — 다른 행도 그대로").isEqualTo("/main/boards/5");
            assertThat(menuUrl(c, fits)).isEqualTo(pad("/main/boards/12?q=", 249));
            assertThat(one(c, "SELECT success FROM flyway_schema_history WHERE version = '10'")).as("실패 이력이 남는다").isEqualTo("0");
            // 운영자 조치 — 쿼리를 줄인다
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE menu SET menu_url = '/main/boards/12' WHERE menu_no = " + overflow);
            }
        }

        Flyway flyway = flyway("10");
        flyway.repair();
        flyway.migrate();

        try (Connection c = open()) {
            assertThat(one(c, "SELECT success FROM flyway_schema_history WHERE version = '10'")).isEqualTo("1");
            assertThat(menuUrl(c, fits)).hasSize(255).startsWith("/main/boards/board-12?q=");
            assertThat(menuUrl(c, overflow)).isEqualTo("/main/boards/board-12");
            assertThat(menuUrl(c, other)).isEqualTo("/main/boards/board-5");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/main/boards/%31%32", "/main/%62oards/12", "/main/%62%6F%61%72%64%73/12", "/main/boards/./12",
            "/main/x/../boards/12", "/main/boards/%2e/12"})
    @DisplayName("V10: 지금 열리는 인코딩·점 세그먼트 변형 링크가 배너에 있으면(/boards/ 필터에 안 걸려도) 실패하고 아무것도 바뀌지 않는다")
    void encodedVariant_failsFast(String variant) throws Exception {
        long bad, other;
        try (Connection c = open()) {
            bad = banner(c, variant);
            other = banner(c, "/main/boards/5");
        }

        assertThatThrownBy(() -> flyway("10").migrate()).hasStackTraceContaining("banner#" + bad);

        try (Connection c = open()) {
            assertThat(one(c, "SELECT CONCAT(link_url, '|', version) FROM banner WHERE id = " + other)).isEqualTo("/main/boards/5|1");
            assertThat(one(c, "SELECT link_url FROM banner WHERE id = " + bad)).isEqualTo(variant);
        }
    }

    @Test
    @DisplayName("V10: FAQ 답변이 치환 후 정확히 30,000바이트면 치환되고 30,001바이트면 실패한다")
    void bodyCap() throws Exception {
        String fitsLink = link("/main/boards/1", "a");
        long fits;
        try (Connection c = open()) {
            fits = faq(c, padBody(fitsLink, 30_000 - 6));
        }
        flyway("10").migrate();
        try (Connection c = open()) {
            String answer = one(c, "SELECT answer_html FROM faq WHERE id = " + fits);
            assertThat(answer.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(30_000);
            assertThat(answer).contains("/main/boards/board-1");
        }
    }

    @Test
    @DisplayName("V10: FAQ 답변이 치환 후 상한을 1바이트 넘으면 실패하고 롤백된다")
    void bodyCapOverflow() throws Exception {
        long overflow;
        try (Connection c = open()) {
            overflow = faq(c, padBody(link("/main/boards/1", "a"), 30_000 - 5));
        }
        assertThatThrownBy(() -> flyway("10").migrate()).hasStackTraceContaining("faq#" + overflow);
        try (Connection c = open()) {
            assertThat(one(c, "SELECT answer_html FROM faq WHERE id = " + overflow)).contains("/main/boards/1\"");
        }
    }

    /** {@code prefix} 뒤를 'x'로 채워 정확히 {@code length}자로. */
    private static String pad(String prefix, int length) {
        return prefix + "x".repeat(length - prefix.length());
    }

    /** 링크 하나를 담은 본문을 앞 문단 글자로 채워 정확히 {@code bytes}바이트로(ASCII). */
    private static String padBody(String linkHtml, int bytes) {
        String tail = "<p>" + linkHtml + "</p>";
        String head = "<p></p>";
        return "<p>" + "x".repeat(bytes - tail.length() - head.length()) + "</p>" + tail;
    }
}
