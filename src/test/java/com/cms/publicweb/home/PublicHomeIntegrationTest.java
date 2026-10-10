package com.cms.publicweb.home;

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
 * 공개 메인의 공지·새 글 섹션(PLAN-public-home-banner.md 쟁점 12): 공개 불변식(게시판 공개·미삭제 ∧ 게시글 노출·미삭제), 공지 게시판 분리,
 * 섹션 크기 5, 제목 이스케이프를 실제 스택으로 확인한다. 행은 JDBC로 직접 만들고 시험이 끝나면 지운다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PublicHomeIntegrationTest extends MariaDbContainerSupport {

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> boardIds = new ArrayList<>();
    private long noticeBoardId;
    private boolean noticePublicBefore;
    private String ip;

    @BeforeEach
    void setUp() {
        int n = IP_SEQUENCE.getAndIncrement();
        ip = "10.90." + (n / 250) + "." + (n % 250 + 1);
        noticeBoardId = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
        noticePublicBefore = jdbc.queryForObject("SELECT public_yn FROM board WHERE id = ?", Boolean.class, noticeBoardId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM post WHERE board_id = ? AND title LIKE 'home-test-%'", noticeBoardId);
        for (Long boardId : boardIds) {
            jdbc.update("DELETE FROM post WHERE board_id = ?", boardId);
            jdbc.update("DELETE FROM board WHERE id = ?", boardId);
        }
        jdbc.update("UPDATE board SET public_yn = ?, deleted = 0 WHERE id = ?", noticePublicBefore, noticeBoardId);
    }

    private RequestPostProcessor fromIp() {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private long board(String name, boolean publicYn, boolean deleted) {
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted, create_date, update_date) VALUES (?, ?, 1, ?, NOW(), NOW())",
                name, publicYn, deleted);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM board", Long.class);
        boardIds.add(id);
        return id;
    }

    private long post(long boardId, String title, boolean useYn, boolean deleted, LocalDateTime createDate) {
        jdbc.update("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, create_date, update_date) VALUES (?, ?, '<p>x</p>', ?, ?, 'a', ?, ?)",
                boardId, title, useYn, deleted, createDate, createDate);
        return jdbc.queryForObject("SELECT MAX(id) FROM post", Long.class);
    }

    private String home() throws Exception {
        return mockMvc.perform(get("/").with(fromIp())).andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("공지는 공지 게시판 글을 /notices/{id}로, 새 글은 공지 게시판을 뺀 공개 게시판 글을 /boards/…로 보여 준다(같은 글이 두 섹션에 나오지 않는다)")
    void sections() throws Exception {
        long noticePost = post(noticeBoardId, "home-test-공지", true, false, LocalDateTime.now());
        long other = board("home-test-자료실", true, false);
        long otherPost = post(other, "home-test-자료글", true, false, LocalDateTime.now());

        String html = home();

        assertThat(html).contains("href=\"/notices/" + noticePost + "\"").contains("home-test-공지");
        assertThat(html).contains("href=\"/boards/" + other + "/posts/" + otherPost + "\"").contains("home-test-자료실").contains("home-test-자료글");
        assertThat(html.split("home-test-공지", -1).length - 1).as("공지 글은 공지 섹션에만").isEqualTo(1);
    }

    @Test
    @DisplayName("공개 불변식: 비공개·삭제 게시판의 글, 미노출·삭제 글은 어느 섹션에도 나오지 않는다")
    void invariants() throws Exception {
        long privateBoard = board("home-test-비공개판", false, false);
        long deletedBoard = board("home-test-삭제판", true, true);
        long open = board("home-test-공개판", true, false);
        post(privateBoard, "home-test-비공개판글", true, false, LocalDateTime.now());
        post(deletedBoard, "home-test-삭제판글", true, false, LocalDateTime.now());
        post(open, "home-test-미노출글", false, false, LocalDateTime.now());
        post(open, "home-test-삭제글", true, true, LocalDateTime.now());
        post(noticeBoardId, "home-test-미노출공지", false, false, LocalDateTime.now());
        post(open, "home-test-보이는글", true, false, LocalDateTime.now());

        String html = home();

        assertThat(html).contains("home-test-보이는글");
        assertThat(html).doesNotContain("home-test-비공개판글", "home-test-삭제판글", "home-test-미노출글", "home-test-삭제글", "home-test-미노출공지");
    }

    @Test
    @DisplayName("공지 게시판이 비공개이면 공지 섹션은 없고(메인은 200), 그 게시판 글이 새 글로 새어 나오지도 않는다")
    void privateNoticeBoard_hidesSection() throws Exception {
        post(noticeBoardId, "home-test-숨은공지", true, false, LocalDateTime.now());
        jdbc.update("UPDATE board SET public_yn = 0 WHERE id = ?", noticeBoardId);

        var result = mockMvc.perform(get("/").with(fromIp())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("home-test-숨은공지");
    }

    @Test
    @DisplayName("섹션당 최신 5건만, 최신순이며 제목은 HTML 이스케이프된다")
    void limitOrderAndEscaping() throws Exception {
        long open = board("home-test-많은판", true, false);
        LocalDateTime base = LocalDateTime.now().withNano(0);
        for (int i = 0; i < 7; i++) {
            post(open, "home-test-글" + i + (i == 6 ? "<script>x</script>" : ""), true, false, base.plusMinutes(i));
        }

        String html = home();

        assertThat(html).contains("home-test-글6", "home-test-글5", "home-test-글4", "home-test-글3", "home-test-글2");
        assertThat(html).doesNotContain("home-test-글1", "home-test-글0");
        assertThat(html.indexOf("home-test-글6")).isLessThan(html.indexOf("home-test-글5"));
        assertThat(html).doesNotContain("<script>x</script>").contains("&lt;script&gt;x&lt;/script&gt;");
    }
}
