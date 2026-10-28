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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 상단 고정 영역의 공개 화면 시험(PLAN-board-definition.md PR 3 D3-5). 검색하지 않은 1페이지에서만 게시판 고정 상한만큼 나오고, 고정 글은 일반
 * 목록에서도 빠지지 않아 총 페이지 수가 그대로이며, 비노출·예약·만료·삭제 고정 글은 영역에 없다. 공지 게시판도 게시판 화면 한 벌로 열린다
 * (PR 4b — {@code /notices}는 302만). 행은 JDBC로 직접 만들고 요청마다 고유 원격 IP를 쓴다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class BoardPinPublicIntegrationTest extends MariaDbContainerSupport {

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> boardIds = new ArrayList<>();
    private final List<Long> noticePostIds = new ArrayList<>();
    private long noticeBoardId;
    private Integer noticePinMaxBackup;
    private String ip;

    @BeforeEach
    void setUp() {
        int n = IP_SEQUENCE.getAndIncrement();
        ip = "10.90." + (n / 250) + "." + (n % 250 + 1);
        noticeBoardId = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
        noticePinMaxBackup = jdbc.queryForObject("SELECT pin_max_count FROM board WHERE id = ?", Integer.class, noticeBoardId);
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
        jdbc.update("UPDATE board SET pin_max_count = ? WHERE id = ?", noticePinMaxBackup, noticeBoardId);
    }

    private RequestPostProcessor fromTestIp() {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private long insertBoard(int pinMax) {
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted, pin_max_count, create_date, update_date)"
                + " VALUES (?, 1, 1, 0, ?, NOW(6), NOW(6))", "PINP_" + System.nanoTime(), pinMax);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM board", Long.class);
        boardIds.add(id);
        return id;
    }

    private long insertPost(long boardId, String title, LocalDateTime createDate, boolean pinned, boolean useYn, boolean deleted,
                            LocalDateTime publishStart, LocalDateTime publishEnd) {
        jdbc.update("INSERT INTO post (board_id, title, content, use_yn, deleted, pinned, author_id, create_date, update_date, publish_start, publish_end)"
                        + " VALUES (?, ?, '<p>본문</p>', ?, ?, ?, 'pinp-admin', ?, ?, ?, ?)",
                boardId, title, useYn, deleted, pinned, createDate, createDate, publishStart, publishEnd);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM post WHERE board_id = ?", Long.class, boardId);
        if (boardId == noticeBoardId) {
            noticePostIds.add(id);
        }
        return id;
    }

    private long insertPost(long boardId, String title, LocalDateTime createDate, boolean pinned) {
        return insertPost(boardId, title, createDate, pinned, true, false, null, null);
    }

    private String boardList(long boardId, String... params) throws Exception {
        var request = get("/main/boards/board-{id}", boardId).with(fromTestIp());
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    @DisplayName("기본(고정 끔): 고정 글이 있어도 영역이 없고 목록·페이지 수는 현행 그대로다")
    void pinDisabledShowsNoArea() throws Exception {
        long board = insertBoard(0);
        insertPost(board, "끔-고정", LocalDateTime.now(), true);
        insertPost(board, "끔-일반", LocalDateTime.now().minusMinutes(1), false);

        String page = boardList(board);

        assertThat(page).doesNotContain("board-pinned-list").doesNotContain("board-pinned-badge");
        assertThat(count(page, "board-list-item")).isEqualTo(2);
    }

    @Test
    @DisplayName("1페이지 상단에 고정 영역이 나오고 고정 글은 일반 목록에서도 빠지지 않으며 총 페이지 수는 그대로다 — 2페이지에는 영역이 없다")
    void pinnedAreaOnFirstPageOnly() throws Exception {
        long board = insertBoard(2);
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        // 12건: 가장 새 글(0)과 가장 오래된 글(11)이 고정 — 오래된 고정 글은 일반 목록 2페이지에 있다
        for (int i = 0; i < 12; i++) {
            insertPost(board, "고정글" + i + "번", base.minusMinutes(i), i == 0 || i == 11);
        }

        String page0 = boardList(board);

        assertThat(count(page0, "board-pinned-badge")).isEqualTo(2);
        assertThat(count(page0, "board-list-item")).as("영역 2건 + 일반 목록 10건").isEqualTo(12);
        assertThat(count(page0, "고정글0번")).as("새 고정 글은 영역과 일반 목록에 모두").isEqualTo(2);
        assertThat(count(page0, "고정글11번")).as("오래된 고정 글은 1페이지에서는 영역에만").isEqualTo(1);
        assertThat(page0).contains("1 / 2");

        String page1 = boardList(board, "page", "1");
        assertThat(page1).doesNotContain("board-pinned-list").doesNotContain("board-pinned-badge");
        assertThat(count(page1, "고정글11번")).isEqualTo(1);
        assertThat(page1).contains("2 / 2");
    }

    @Test
    @DisplayName("검색 중에는 고정 영역이 없다 — 일치하는 고정 글은 검색 결과로만 나온다")
    void searchHidesPinnedArea() throws Exception {
        long board = insertBoard(2);
        insertPost(board, "검색-고정", LocalDateTime.now(), true);
        insertPost(board, "검색-일반", LocalDateTime.now().minusMinutes(1), false);

        String result = boardList(board, "keyword", "검색-");

        assertThat(result).doesNotContain("board-pinned-list").doesNotContain("board-pinned-badge");
        assertThat(count(result, "검색-고정")).isEqualTo(1);
    }

    @Test
    @DisplayName("공개 영역은 게시판 상한만큼만, 가장 새 고정 글부터 보인다 — 상한을 낮추면 줄고 데이터는 그대로다")
    void limitAppliesNewestFirst() throws Exception {
        long board = insertBoard(1);
        LocalDateTime now = LocalDateTime.now();
        insertPost(board, "상한-가장오래된", now.minusMinutes(3), true);
        insertPost(board, "상한-중간", now.minusMinutes(2), true);
        insertPost(board, "상한-가장새로운", now.minusMinutes(1), true);

        String page = boardList(board);

        assertThat(count(page, "board-pinned-badge")).isEqualTo(1);
        int generalList = page.indexOf("<ul class=\"board-list\">");
        assertThat(generalList).isPositive();
        assertThat(page.indexOf("board-pinned-list")).as("영역은 일반 목록보다 위").isLessThan(generalList);
        assertThat(page.indexOf("상한-가장새로운")).as("영역에는 가장 새 고정 글").isLessThan(generalList);
        assertThat(page.indexOf("상한-중간")).as("상한 밖 고정 글은 영역에 없고 일반 목록에만").isGreaterThan(generalList);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post WHERE board_id = ? AND pinned = 1", Integer.class, board)).isEqualTo(3);

        jdbc.update("UPDATE board SET pin_max_count = 2 WHERE id = ?", board);
        assertThat(count(boardList(board), "board-pinned-badge")).isEqualTo(2);
    }

    @Test
    @DisplayName("비노출·예약 전·만료·삭제된 고정 글은 영역에 나오지 않는다 (공개 조건 그대로)")
    void unpublishedPinnedNeverShown() throws Exception {
        long board = insertBoard(5);
        LocalDateTime now = LocalDateTime.now();
        insertPost(board, "숨김-노출", now, true);
        insertPost(board, "숨김-비노출", now, true, false, false, null, null);
        insertPost(board, "숨김-삭제", now, true, true, true, null, null);
        insertPost(board, "숨김-예약", now, true, true, false, now.plusDays(1), null);
        insertPost(board, "숨김-만료", now, true, true, false, null, now.minusDays(1));

        String page = boardList(board);

        assertThat(count(page, "board-pinned-badge")).isEqualTo(1);
        assertThat(page).contains("숨김-노출")
                .doesNotContain("숨김-비노출").doesNotContain("숨김-삭제").doesNotContain("숨김-예약").doesNotContain("숨김-만료");
    }

    @Test
    @DisplayName("고정 글의 제목은 이스케이프되어 출력된다 (영역도 th:text)")
    void pinnedTitleIsEscaped() throws Exception {
        long board = insertBoard(1);
        insertPost(board, "<script>alert('pin')</script>", LocalDateTime.now(), true);

        String page = boardList(board);

        assertThat(page).doesNotContain("<script>alert('pin')</script>").contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("공지 게시판 목록(게시판 경로)도 같은 고정 영역이 나온다 — 상한·검색 규칙 동일")
    void noticeBoardHasPinnedArea() throws Exception {
        jdbc.update("UPDATE board SET pin_max_count = 1 WHERE id = ?", noticeBoardId);
        LocalDateTime now = LocalDateTime.now().plusYears(1);
        insertPost(noticeBoardId, "공지고정-A", now, true);
        insertPost(noticeBoardId, "공지고정-B", now.minusMinutes(1), true);
        insertPost(noticeBoardId, "공지일반-C", now.minusMinutes(2), false);

        String page = boardList(noticeBoardId);
        assertThat(count(page, "board-pinned-badge")).as("상한 1").isEqualTo(1);
        assertThat(page).contains("board-pinned-list");
        assertThat(count(page, "공지고정-A")).as("영역과 일반 목록").isEqualTo(2);

        assertThat(boardList(noticeBoardId, "keyword", "공지고정")).doesNotContain("board-pinned-list");

        jdbc.update("UPDATE board SET pin_max_count = 0 WHERE id = ?", noticeBoardId);
        assertThat(boardList(noticeBoardId)).doesNotContain("board-pinned-list").doesNotContain("board-pinned-badge");
    }
}
