package com.cms.publicweb.home;

import com.cms.support.BrowserTestSupport;
import com.cms.support.CmsTestApplication;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공개 메인의 좁은 화면 레이아웃(이슈 #194)을 <b>실제 브라우저</b>로 고정한다. 카드 열이 {@code minmax(300px, 1fr)}이고 컨테이너 좌우 패딩이 합 48px라
 * 340px 아래 화면에서 카드가 뷰포트를 넘쳐 가로 스크롤이 생겼다. 좁은 화면에서는 카드가 컨테이너 폭으로 줄어들고, 넓은 화면의 두 열 배치는 그대로여야 한다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HomeNarrowLayoutBrowserTest extends BrowserTestSupport {

    /** 게시판 이름 표시 상한(10rem)까지 차는 긴 이름. */
    private static final String LONG_BOARD_NAME = "아주아주긴게시판이름입니다열글자넘김";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> boardIds = new ArrayList<>();
    private long noticeBoardId;
    private boolean noticePublicBefore;
    private BrowserContext context;

    @BeforeEach
    void setUp() {
        noticeBoardId = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
        noticePublicBefore = jdbc.queryForObject("SELECT public_yn FROM board WHERE id = ?", Boolean.class, noticeBoardId);
        // 두 섹션(공지·새 글)이 모두 있어야 열 배치를 볼 수 있다
        jdbc.update("UPDATE board SET public_yn = 1 WHERE id = ?", noticeBoardId);
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        jdbc.update("DELETE FROM post WHERE board_id = ? AND title LIKE 'HNL-%'", noticeBoardId);
        for (Long boardId : boardIds) {
            jdbc.update("DELETE FROM post WHERE board_id = ?", boardId);
            jdbc.update("DELETE FROM board WHERE id = ?", boardId);
        }
        jdbc.update("UPDATE board SET public_yn = ? WHERE id = ?", noticePublicBefore, noticeBoardId);
    }

    private void seed() {
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted, create_date, update_date) VALUES (?, 1, 1, 0, NOW(6), NOW(6))",
                LONG_BOARD_NAME);
        long board = jdbc.queryForObject("SELECT MAX(id) FROM board", Long.class);
        boardIds.add(board);
        LocalDateTime now = LocalDateTime.now();
        post(board, "HNL-긴 게시판명 글 제목입니다", now);
        post(noticeBoardId, "HNL-공지 글 제목입니다", now);
    }

    private void post(long boardId, String title, LocalDateTime createDate) {
        jdbc.update("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, create_date, update_date)"
                + " VALUES (?, ?, '<p>본문</p>', 1, 0, 'hnl-admin', ?, ?)", boardId, title, createDate, createDate);
    }

    private Page open(int width) {
        context = newContext(new Browser.NewContextOptions().setViewportSize(width, 800));
        Page page = context.newPage();
        page.navigate("http://localhost:" + port + "/main");
        page.waitForSelector(".home-post-item");
        return page;
    }

    @Test
    @DisplayName("320px 화면에서 가로 스크롤이 없고, 썸네일 없는 줄도 긴 게시판명에 제목·날짜가 밀려나지 않는다")
    void narrowViewportHasNoHorizontalScroll() {
        seed();
        Page page = open(320);

        @SuppressWarnings("unchecked")
        Map<String, Number> m = (Map<String, Number>) page.evaluate("() => { const de = document.documentElement;"
                + " const li = [...document.querySelectorAll('.home-post-item')].find(x => x.textContent.includes('HNL-긴 게시판명'));"
                + " const row = li.querySelector('a').getBoundingClientRect();"
                + " const t = li.querySelector('.home-post-title').getBoundingClientRect();"
                + " const d = li.querySelector('.home-post-date').getBoundingClientRect();"
                + " const sec = document.querySelector('.home-section').getBoundingClientRect();"
                + " return { scrollW: de.scrollWidth, clientW: de.clientWidth, sectionRight: sec.right, titleW: t.width, dateOver: d.right - row.right }; }");

        assertThat(m.get("scrollW").intValue()).as("문서 가로 폭(%s) ≤ 뷰포트(%s) — 가로 스크롤 없음", m.get("scrollW"), m.get("clientW"))
                .isLessThanOrEqualTo(m.get("clientW").intValue());
        assertThat(m.get("sectionRight").doubleValue()).as("카드 오른쪽 끝이 뷰포트 안").isLessThanOrEqualTo(m.get("clientW").doubleValue());
        assertThat(m.get("titleW").doubleValue()).as("썸네일 없는 줄의 제목 폭(px)").isGreaterThanOrEqualTo(120);
        assertThat(m.get("dateOver").doubleValue()).as("날짜가 행 오른쪽 밖으로 넘치지 않는다").isLessThanOrEqualTo(0);
    }

    @Test
    @DisplayName("넓은 화면(1000px)에서는 공지·새 글 카드가 기존처럼 두 열로 나란히 놓이고 각 줄은 한 줄이다")
    void wideViewportKeepsTwoColumns() {
        seed();
        Page page = open(1000);

        @SuppressWarnings("unchecked")
        Map<String, Number> m = (Map<String, Number>) page.evaluate("() => { const s = [...document.querySelectorAll('.home-section')].map(x => x.getBoundingClientRect());"
                + " const li = [...document.querySelectorAll('.home-post-item')].find(x => x.textContent.includes('HNL-긴 게시판명'));"
                + " const t = li.querySelector('.home-post-title').getBoundingClientRect(); const b = li.querySelector('.home-post-board').getBoundingClientRect();"
                + " return { count: s.length, topDiff: Math.abs(s[0].top - s[1].top), leftDiff: Math.abs(s[0].left - s[1].left), sameLine: Math.abs(t.top - b.top) }; }");

        assertThat(m.get("count").intValue()).isEqualTo(2);
        assertThat(m.get("topDiff").doubleValue()).as("두 카드가 같은 높이에서 시작(두 열)").isLessThan(1);
        assertThat(m.get("leftDiff").doubleValue()).as("두 카드가 서로 다른 열").isGreaterThan(100);
        assertThat(m.get("sameLine").doubleValue()).as("넓은 화면에서는 게시판명과 제목이 같은 줄(줄바꿈 없음)").isLessThan(12);
    }
}
