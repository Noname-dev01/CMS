package com.cms.publicweb.notice;

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
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.net.URI;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;

/**
 * 공개 공지 흡수의 실제 스택 시험(PLAN-board-definition.md "PR 4b 상세 설계" D1~D4) — {@code /{siteKey}/notices}는 해석된 공지 게시판의 현재 슬러그로
 * 302(GET·HEAD, 쿼리 원문 유지), 공지 게시판이 공개 조건을 통과하지 못하면 404, 공지 상세·첨부 하위 경로는 핸들러 없는 404. 302의 레이트리밋은
 * 레이트리밋을 켠 {@code SitePublicRateLimitConventionTest}가 본다(시험은 기본으로 꺼져 있다). 사이트별 해석(전용 → 공통, 전용 삭제 후 복귀)은
 * {@code PublicBoardSiteScopeIntegrationTest}가 본다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PublicNoticeRedirectIntegrationTest extends MariaDbContainerSupport {

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    private long noticeBoardId;
    private Long postId;
    private String ip;

    @BeforeEach
    void setUp() {
        int n = IP_SEQUENCE.getAndIncrement();
        ip = "10.91." + (n / 250) + "." + (n % 250 + 1);
        noticeBoardId = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
    }

    @AfterEach
    void cleanUp() {
        if (postId != null) {
            jdbc.update("DELETE FROM post_attachment WHERE post_id = ?", postId);
            jdbc.update("DELETE FROM post WHERE id = ?", postId);
        }
        jdbc.update("UPDATE board SET slug = NULL, board_key = 'NOTICE', public_yn = 1 WHERE id = ?", noticeBoardId);
    }

    private RequestPostProcessor fromTestIp() {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(fromTestIp())).andReturn();
    }

    @Test
    @DisplayName("GET·HEAD /main/notices는 공통 공지 게시판의 기본 슬러그로 302하고 본문이 없다")
    void redirectsToNoticeBoard() throws Exception {
        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[]{get("/main/notices"), head("/main/notices")}) {
            MvcResult result = perform(request);
            assertThat(result.getResponse().getStatus()).isEqualTo(302);
            assertThat(result.getResponse().getHeader("Location")).isEqualTo("/main/boards/board-" + noticeBoardId);
            assertThat(result.getResponse().getContentAsString()).isEmpty();
        }
    }

    @Test
    @DisplayName("쿼리스트링은 원문 그대로 붙는다 — 인코딩된 한글·CR/LF도 디코딩되지 않는다(헤더 분할 없음)")
    void keepsRawQueryString() throws Exception {
        MvcResult search = perform(get(URI.create("/main/notices?page=1&keyword=%ED%95%9C")));
        assertThat(search.getResponse().getStatus()).isEqualTo(302);
        assertThat(search.getResponse().getHeader("Location")).isEqualTo("/main/boards/board-" + noticeBoardId + "?page=1&keyword=%ED%95%9C");

        MvcResult crlf = perform(get(URI.create("/main/notices?keyword=a%0D%0ASet-Cookie:x=1")));
        assertThat(crlf.getResponse().getHeader("Location")).isEqualTo("/main/boards/board-" + noticeBoardId + "?keyword=a%0D%0ASet-Cookie:x=1");
        assertThat(crlf.getResponse().getHeader("Set-Cookie")).isNull();
    }

    @Test
    @DisplayName("공지 게시판 슬러그를 바꾸면 302 대상도 곧바로 새 슬러그다(302라 캐시되지 않는다)")
    void followsSlugChange() throws Exception {
        jdbc.update("UPDATE board SET slug = 'pr4b-notice' WHERE id = ?", noticeBoardId);

        MvcResult result = perform(get("/main/notices"));
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/main/boards/pr4b-notice");
    }

    @Test
    @DisplayName("공지 게시판이 없거나 공개 조건을 통과하지 못하면 302 없이 404")
    void notFoundWithoutPublicNoticeBoard() throws Exception {
        jdbc.update("UPDATE board SET public_yn = 0 WHERE id = ?", noticeBoardId);
        MvcResult hidden = perform(get("/main/notices"));
        assertThat(hidden.getResponse().getStatus()).isEqualTo(404);
        assertThat(hidden.getResponse().getHeader("Location")).isNull();

        jdbc.update("UPDATE board SET public_yn = 1, board_key = NULL WHERE id = ?", noticeBoardId);
        assertThat(perform(get("/main/notices")).getResponse().getStatus()).isEqualTo(404);
        assertThat(perform(head("/main/notices")).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("공지 상세·첨부 하위 경로는 실제 공지 글이어도 GET·HEAD 모두 404이고 게시판 경로로는 열린다")
    void subPathsAreGone() throws Exception {
        postId = insertNoticePost();
        for (String path : new String[]{"/main/notices/" + postId, "/main/notices/" + postId + "/attachments/1", "/main/notices/x"}) {
            assertThat(perform(get(path)).getResponse().getStatus()).as("GET " + path).isEqualTo(404);
            assertThat(perform(head(path)).getResponse().getStatus()).as("HEAD " + path).isEqualTo(404);
            assertThat(perform(get(path)).getResponse().getHeader("Location")).as(path).isNull();
        }
        assertThat(perform(get("/main/boards/board-" + noticeBoardId + "/posts/" + postId)).getResponse().getStatus()).isEqualTo(200);
    }

    private long insertNoticePost() {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, create_date, update_date)"
                    + " VALUES (?, 'pr4b 공지', '<p>본문</p>', 1, 0, 'tester', NOW(6), NOW(6))", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, noticeBoardId);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }
}
