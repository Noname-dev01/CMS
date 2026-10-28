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
 * V11(저장된 공지 상세 링크 → 공지 게시판의 게시글 링크 치환, Java 마이그레이션)의 업그레이드 경로 시험(PLAN-board-definition.md "PR 4b 상세 설계" D5).
 * V10까지 적용한 DB에 링크가 든 행을 넣고 V11을 적용해 치환 대상(그 사이트의 해석 공지 게시판 소속 글만)·비대상·버전 증가·정규형 경로만 치환·
 * fail-fast(전체 롤백 → 실패 이력 → 수정 → repair → 재실행)를 확인한다. 본문 픽스처는 {@code BoardSlugLinkUpgradeTest}와 같은 저장 모양을 손으로 쓴다
 * (지금 sanitizer는 숫자 공지 {@code href}를 지운다). 치환 결과는 지금 sanitizer의 고정점이어야 한다.
 *
 * <p>첫 실배포 직전 마지막 합치기 때 V11은 빈 DB에 할 일이 없어 삭제되고 이 시험도 함께 지운다(docs/migration-guide.md "시험 정책").
 */
class NoticeLinkUpgradeTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "notice_link_upgrade_test";
    private static final String REL = "rel=\"noopener noreferrer nofollow\"";
    /** V2 시드 공통 공지 게시판·기본 공개 사이트. */
    private static final long COMMON_NOTICE = 1;
    private static final long MAIN_SITE = 2;

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
    void createSchemaAtV10() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement s = root.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            s.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }
        flyway("10").migrate();
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

    private static long imagePopup(Connection c, String url) throws Exception {
        return insert(c, "INSERT INTO popup (title, popup_type, pos_x, pos_y, width, use_yn, link_url) VALUES ('p', 'IMAGE', 0, 0, 300, 1, ?)", url);
    }

    private static long post(Connection c, long boardId, boolean useYn, boolean deleted) throws Exception {
        return insert(c, "INSERT INTO post (board_id, title, content, use_yn, deleted, author_id) VALUES (?, 't', '<p>x</p>', ?, ?, 'admin01')",
                boardId, useYn, deleted);
    }

    private static long faq(Connection c, String answer) throws Exception {
        long category = insert(c, "INSERT INTO faq_category (name, ord, use_yn) VALUES ('c', 0, 1)");
        return insert(c, "INSERT INTO faq (category_id, question, answer_html, ord, use_yn) VALUES (?, 'q', ?, 0, 1)", category, answer);
    }

    private static String link(String href, String text) {
        return "<a " + REL + " href=\"" + href + "\">" + text + "</a>";
    }

    @Test
    @DisplayName("V11: 그 사이트의 해석 공지 게시판 소속 글 링크만 게시판 주소로 치환하고(삭제·미노출 글 포함) 나머지는 그대로, 배너 version·팝업 revision은 행당 +1")
    void upgrade_replacesNoticeLinks() throws Exception {
        long notice, hidden, deleted, general, common, alphaPost, alphaBoard;
        long mPlain, mSub, mPlus, mWide, mHidden, mDeleted, mGeneral, mMissing, mList, mListQuery, mOldSite, mAlpha, mAlphaCommon, mMainAlpha,
                mAdmin, mBoundary, mQueryParam, mDotOther;
        long bLink, bExternal, bProtocolRelative, popupImage, popupHtml, postId, faqId, page1, page2;
        String postBody;
        try (Connection c = open()) {
            long alphaSite = insert(c, "INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, default_yn)"
                    + " VALUES ('alpha', '알파', 'PUBLIC', 1, 1, 0)");
            alphaBoard = insert(c, "INSERT INTO board (name, public_yn, attachment_yn, deleted, board_key, site_id, slug) VALUES ('알파 공지', 1, 1, 0, ?, ?, 'alpha-news')",
                    "NOTICE:" + alphaSite, alphaSite);
            long generalBoard = insert(c, "INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('일반', 1, 1, 0)");
            notice = post(c, COMMON_NOTICE, true, false);
            hidden = post(c, COMMON_NOTICE, false, false);
            deleted = post(c, COMMON_NOTICE, true, true);
            general = post(c, generalBoard, true, false);
            common = notice;
            alphaPost = post(c, alphaBoard, true, false);

            mPlain = menu(c, MAIN_SITE, "/main/notices/" + notice);
            mSub = menu(c, MAIN_SITE, "/main/notices/" + notice + "/attachments/9?page=2#x");
            mPlus = menu(c, MAIN_SITE, "/main/notices/+" + notice);
            mWide = menu(c, MAIN_SITE, "/main/notices/" + toFullWidth(notice));
            mHidden = menu(c, MAIN_SITE, "/main/notices/" + hidden);
            mDeleted = menu(c, MAIN_SITE, "/main/notices/" + deleted);
            mGeneral = menu(c, MAIN_SITE, "/main/notices/" + general);
            mMissing = menu(c, MAIN_SITE, "/main/notices/999999");
            mList = menu(c, MAIN_SITE, "/main/notices");
            mListQuery = menu(c, MAIN_SITE, "/main/notices?page=2");
            mOldSite = menu(c, MAIN_SITE, "/old/notices/" + notice);
            mAlpha = menu(c, alphaSite, "/alpha/notices/" + alphaPost);
            mAlphaCommon = menu(c, alphaSite, "/alpha/notices/" + common);
            mMainAlpha = menu(c, MAIN_SITE, "/main/notices/" + alphaPost);
            mAdmin = menu(c, 1, "/main/notices/" + notice);
            mBoundary = menu(c, MAIN_SITE, "/main/notices/" + notice + "x");
            mQueryParam = menu(c, MAIN_SITE, "/main/pages/a?next=/main/notices/" + notice);
            mDotOther = menu(c, MAIN_SITE, "/main/notices/./" + general);

            bLink = banner(c, "/main/notices/" + notice);
            bExternal = banner(c, "https://example.com/main/notices/" + notice);
            bProtocolRelative = banner(c, "//host/main/notices/" + notice);
            popupImage = imagePopup(c, "/main/notices/" + notice);
            popupHtml = insert(c, "INSERT INTO popup (title, popup_type, pos_x, pos_y, width, use_yn, link_url, content_html)"
                    + " VALUES ('p', 'HTML', 0, 0, 300, 1, ?, ?)", "/main/notices/" + hidden, "<p>" + link("/main/notices/" + notice, "x") + "</p>");

            postBody = "<p>글 /main/notices/" + notice + " " + link("/main/notices/" + notice + "?a=1&amp;b=2", "링크") + " "
                    + "<a href=\"https://x.test/main/notices/" + notice + "\" " + REL + " target=\"_blank\">외부</a> "
                    + link("/main/notices/" + general, "일반") + " " + link("/main/notices", "목록") + "</p><p><img src=\"/content-images/7\"></p>";
            postId = insert(c, "INSERT INTO post (board_id, title, content, use_yn, deleted, author_id) VALUES (?, 't', ?, 1, 0, 'admin01')",
                    generalBoard, postBody);
            faqId = faq(c, "<p>" + link("/alpha/notices/" + alphaPost + "#q", "답") + "</p>");
            page1 = insert(c, "INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn) VALUES ('p-one', 2, 3, 1, 0)");
            page2 = insert(c, "INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn) VALUES ('p-two', 1, 1, 1, 0)");
            for (Object[] revision : new Object[][]{{page1, 1, notice}, {page1, 2, hidden}, {page2, 1, deleted}}) {
                insert(c, "INSERT INTO site_page_revision (page_id, revision_no, title, content, create_date) VALUES (?, ?, 't', ?, NOW(6))",
                        revision[0], revision[1], "<p>" + link("/main/notices/" + revision[2], "r") + "</p>");
            }
        }

        assertThat(flyway("11").migrate().migrationsExecuted).isEqualTo(1);

        String commonPath = "/main/boards/board-" + COMMON_NOTICE + "/posts/";
        try (Connection c = open()) {
            assertThat(one(c, "SELECT success FROM flyway_schema_history WHERE version = '11'")).isEqualTo("1");
            assertThat(menuUrl(c, mPlain)).isEqualTo(commonPath + notice);
            assertThat(menuUrl(c, mSub)).isEqualTo(commonPath + notice + "/attachments/9?page=2#x");
            assertThat(menuUrl(c, mPlus)).as("비정규 id는 정규형으로").isEqualTo(commonPath + notice);
            assertThat(menuUrl(c, mWide)).isEqualTo(commonPath + notice);
            assertThat(menuUrl(c, mHidden)).as("미노출 공지 글도 치환").isEqualTo(commonPath + hidden);
            assertThat(menuUrl(c, mDeleted)).as("삭제된 공지 글도 치환").isEqualTo(commonPath + deleted);
            assertThat(menuUrl(c, mGeneral)).as("일반 게시판 글").isEqualTo("/main/notices/" + general);
            assertThat(menuUrl(c, mMissing)).as("없는 글").isEqualTo("/main/notices/999999");
            assertThat(menuUrl(c, mList)).isEqualTo("/main/notices");
            assertThat(menuUrl(c, mListQuery)).isEqualTo("/main/notices?page=2");
            assertThat(menuUrl(c, mOldSite)).as("공개 사이트 키가 아님").isEqualTo("/old/notices/" + notice);
            assertThat(menuUrl(c, mAlpha)).as("전용 공지 게시판 — 사용자 슬러그").isEqualTo("/alpha/boards/alpha-news/posts/" + alphaPost);
            assertThat(menuUrl(c, mAlphaCommon)).as("전용 공지가 대체한 공통 공지 글").isEqualTo("/alpha/notices/" + common);
            assertThat(menuUrl(c, mMainAlpha)).as("다른 사이트의 전용 공지 글").isEqualTo("/main/notices/" + alphaPost);
            assertThat(menuUrl(c, mAdmin)).as("관리자 사이트 메뉴는 대상 아님").isEqualTo("/main/notices/" + notice);
            assertThat(menuUrl(c, mBoundary)).as("id 뒤가 경계가 아님").isEqualTo("/main/notices/" + notice + "x");
            assertThat(menuUrl(c, mQueryParam)).as("쿼리 매개변수 안의 경로").isEqualTo("/main/pages/a?next=/main/notices/" + notice);
            assertThat(menuUrl(c, mDotOther)).as("정규형이 아니고 목적지가 공지가 아님 — 원문 그대로").isEqualTo("/main/notices/./" + general);

            assertThat(one(c, "SELECT CONCAT(link_url, '|', version) FROM banner WHERE id = " + bLink)).isEqualTo(commonPath + notice + "|2");
            assertThat(one(c, "SELECT CONCAT(link_url, '|', version) FROM banner WHERE id = " + bExternal))
                    .isEqualTo("https://example.com/main/notices/" + notice + "|1");
            assertThat(one(c, "SELECT CONCAT(link_url, '|', version) FROM banner WHERE id = " + bProtocolRelative))
                    .isEqualTo("//host/main/notices/" + notice + "|1");

            assertThat(one(c, "SELECT CONCAT(link_url, '|', revision) FROM popup WHERE id = " + popupImage)).isEqualTo(commonPath + notice + "|1");
            assertThat(one(c, "SELECT CONCAT(link_url, '|', revision, '|', content_html) FROM popup WHERE id = " + popupHtml))
                    .as("링크·본문이 함께 바뀌어도 revision은 한 번만")
                    .isEqualTo(commonPath + hidden + "|1|<p>" + link(commonPath + notice, "x") + "</p>");

            String expectedPost = "<p>글 /main/notices/" + notice + " " + link(commonPath + notice + "?a=1&amp;b=2", "링크") + " "
                    + "<a href=\"https://x.test/main/notices/" + notice + "\" " + REL + " target=\"_blank\">외부</a> "
                    + link("/main/notices/" + general, "일반") + " " + link("/main/notices", "목록") + "</p><p><img src=\"/content-images/7\"></p>";
            String migratedPost = one(c, "SELECT content FROM post WHERE id = " + postId);
            assertThat(migratedPost).as("href만, 본문 텍스트·외부·일반 글·목록·img는 그대로").isEqualTo(expectedPost);
            String sanitized = HtmlContentSanitizer.sanitize(migratedPost).html();
            assertThat(sanitized).as("치환된 링크는 지금 sanitizer를 통과한다").contains("href=\"" + commonPath + notice + "?a=1&amp;b=2\"");

            assertThat(one(c, "SELECT answer_html FROM faq WHERE id = " + faqId))
                    .isEqualTo("<p>" + link("/alpha/boards/alpha-news/posts/" + alphaPost + "#q", "답") + "</p>");
            assertThat(HtmlContentSanitizer.sanitize(one(c, "SELECT answer_html FROM faq WHERE id = " + faqId)).html())
                    .as("치환 결과는 지금 sanitizer의 고정점").isEqualTo(one(c, "SELECT answer_html FROM faq WHERE id = " + faqId));
            assertThat(rows(c, "SELECT page_id, revision_no, content FROM site_page_revision ORDER BY page_id, revision_no"))
                    .as("같은 페이지의 여러 개정, 다른 페이지의 같은 개정 번호 모두 독립적으로")
                    .containsExactly(
                            page1 + "|1|<p>" + link(commonPath + notice, "r") + "</p>",
                            page1 + "|2|<p>" + link(commonPath + hidden, "r") + "</p>",
                            page2 + "|1|<p>" + link(commonPath + deleted, "r") + "</p>");
            assertThat(rows(c, "SELECT version, current_revision_no FROM site_page ORDER BY id")).as("페이지 version·개정 번호 불변")
                    .containsExactly("3|2", "1|1");
        }
    }

    @Test
    @DisplayName("V11: 치환하면 정확히 컬럼 길이(255)인 메뉴는 치환되고, 1 넘는 행이 있으면 전체 롤백 → 실패 이력 → 수정 → repair → 재실행 성공")
    void lengthOverflow_failsFastThenRepairs() throws Exception {
        long notice, fits, overflow, other;
        String prefix;
        try (Connection c = open()) {
            notice = post(c, COMMON_NOTICE, true, false);
            prefix = "/main/notices/" + notice + "?q=";
            // notices/{n} → boards/board-1/posts/{n}은 13자 길다 — 242자 → 255자(맞음), 243자 → 256자(초과)
            fits = menu(c, MAIN_SITE, pad(prefix, 242));
            overflow = menu(c, MAIN_SITE, pad(prefix, 243));
            other = menu(c, MAIN_SITE, "/main/notices/" + notice);
        }

        assertThatThrownBy(() -> flyway("11").migrate()).hasStackTraceContaining("menu#" + overflow);

        try (Connection c = open()) {
            assertThat(menuUrl(c, other)).as("전체 롤백 — 다른 행도 그대로").isEqualTo("/main/notices/" + notice);
            assertThat(menuUrl(c, fits)).isEqualTo(pad(prefix, 242));
            assertThat(one(c, "SELECT success FROM flyway_schema_history WHERE version = '11'")).as("실패 이력이 남는다").isEqualTo("0");
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE menu SET menu_url = '/main/notices/" + notice + "' WHERE menu_no = " + overflow);
            }
        }

        Flyway flyway = flyway("11");
        flyway.repair();
        flyway.migrate();

        try (Connection c = open()) {
            assertThat(one(c, "SELECT success FROM flyway_schema_history WHERE version = '11'")).isEqualTo("1");
            assertThat(menuUrl(c, fits)).hasSize(255).startsWith("/main/boards/board-1/posts/" + notice + "?q=");
            assertThat(menuUrl(c, overflow)).isEqualTo("/main/boards/board-1/posts/" + notice);
            assertThat(menuUrl(c, other)).isEqualTo("/main/boards/board-1/posts/" + notice);
        }
    }

    /**
     * 지금 열리는 공지 링크의 변형 — {@code {N}}은 해석 공지 게시판 소속 글, {@code {G}}는 일반 게시판 글, {@code {E}}는 {@code {N}}의 숫자를 퍼센트 인코딩한 값.
     * 마지막 두 개는 치환 경로를 벗어나는 접미부 {@code ..}(리뷰 R1-1) — 정규형이 아니라 치환되지 않고 남아 사후 검사가 잡는다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/main/notices/{E}", "/main/%6Eotices/{N}", "/main/notices/./{N}", "/main/x/../notices/{N}",
            "/main/notices/{N}/../../notices/{N}", "/main/notices/{G}/../../notices/{N}"})
    @DisplayName("V11: 지금 열리는 인코딩·점 세그먼트 변형 공지 링크가 배너·팝업에 있으면 실패하고 아무것도 바뀌지 않는다")
    void openVariant_failsFast(String template) throws Exception {
        long badBanner, badPopup, other;
        String variant;
        try (Connection c = open()) {
            long generalBoard = insert(c, "INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('일반', 1, 1, 0)");
            long notice = post(c, COMMON_NOTICE, true, false);
            long general = post(c, generalBoard, true, false);
            variant = template.replace("{N}", String.valueOf(notice)).replace("{G}", String.valueOf(general))
                    .replace("{E}", percentEncode(String.valueOf(notice)));
            badBanner = banner(c, variant);
            badPopup = imagePopup(c, variant);
            other = banner(c, "/main/notices/" + notice);
        }

        assertThatThrownBy(() -> flyway("11").migrate())
                .hasStackTraceContaining("banner#" + badBanner).hasStackTraceContaining("popup#" + badPopup);

        try (Connection c = open()) {
            assertThat(one(c, "SELECT version FROM banner WHERE id = " + other)).as("전체 롤백").isEqualTo("1");
            assertThat(one(c, "SELECT link_url FROM banner WHERE id = " + badBanner)).isEqualTo(variant);
            assertThat(one(c, "SELECT CONCAT(link_url, '|', revision) FROM popup WHERE id = " + badPopup)).isEqualTo(variant + "|0");
        }
    }

    @Test
    @DisplayName("V11: FAQ 답변이 치환 후 정확히 30,000바이트면 치환되고 1바이트 넘으면 실패한다")
    void bodyCap() throws Exception {
        long notice, fits;
        try (Connection c = open()) {
            notice = post(c, COMMON_NOTICE, true, false);
            fits = faq(c, padBody(link("/main/notices/" + notice, "a"), 30_000 - 13));
        }
        flyway("11").migrate();
        try (Connection c = open()) {
            String answer = one(c, "SELECT answer_html FROM faq WHERE id = " + fits);
            assertThat(answer.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(30_000);
            assertThat(answer).contains("/main/boards/board-1/posts/" + notice);
        }
    }

    @Test
    @DisplayName("V11: FAQ 답변이 치환 후 상한을 1바이트 넘으면 실패하고 롤백된다")
    void bodyCapOverflow() throws Exception {
        long notice, overflow;
        try (Connection c = open()) {
            notice = post(c, COMMON_NOTICE, true, false);
            overflow = faq(c, padBody(link("/main/notices/" + notice, "a"), 30_000 - 12));
        }
        assertThatThrownBy(() -> flyway("11").migrate()).hasStackTraceContaining("faq#" + overflow);
        try (Connection c = open()) {
            assertThat(one(c, "SELECT answer_html FROM faq WHERE id = " + overflow)).contains("/main/notices/" + notice + "\"");
        }
    }

    private static String toFullWidth(long id) {
        StringBuilder out = new StringBuilder();
        for (char ch : String.valueOf(id).toCharArray()) {
            out.append((char) ('０' + (ch - '0')));
        }
        return out.toString();
    }

    private static String percentEncode(String digits) {
        StringBuilder out = new StringBuilder();
        for (char ch : digits.toCharArray()) {
            out.append('%').append(Integer.toHexString(ch).toUpperCase(java.util.Locale.ROOT));
        }
        return out.toString();
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
