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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 저장된 공지 상세 링크 {@code /{공개 사이트 키}/notices/{postId}…}를 공지 게시판의 게시글 링크 {@code /{키}/boards/{슬러그}/posts/{postId}…}로 치환한다
 * (PLAN-board-definition.md "PR 4b 상세 설계" D5 — 공개 공지 상세·첨부 경로가 사라진다).
 *
 * <ul>
 *   <li>대상은 V10과 같다: 공개 사이트 메뉴 {@code menu.menu_url}, {@code banner.link_url}(+{@code version}), {@code popup.link_url}·{@code content_html}
 *       (+{@code revision} 행당 1), {@code post.content}, {@code site_page_revision.content}(모든 개정, 버전 불변), {@code faq.answer_html}.</li>
 *   <li>URL 컬럼은 값의 시작, 본문은 {@code <a>}의 {@code href} 값의 시작이 {@code /{키}/notices/{s}}이고 {@code s} 뒤가 끝·{@code /}·{@code ?}·{@code #}이며
 *       {@link PathIds#parse}가 읽은 게시글이 <b>그 사이트에서 해석되는 공지 게시판</b>(전용 {@code NOTICE:{siteId}} → 없으면 공통 {@code NOTICE}) 소속일 때만
 *       바꾼다 — 지금 그 주소로 열리는(또는 공개되면 열릴) 글만. 게시글의 삭제·노출 상태는 보지 않는다(삭제된 글은 치환 전후 모두 404, id는 재사용되지 않는다).</li>
 *   <li><b>경로가 정규형일 때만</b>(경로 부분에 {@code %}·{@code .}·{@code ..} 세그먼트 없음) 치환한다. 두 세그먼트를 네 세그먼트로 늘리므로 뒤에 붙은
 *       {@code ..}가 다른 곳으로 이동하기 때문이다({@code /main/notices/5/../../notices/6}). 정규형이 아닌 값은 그대로 두고 사후 검사가 판정한다.</li>
 *   <li>fail-fast(전체 롤백): 치환 결과가 컬럼 길이·본문 상한을 넘는 행, 치환 뒤에도 {@link BoardLinkPaths#numericNoticeTarget}으로 정규화한 목적지가
 *       "지금 열리는" 공지 링크인 행(인코딩·점 세그먼트 변형). URL 컬럼의 이 검사는 후보 필터 없이 모든 행에 한다.</li>
 * </ul>
 *
 * <p>JDBC만 쓴다(엔티티·스프링 빈 금지). 앱 코드는 {@link PathIds}·{@link BoardLinkPaths}만 부르므로 그 규칙을 바꾸면 아직 V11을 적용하지 않은 DB의
 * 결과가 달라진다 — 바꿀 때 {@code NoticeLinkUpgradeTest}를 함께 본다. 상한값은 이 마이그레이션 시점 값으로 고정했다. 실패하면 MariaDB에서 실패 이력이
 * 남으므로 원인 행을 고친 뒤 {@code flyway repair} → 재기동한다({@code docs/migration-guide.md} V11).
 */
public class V11__replace_notice_links_with_board_links extends BaseJavaMigration {

    private static final int MENU_URL_MAX_CHARS = 255;
    private static final int LINK_URL_MAX_CHARS = 500;
    private static final int POST_MAX_BYTES = 200_000;
    private static final int PAGE_MAX_BYTES = 500_000;
    private static final int POPUP_MAX_BYTES = 50_000;
    private static final int FAQ_MAX_BYTES = 30_000;

    private static final int PAGE_SIZE = 200;
    private static final int MAX_REPORTED = 20;
    private static final String CANDIDATE = "LIKE '%/notices/%'";
    private static final String PUBLIC_MENU_SCOPE = "site_id IN (SELECT id FROM site WHERE site_type = 'PUBLIC')";

    /** sanitizer 출력의 {@code <a …>} 태그 — V10과 같은 가정(속성 값은 큰따옴표이고 안에 {@code "}가 없다). */
    private static final Pattern A_TAG = Pattern.compile("<a(?:\\s+[^\\s=>\"/]+(?:=\"[^\"]*\")?)*\\s*/?>");
    private static final Pattern HREF = Pattern.compile("(\\shref=\")([^\"]*)(\")");

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        NoticeLinks links = NoticeLinks.load(connection);
        List<String> failures = new ArrayList<>();

        replaceUrls(connection, links, failures, "menu", "menu_no", "menu_url", MENU_URL_MAX_CHARS, PUBLIC_MENU_SCOPE, null);
        replaceUrls(connection, links, failures, "banner", "id", "link_url", LINK_URL_MAX_CHARS, null, "version = version + 1");
        replacePopups(connection, links, failures);
        replaceBodies(connection, links, failures, "post", "content", POST_MAX_BYTES);
        replaceBodies(connection, links, failures, "faq", "answer_html", FAQ_MAX_BYTES);
        replacePageRevisions(connection, links, failures);

        // 치환 뒤 남은 "지금 열리는" 공지 링크 — 후보 필터(/notices/)를 쓰지 않고 URL 컬럼의 모든 행을 본다(%6Eotices 같은 인코딩 변형)
        checkRemainingUrls(connection, links, failures, "menu", "menu_no", "menu_url", PUBLIC_MENU_SCOPE);
        checkRemainingUrls(connection, links, failures, "banner", "id", "link_url", null);
        checkRemainingUrls(connection, links, failures, "popup", "id", "link_url", null);

        if (!failures.isEmpty()) {
            List<String> shown = failures.subList(0, Math.min(MAX_REPORTED, failures.size()));
            throw new FlywayException("V11: 공지 링크를 게시판 주소로 치환할 수 없는 행이 " + failures.size() + "개 있습니다 — "
                    + String.join(", ", shown) + (failures.size() > MAX_REPORTED ? " 외" : "")
                    + ". 이전 버전에서 그 행을 고친 뒤 flyway repair → 재기동하세요(docs/migration-guide.md V11).");
        }
    }

    private static void replaceUrls(Connection connection, NoticeLinks links, List<String> failures, String table, String key,
                                    String column, int maxChars, String scope, String bump) throws SQLException {
        String where = column + " " + CANDIDATE + (scope == null ? "" : " AND " + scope);
        List<Object[]> changes = new ArrayList<>();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + key + ", " + column + " FROM " + table + " WHERE " + where + " ORDER BY " + key)) {
            while (rs.next()) {
                long id = rs.getLong(1);
                String value = rs.getString(2);
                String replaced = links.rewriteUrl(value);
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
    private static void replacePopups(Connection connection, NoticeLinks links, List<String> failures) throws SQLException {
        List<Object[]> changes = new ArrayList<>();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, link_url, content_html FROM popup WHERE link_url " + CANDIDATE
                     + " OR content_html " + CANDIDATE + " ORDER BY id")) {
            while (rs.next()) {
                long id = rs.getLong(1);
                String link = rs.getString(2);
                String html = rs.getString(3);
                String newLink = link == null ? null : links.rewriteUrl(link);
                String newHtml = html == null ? null : links.rewriteHtml(html);
                boolean linkChanged = link != null && !newLink.equals(link);
                boolean htmlChanged = html != null && !newHtml.equals(html);
                if (html != null && links.hasOpenHref(newHtml)) {
                    failures.add("popup#" + id + "(본문 링크 변형)");
                    continue;
                }
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
    private static void replaceBodies(Connection connection, NoticeLinks links, List<String> failures, String table, String column,
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
                        String replaced = links.rewriteHtml(html);
                        if (links.hasOpenHref(replaced)) {
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
    private static void replacePageRevisions(Connection connection, NoticeLinks links, List<String> failures) throws SQLException {
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
                        String replaced = links.rewriteHtml(html);
                        String label = "site_page_revision#" + pageId + "/" + revisionNo;
                        if (links.hasOpenHref(replaced)) {
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

    private static void checkRemainingUrls(Connection connection, NoticeLinks links, List<String> failures, String table, String key,
                                           String column, String scope) throws SQLException {
        String where = column + " IS NOT NULL" + (scope == null ? "" : " AND " + scope);
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + key + ", " + column + " FROM " + table + " WHERE " + where + " ORDER BY " + key)) {
            while (rs.next()) {
                if (links.isOpenNoticeLink(rs.getString(2))) {
                    failures.add(table + "#" + rs.getLong(1) + "(링크 변형)");
                }
            }
        }
    }

    /**
     * 사이트 키 → 해석 공지 게시판(id·현재 슬러그)과 게시글 → 게시판 조회. 해석 규칙은 런타임 {@code BoardRepository.findNoticeBoardId}와 같다 —
     * 전용 {@code NOTICE:{siteId}}(미삭제)가 있으면 그것, 없으면 공통 {@code NOTICE}(미삭제). 게시글 소속은 링크마다 조회하고 캐시한다.
     */
    static final class NoticeLinks {

        private final Map<String, NoticeBoard> boardsBySiteKey;
        private final PreparedStatement postBoard;
        private final Map<Long, Optional<Long>> postBoardCache = new HashMap<>();

        private NoticeLinks(Map<String, NoticeBoard> boardsBySiteKey, PreparedStatement postBoard) {
            this.boardsBySiteKey = boardsBySiteKey;
            this.postBoard = postBoard;
        }

        static NoticeLinks load(Connection connection) throws SQLException {
            Map<String, Long> sites = new HashMap<>();
            try (Statement s = connection.createStatement();
                 ResultSet rs = s.executeQuery("SELECT site_key, id FROM site WHERE site_type = 'PUBLIC'")) {
                while (rs.next()) {
                    sites.put(rs.getString(1), rs.getLong(2));
                }
            }
            Map<String, NoticeBoard> boards = new HashMap<>();
            try (PreparedStatement byKey = connection.prepareStatement("SELECT id, slug FROM board WHERE board_key = ? AND deleted = 0")) {
                for (Map.Entry<String, Long> site : sites.entrySet()) {
                    NoticeBoard board = findBoard(byKey, "NOTICE:" + site.getValue());
                    if (board == null) {
                        board = findBoard(byKey, "NOTICE");
                    }
                    if (board != null) {
                        boards.put(site.getKey(), board);
                    }
                }
            }
            // 마이그레이션 트랜잭션 동안 쓰는 문장 — Flyway가 연결을 닫을 때 함께 정리된다
            return new NoticeLinks(boards, connection.prepareStatement("SELECT board_id FROM post WHERE id = ?"));
        }

        private static NoticeBoard findBoard(PreparedStatement byKey, String boardKey) throws SQLException {
            byKey.setString(1, boardKey);
            try (ResultSet rs = byKey.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long id = rs.getLong(1);
                String slug = rs.getString(2);
                return new NoticeBoard(id, slug == null ? "board-" + id : slug);
            }
        }

        /** 그 사이트에서 이 게시글이 공지로 열리면(소속 = 해석 공지 게시판) 그 게시판, 아니면 null. */
        private NoticeBoard noticeBoardOf(String siteKey, long postId) throws SQLException {
            NoticeBoard board = boardsBySiteKey.get(siteKey);
            if (board == null) {
                return null;
            }
            Optional<Long> boardId = postBoardCache.get(postId);
            if (boardId == null) {
                postBoard.setLong(1, postId);
                try (ResultSet rs = postBoard.executeQuery()) {
                    boardId = rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
                }
                postBoardCache.put(postId, boardId);
            }
            return boardId.isPresent() && boardId.get() == board.id() ? board : null;
        }

        /** 정규형 {@code /{키}/notices/{id}…}이고 그 글이 해석 공지 소속이면 {@code /{키}/boards/{슬러그}/posts/{id}…}로. */
        String rewriteUrl(String url) throws SQLException {
            if (!url.startsWith("/") || !isCanonicalPath(BoardLinkPaths.pathPart(url))) {
                return url;
            }
            int keyEnd = url.indexOf('/', 1);
            if (keyEnd < 0 || !url.startsWith("/notices/", keyEnd)) {
                return url;
            }
            String siteKey = url.substring(1, keyEnd);
            int idStart = keyEnd + "/notices/".length();
            int idEnd = idStart;
            while (idEnd < url.length() && "/?#".indexOf(url.charAt(idEnd)) < 0) {
                idEnd++;
            }
            Long id = PathIds.parse(url.substring(idStart, idEnd));
            if (id == null) {
                return url;
            }
            NoticeBoard board = noticeBoardOf(siteKey, id);
            if (board == null) {
                return url;
            }
            return "/" + siteKey + "/boards/" + board.slug() + "/posts/" + id + url.substring(idEnd);
        }

        /** {@code <a>} 태그의 {@code href} 값에만 {@link #rewriteUrl}을 적용한다(본문 텍스트·다른 속성·다른 태그는 그대로). */
        String rewriteHtml(String html) throws SQLException {
            Matcher tag = A_TAG.matcher(html);
            StringBuilder out = new StringBuilder(html.length() + 32);
            int last = 0;
            while (tag.find()) {
                String original = tag.group();
                Matcher href = HREF.matcher(original);
                String rewritten = original;
                if (href.find()) {
                    String value = href.group(2);
                    String replaced = rewriteUrl(value);
                    if (!replaced.equals(value)) {
                        rewritten = original.substring(0, href.start(2)) + replaced + original.substring(href.end(2));
                    }
                }
                out.append(html, last, tag.start()).append(rewritten);
                last = tag.end();
            }
            return out.append(html, last, html.length()).toString();
        }

        /** 지금 열리는 공지 링크인가 — 디코딩·점 세그먼트 정리 뒤 {@code /{공개 사이트 키}/notices/{id}…}이고 그 글이 해석 공지 소속. */
        boolean isOpenNoticeLink(String url) throws SQLException {
            BoardLinkPaths.NoticeTarget target = BoardLinkPaths.numericNoticeTarget(url);
            return target != null && noticeBoardOf(target.siteSegment(), target.postId()) != null;
        }

        boolean hasOpenHref(String html) throws SQLException {
            Matcher tag = A_TAG.matcher(html);
            while (tag.find()) {
                Matcher href = HREF.matcher(tag.group());
                if (href.find() && isOpenNoticeLink(Parser.unescapeEntities(href.group(2), true))) {
                    return true;
                }
            }
            return false;
        }

        /** 경로에 {@code %}가 없고 {@code .}·{@code ..} 세그먼트가 없는가 — 치환해도 목적지가 같음을 보장할 수 있는 형태. */
        static boolean isCanonicalPath(String path) {
            if (path.indexOf('%') >= 0) {
                return false;
            }
            for (String segment : path.split("/", -1)) {
                if (".".equals(segment) || "..".equals(segment)) {
                    return false;
                }
            }
            return true;
        }
    }

    private record NoticeBoard(long id, String slug) {
    }
}
