package db.migration;

import com.cms.common.web.BoardLinkPaths;
import com.cms.common.web.PathIds;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.jsoup.parser.Parser;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 저장된 숫자 게시판 링크 {@code /{공개 사이트 키}/boards/{id}…}를 슬러그 링크 {@code …/boards/board-{id}…}로 치환한다
 * (PLAN-board-definition.md "PR 4a 상세 설계" D6 — 이 프로젝트 첫 Java 마이그레이션).
 *
 * <ul>
 *   <li>대상: 공개 사이트 메뉴 {@code menu.menu_url}, {@code banner.link_url}(+{@code version}), {@code popup.link_url}·{@code content_html}
 *       (+{@code revision} 행당 1), {@code post.content}, {@code site_page_revision.content}(모든 개정, 버전 불변), {@code faq.answer_html}.</li>
 *   <li>URL 컬럼은 값의 시작, 본문은 {@code <a>}의 {@code href} 값의 시작이 {@code /{키}/boards/{s}}이고 {@code s} 뒤가 끝·{@code /}·{@code ?}·{@code #}이며
 *       {@link PathIds#parse}가 읽을 때만 {@code s}를 {@code board-{id}}로 바꾼다(비정규 표기 {@code +12}·전각도 정규형으로). 게시판 존재 여부는 보지 않는다 —
 *       없는·삭제된 게시판은 치환 전후 모두 404이고 게시판 id는 재사용되지 않는다.</li>
 *   <li>fail-fast(전체 롤백): 치환 결과가 컬럼 길이·본문 상한을 넘는 행, 치환 뒤에도 {@link BoardLinkPaths}로 판정한 공개 사이트 키의 숫자 게시판 경로가
 *       남은 행(인코딩·점 세그먼트 변형 — 지금 열리는 링크라 남기면 배포 순간 404). URL 컬럼의 이 검사는 후보 필터 없이 모든 행에 한다.</li>
 * </ul>
 *
 * <p>JDBC만 쓴다(엔티티·스프링 빈 금지 — 이후 PR에서 바뀐다). 앱 코드는 {@link PathIds}·{@link BoardLinkPaths}만 부르므로 그 규칙을 바꾸면 아직 V10을
 * 적용하지 않은 DB의 결과가 달라진다 — 바꿀 때 {@code BoardSlugLinkUpgradeTest}를 함께 본다. 상한값은 이 마이그레이션 시점 값으로 고정했다.
 * 실패하면 MariaDB에서 실패 이력이 남으므로 원인 행을 고친 뒤 {@code flyway repair} → 재기동한다({@code docs/migration-guide.md} V10).
 */
public class V10__replace_board_links_with_slugs extends BaseJavaMigration {

    private static final int MENU_URL_MAX_CHARS = 255;
    private static final int LINK_URL_MAX_CHARS = 500;
    private static final int POST_MAX_BYTES = 200_000;
    private static final int PAGE_MAX_BYTES = 500_000;
    private static final int POPUP_MAX_BYTES = 50_000;
    private static final int FAQ_MAX_BYTES = 30_000;

    private static final int PAGE_SIZE = 200;
    private static final int MAX_REPORTED = 20;
    private static final String CANDIDATE = "LIKE '%/boards/%'";

    /** sanitizer 출력의 {@code <a …>} 태그 — 속성 값은 큰따옴표이고 안에 {@code "}가 없다(시험이 sanitizer 왕복으로 고정). */
    private static final Pattern A_TAG = Pattern.compile("<a(?:\\s+[^\\s=>\"/]+(?:=\"[^\"]*\")?)*\\s*/?>");
    private static final Pattern HREF = Pattern.compile("(\\shref=\")([^\"]*)(\")");

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Set<String> siteKeys = publicSiteKeys(connection);
        List<String> failures = new ArrayList<>();

        replaceUrls(connection, siteKeys, failures, "menu", "menu_no", "menu_url", MENU_URL_MAX_CHARS,
                "site_id IN (SELECT id FROM site WHERE site_type = 'PUBLIC')", null);
        replaceUrls(connection, siteKeys, failures, "banner", "id", "link_url", LINK_URL_MAX_CHARS, null, "version = version + 1");
        replacePopups(connection, siteKeys, failures);
        replaceBodies(connection, siteKeys, failures, "post", "content", POST_MAX_BYTES);
        replaceBodies(connection, siteKeys, failures, "faq", "answer_html", FAQ_MAX_BYTES);
        replacePageRevisions(connection, siteKeys, failures);

        // 치환 뒤 남은 "지금 열리는" 숫자 게시판 링크 — 후보 필터(/boards/)를 쓰지 않고 URL 컬럼의 모든 행을 본다(%62oards 같은 인코딩 변형)
        checkRemainingUrls(connection, siteKeys, failures, "menu", "menu_no", "menu_url",
                "site_id IN (SELECT id FROM site WHERE site_type = 'PUBLIC')");
        checkRemainingUrls(connection, siteKeys, failures, "banner", "id", "link_url", null);
        checkRemainingUrls(connection, siteKeys, failures, "popup", "id", "link_url", null);

        if (!failures.isEmpty()) {
            List<String> shown = failures.subList(0, Math.min(MAX_REPORTED, failures.size()));
            throw new FlywayException("V10: 게시판 링크를 슬러그 주소로 치환할 수 없는 행이 " + failures.size() + "개 있습니다 — "
                    + String.join(", ", shown) + (failures.size() > MAX_REPORTED ? " 외" : "")
                    + ". 이전 버전에서 그 행을 고친 뒤 flyway repair → 재기동하세요(docs/migration-guide.md V10).");
        }
    }

    private static Set<String> publicSiteKeys(Connection connection) throws SQLException {
        Set<String> keys = new HashSet<>();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT site_key FROM site WHERE site_type = 'PUBLIC'")) {
            while (rs.next()) {
                keys.add(rs.getString(1));
            }
        }
        return keys;
    }

    /** URL 컬럼: 바뀐 행만 UPDATE(+ 함께 올릴 버전). 길이는 문자(코드 포인트) 기준 — DB varchar와 같다. */
    private static void replaceUrls(Connection connection, Set<String> siteKeys, List<String> failures, String table, String key,
                                    String column, int maxChars, String scope, String bump) throws SQLException {
        String where = column + " " + CANDIDATE + (scope == null ? "" : " AND " + scope);
        List<Object[]> changes = new ArrayList<>();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + key + ", " + column + " FROM " + table + " WHERE " + where + " ORDER BY " + key)) {
            while (rs.next()) {
                long id = rs.getLong(1);
                String value = rs.getString(2);
                String replaced = rewriteUrl(value, siteKeys);
                if (replaced.equals(value)) {
                    continue;
                }
                if (replaced.codePointCount(0, replaced.length()) > maxChars) {
                    failures.add(table + "#" + id + "(길이 초과)");
                    continue;
                }
                changes.add(new Object[]{id, replaced});
            }
        }
        String sql = "UPDATE " + table + " SET " + column + " = ?" + (bump == null ? "" : ", " + bump) + " WHERE " + key + " = ?";
        try (PreparedStatement update = connection.prepareStatement(sql)) {
            for (Object[] change : changes) {
                update.setString(1, (String) change[1]);
                update.setLong(2, (Long) change[0]);
                update.executeUpdate();
            }
        }
    }

    /** 팝업: 링크와 HTML 본문을 한 행에서 함께 치환하고 {@code revision}은 행당 한 번만 올린다. */
    private static void replacePopups(Connection connection, Set<String> siteKeys, List<String> failures) throws SQLException {
        List<Object[]> changes = new ArrayList<>();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, link_url, content_html FROM popup WHERE link_url " + CANDIDATE
                     + " OR content_html " + CANDIDATE + " ORDER BY id")) {
            while (rs.next()) {
                long id = rs.getLong(1);
                String link = rs.getString(2);
                String html = rs.getString(3);
                String newLink = link == null ? null : rewriteUrl(link, siteKeys);
                String newHtml = html == null ? null : rewriteHtml(html, siteKeys);
                boolean linkChanged = link != null && !newLink.equals(link);
                boolean htmlChanged = html != null && !newHtml.equals(html);
                if (!linkChanged && !htmlChanged) {
                    continue;
                }
                boolean ok = true;
                if (linkChanged && newLink.codePointCount(0, newLink.length()) > LINK_URL_MAX_CHARS) {
                    failures.add("popup#" + id + "(링크 길이 초과)");
                    ok = false;
                }
                if (htmlChanged && newHtml.getBytes(StandardCharsets.UTF_8).length > POPUP_MAX_BYTES) {
                    failures.add("popup#" + id + "(본문 상한 초과)");
                    ok = false;
                }
                if (htmlChanged && hasRemainingHref(newHtml, siteKeys)) {
                    failures.add("popup#" + id + "(본문 링크 변형)");
                    ok = false;
                }
                if (ok) {
                    changes.add(new Object[]{id, newLink, newHtml});
                }
            }
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE popup SET link_url = ?, content_html = ?, revision = revision + 1 WHERE id = ?")) {
            for (Object[] change : changes) {
                update.setString(1, (String) change[1]);
                update.setString(2, (String) change[2]);
                update.setLong(3, (Long) change[0]);
                update.executeUpdate();
            }
        }
    }

    /** 단일 키({@code id}) 본문 테이블 — 큰 MEDIUMTEXT를 한꺼번에 들지 않게 id 키셋으로 나눠 읽는다. */
    private static void replaceBodies(Connection connection, Set<String> siteKeys, List<String> failures, String table, String column,
                                      int maxBytes) throws SQLException {
        long after = 0;
        try (PreparedStatement select = connection.prepareStatement("SELECT id, " + column + " FROM " + table
                + " WHERE id > ? AND " + column + " " + CANDIDATE + " ORDER BY id LIMIT " + PAGE_SIZE);
             PreparedStatement update = connection.prepareStatement("UPDATE " + table + " SET " + column + " = ? WHERE id = ?")) {
            while (true) {
                select.setLong(1, after);
                List<Object[]> changes = new ArrayList<>();
                int read = 0;
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        read++;
                        long id = rs.getLong(1);
                        after = id;
                        String html = rs.getString(2);
                        String replaced = rewriteHtml(html, siteKeys);
                        if (hasRemainingHref(replaced, siteKeys)) {
                            failures.add(table + "#" + id + "(본문 링크 변형)");
                            continue;
                        }
                        if (replaced.equals(html)) {
                            continue;
                        }
                        if (replaced.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                            failures.add(table + "#" + id + "(본문 상한 초과)");
                            continue;
                        }
                        changes.add(new Object[]{id, replaced});
                    }
                }
                for (Object[] change : changes) {
                    update.setString(1, (String) change[1]);
                    update.setLong(2, (Long) change[0]);
                    update.executeUpdate();
                }
                if (read < PAGE_SIZE) {
                    return;
                }
            }
        }
    }

    /** 정적 페이지 개정 — {@code id}가 없고 복합 키 {@code (page_id, revision_no)}다. 개정 번호·페이지 {@code version}은 올리지 않는다. */
    private static void replacePageRevisions(Connection connection, Set<String> siteKeys, List<String> failures) throws SQLException {
        long afterPage = 0;
        int afterRevision = 0;
        try (PreparedStatement select = connection.prepareStatement("SELECT page_id, revision_no, content FROM site_page_revision"
                + " WHERE (page_id, revision_no) > (?, ?) AND content " + CANDIDATE + " ORDER BY page_id, revision_no LIMIT " + PAGE_SIZE);
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE site_page_revision SET content = ? WHERE page_id = ? AND revision_no = ?")) {
            while (true) {
                select.setLong(1, afterPage);
                select.setInt(2, afterRevision);
                List<Object[]> changes = new ArrayList<>();
                int read = 0;
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        read++;
                        long pageId = rs.getLong(1);
                        int revisionNo = rs.getInt(2);
                        afterPage = pageId;
                        afterRevision = revisionNo;
                        String html = rs.getString(3);
                        String replaced = rewriteHtml(html, siteKeys);
                        String label = "site_page_revision#" + pageId + "/" + revisionNo;
                        if (hasRemainingHref(replaced, siteKeys)) {
                            failures.add(label + "(본문 링크 변형)");
                            continue;
                        }
                        if (replaced.equals(html)) {
                            continue;
                        }
                        if (replaced.getBytes(StandardCharsets.UTF_8).length > PAGE_MAX_BYTES) {
                            failures.add(label + "(본문 상한 초과)");
                            continue;
                        }
                        changes.add(new Object[]{pageId, revisionNo, replaced});
                    }
                }
                for (Object[] change : changes) {
                    update.setString(1, (String) change[2]);
                    update.setLong(2, (Long) change[0]);
                    update.setInt(3, (Integer) change[1]);
                    update.executeUpdate();
                }
                if (read < PAGE_SIZE) {
                    return;
                }
            }
        }
    }

    private static void checkRemainingUrls(Connection connection, Set<String> siteKeys, List<String> failures, String table, String key,
                                           String column, String scope) throws SQLException {
        String where = column + " IS NOT NULL" + (scope == null ? "" : " AND " + scope);
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + key + ", " + column + " FROM " + table + " WHERE " + where + " ORDER BY " + key)) {
            while (rs.next()) {
                if (isOpenNumericBoardLink(rs.getString(2), siteKeys)) {
                    failures.add(table + "#" + rs.getLong(1) + "(링크 변형)");
                }
            }
        }
    }

    /** 지금 열리는 숫자 게시판 링크인가 — 디코딩·점 세그먼트 정리 뒤 사이트 키 자리가 공개 사이트 키. */
    private static boolean isOpenNumericBoardLink(String url, Set<String> siteKeys) {
        String site = BoardLinkPaths.numericBoardSiteSegment(url);
        return site != null && siteKeys.contains(site);
    }

    private static boolean hasRemainingHref(String html, Set<String> siteKeys) {
        Matcher tag = A_TAG.matcher(html);
        while (tag.find()) {
            Matcher href = HREF.matcher(tag.group());
            if (href.find() && isOpenNumericBoardLink(Parser.unescapeEntities(href.group(2), true), siteKeys)) {
                return true;
            }
        }
        return false;
    }

    /** {@code <a>} 태그의 {@code href} 값에만 {@link #rewriteUrl}을 적용한다(본문 텍스트·다른 속성·다른 태그는 그대로). */
    static String rewriteHtml(String html, Set<String> siteKeys) {
        Matcher tag = A_TAG.matcher(html);
        StringBuilder out = new StringBuilder(html.length() + 16);
        int last = 0;
        while (tag.find()) {
            String original = tag.group();
            Matcher href = HREF.matcher(original);
            String rewritten = original;
            if (href.find()) {
                String value = href.group(2);
                String replaced = rewriteUrl(value, siteKeys);
                if (!replaced.equals(value)) {
                    rewritten = original.substring(0, href.start(2)) + replaced + original.substring(href.end(2));
                }
            }
            out.append(html, last, tag.start()).append(rewritten);
            last = tag.end();
        }
        return out.append(html, last, html.length()).toString();
    }

    /** 값이 {@code /{공개 사이트 키}/boards/{id}}로 시작하고 id 뒤가 끝·{@code /}·{@code ?}·{@code #}이면 그 id 세그먼트를 {@code board-{id}}로. */
    static String rewriteUrl(String url, Set<String> siteKeys) {
        if (!url.startsWith("/")) {
            return url;
        }
        int keyEnd = url.indexOf('/', 1);
        if (keyEnd < 0 || !siteKeys.contains(url.substring(1, keyEnd)) || !url.startsWith("/boards/", keyEnd)) {
            return url;
        }
        int idStart = keyEnd + "/boards/".length();
        int idEnd = idStart;
        while (idEnd < url.length() && "/?#".indexOf(url.charAt(idEnd)) < 0) {
            idEnd++;
        }
        Long id = PathIds.parse(url.substring(idStart, idEnd));
        if (id == null) {
            return url;
        }
        return url.substring(0, idStart) + "board-" + id + url.substring(idEnd);
    }
}
