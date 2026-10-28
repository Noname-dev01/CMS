package com.cms.publicweb.board;

import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 게시판 표시 설정(목록 크기·조회수·새 글 배지·설명·작성자 표시명)의 공개 화면 반영 시험(PLAN-board-definition.md PR 1 D4). 일반 게시판
 * ({@code /boards})과 공지({@code /notices}) 두 템플릿 세트가 같은 결과여야 한다. 행은 JDBC로 직접 만들고 요청마다 고유 원격 IP를 쓴다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class BoardDisplaySettingsPublicIntegrationTest extends MariaDbContainerSupport {

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> boardIds = new ArrayList<>();
    private final List<Long> noticePostIds = new ArrayList<>();
    private Map<String, Object> noticeBoardBackup;
    private long noticeBoardId;
    private String ip;

    @BeforeEach
    void setUp() {
        int n = IP_SEQUENCE.getAndIncrement();
        ip = "10.89." + (n / 250) + "." + (n % 250 + 1);
        noticeBoardId = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
        noticeBoardBackup = jdbc.queryForMap("SELECT description, list_page_size, author_label, show_view_count, new_badge_days FROM board WHERE id = ?",
                noticeBoardId);
    }

    @AfterEach
    void cleanUp() {
        for (Long boardId : boardIds) {
            jdbc.update("DELETE FROM post WHERE board_id = ?", boardId);
            jdbc.update("DELETE FROM board WHERE id = ?", boardId);
        }
        for (Long postId : noticePostIds) {
            jdbc.update("DELETE FROM post WHERE id = ?", postId);
        }
        jdbc.update("UPDATE board SET description = ?, list_page_size = ?, author_label = ?, show_view_count = ?, new_badge_days = ? WHERE id = ?",
                noticeBoardBackup.get("description"), noticeBoardBackup.get("list_page_size"), noticeBoardBackup.get("author_label"),
                noticeBoardBackup.get("show_view_count"), noticeBoardBackup.get("new_badge_days"), noticeBoardId);
    }

    private RequestPostProcessor fromTestIp() {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private long insertBoard(String settingsSql) {
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted, create_date, update_date) VALUES (?, 1, 1, 0, NOW(6), NOW(6))",
                "BDP_" + System.nanoTime());
        long id = jdbc.queryForObject("SELECT MAX(id) FROM board", Long.class);
        boardIds.add(id);
        if (settingsSql != null) {
            jdbc.update("UPDATE board SET " + settingsSql + " WHERE id = ?", id);
        }
        return id;
    }

    private long insertPost(long boardId, String title, LocalDateTime createDate, Long viewCount, LocalDateTime publishStart) {
        jdbc.update("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, create_date, update_date, view_count, publish_start)"
                        + " VALUES (?, ?, '<p>본문</p>', 1, 0, 'bdp-secret-admin', ?, ?, ?, ?)",
                boardId, title, createDate, createDate, viewCount == null ? 0L : viewCount, publishStart);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM post WHERE board_id = ?", Long.class, boardId);
        if (boardId == noticeBoardId) {
            noticePostIds.add(id);
        }
        return id;
    }

    private String list(long boardId, String... params) throws Exception {
        var request = get("/main/boards/board-{id}", boardId).with(fromTestIp());
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    private String detail(long boardId, long postId) throws Exception {
        return mockMvc.perform(get("/main/boards/board-{b}/posts/{p}", boardId, postId).with(fromTestIp())).andReturn().getResponse().getContentAsString();
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    // ── 기본값 = 현행 동작 ───────────────────────────────────────

    @Test
    @DisplayName("기본 설정(기존 게시판과 같음): 목록 10건·조회수/작성자/배지/설명 없음 — 로그인 ID도 나오지 않는다")
    void defaultsKeepCurrentBehavior() throws Exception {
        long board = insertBoard(null);
        for (int i = 0; i < 11; i++) {
            insertPost(board, "기본글" + i, LocalDateTime.now().minusMinutes(i), 5L, null);
        }

        String page0 = list(board);

        assertThat(count(page0, "board-list-item")).isEqualTo(10);
        assertThat(page0).contains("1 / 2").doesNotContain("조회").doesNotContain("board-new-badge").doesNotContain("board-description")
                .doesNotContain("bdp-secret-admin");
    }

    @Test
    @DisplayName("목록 크기 20이면 11건이 한 페이지에 나온다")
    void listPageSizeApplies() throws Exception {
        long board = insertBoard("list_page_size = 20");
        for (int i = 0; i < 11; i++) {
            insertPost(board, "크기글" + i, LocalDateTime.now().minusMinutes(i), null, null);
        }

        String page0 = list(board);

        assertThat(count(page0, "board-list-item")).isEqualTo(11);
        assertThat(page0).doesNotContain("board-pagination");
    }

    @Test
    @DisplayName("허용 페이지 경계: 크기 50은 page 200까지(데이터가 없으면 빈 목록), 201부터는 0페이지로 보정되어 첫 페이지 글이 보인다")
    void pageLimitFollowsListSize() throws Exception {
        long board = insertBoard("list_page_size = 50");
        insertPost(board, "경계글", LocalDateTime.now(), null, null);

        assertThat(list(board, "page", "200")).as("허용 범위 안 — 데이터 없음").contains("등록된 게시글이 없습니다").doesNotContain("경계글");
        assertThat(list(board, "page", "201")).as("범위 밖 — 0페이지로 보정").contains("경계글");
    }

    // ── 조회수 ───────────────────────────────────────────────────

    @Test
    @DisplayName("조회수 표시를 켠 게시판만 목록·상세에 조회수가 보이고, 끄면 값 자체가 응답에 없다")
    void viewCountOnlyWhenEnabled() throws Exception {
        long on = insertBoard("show_view_count = 1");
        long off = insertBoard(null);
        long onPost = insertPost(on, "켜짐글", LocalDateTime.now(), 7L, null);
        long offPost = insertPost(off, "꺼짐글", LocalDateTime.now(), 7L, null);

        assertThat(list(on)).contains("조회 7");
        assertThat(detail(on, onPost)).contains("조회 7");
        assertThat(list(off)).doesNotContain("조회");
        assertThat(detail(off, offPost)).doesNotContain("조회");
    }

    // ── 새 글 배지 ───────────────────────────────────────────────

    @Test
    @DisplayName("새 글 배지: 기간 안의 글만, 예약 글은 공개 시각 기준, create_date가 NULL인 글이 있어도 목록은 200")
    void newBadgeRules() throws Exception {
        long board = insertBoard("new_badge_days = 3");
        LocalDateTime now = LocalDateTime.now();
        insertPost(board, "최근글", now.minusHours(2), null, null);
        insertPost(board, "오래된글", now.minusDays(10), null, null);
        insertPost(board, "예약공개글", now.minusDays(10), null, now.minusHours(3));   // 작성은 오래전, 공개된 지 3시간
        jdbc.update("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id) VALUES (?, '날짜없는글', '<p>x</p>', 1, 0, 'bdp-secret-admin')",
                board);

        String html = list(board);

        assertThat(html).contains("최근글").contains("오래된글").contains("예약공개글").contains("날짜없는글");
        assertThat(count(html, "board-new-badge")).as("최근글·예약공개글만").isEqualTo(2);
    }

    @Test
    @DisplayName("새 글 기간 0이면 배지가 없다")
    void newBadgeOffByDefault() throws Exception {
        long board = insertBoard(null);
        insertPost(board, "방금글", LocalDateTime.now(), null, null);

        assertThat(list(board)).doesNotContain("board-new-badge");
    }

    // ── 설명·작성자 표시명 (평문 · 이스케이프) ────────────────────

    @Test
    @DisplayName("설명과 작성자 표시명은 평문으로 이스케이프되어 나오고 줄바꿈은 CSS가 보인다 — 스크립트가 실행되지 않는다")
    void descriptionAndLabelAreEscaped() throws Exception {
        long board = jdbcInsertWithText("<script>alert(1)</script>\n둘째 줄", "<img src=x onerror=alert(1)>");
        long post = insertPost(board, "표시글", LocalDateTime.now(), null, null);

        String html = list(board);
        String detail = detail(board, post);

        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>alert(1)");
        assertThat(html).contains("&lt;img src=x onerror=alert(1)&gt;").doesNotContain("<img src=x");
        assertThat(detail).contains("&lt;img src=x onerror=alert(1)&gt;").doesNotContain("<img src=x");
        assertThat(html).contains("board-description");
    }

    private long jdbcInsertWithText(String description, String authorLabel) {
        long board = insertBoard(null);
        jdbc.update("UPDATE board SET description = ?, author_label = ? WHERE id = ?", description, authorLabel, board);
        return board;
    }

    // ── 공지 템플릿 세트도 같은 결과 ──────────────────────────────

    @Test
    @DisplayName("공지(/notices) 목록·상세도 같은 설정을 반영한다 — 설명·작성자 표시명·조회수·배지·목록 크기")
    void noticeTemplatesReflectSettings() throws Exception {
        jdbc.update("UPDATE board SET description = '공지 안내', author_label = '운영팀', show_view_count = 1, new_badge_days = 3, list_page_size = 20 WHERE id = ?",
                noticeBoardId);
        long recent = insertPost(noticeBoardId, "신규공지", LocalDateTime.now().minusHours(1), 9L, null);
        for (int i = 0; i < 11; i++) {
            insertPost(noticeBoardId, "예전공지" + i, LocalDateTime.now().minusDays(20).minusMinutes(i), null, null);
        }

        String list = mockMvc.perform(get("/main/notices").with(fromTestIp())).andReturn().getResponse().getContentAsString();
        String detail = mockMvc.perform(get("/main/notices/{id}", recent).with(fromTestIp())).andReturn().getResponse().getContentAsString();

        assertThat(list).contains("공지 안내").contains("운영팀").contains("조회 9");
        assertThat(count(list, "notice-new-badge")).isEqualTo(1);
        assertThat(count(list, "notice-list-item")).as("목록 크기 20 — 12건 모두").isEqualTo(12);
        assertThat(detail).contains("운영팀").contains("조회 9").doesNotContain("bdp-secret-admin");
    }
}
