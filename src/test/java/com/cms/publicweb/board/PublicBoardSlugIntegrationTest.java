package com.cms.publicweb.board;

import com.cms.common.storage.FileStorage;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;

/**
 * 공개 게시판 슬러그 라우팅의 실제 스택 시험(PLAN-board-definition.md "PR 4a 상세 설계" D4·D8) — 기본·사용자 슬러그 200, 숫자 id 404(완전 교체),
 * 슬러그를 바꾼 게시판의 옛 기본 슬러그 301(정규형 Location·쿼리 유지·HEAD 포함), 공개 조건을 통과하지 못한 게시판은 301 없이 같은 404,
 * 템플릿 링크가 현재 슬러그를 쓴다, 301은 조회수·다운로드 수를 세지 않는다. 행은 JDBC로 직접 만든다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PublicBoardSlugIntegrationTest extends MariaDbContainerSupport {

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileStorage fileStorage;

    private final List<Long> boardIds = new ArrayList<>();
    private final List<String> storageKeys = new ArrayList<>();
    private Long otherSiteId;
    private String ip;

    @BeforeEach
    void setUp() {
        int n = IP_SEQUENCE.getAndIncrement();
        ip = "10.89." + (n / 250) + "." + (n % 250 + 1);
    }

    @AfterEach
    void cleanUp() {
        for (Long boardId : boardIds) {
            jdbc.update("DELETE a FROM post_attachment a JOIN post p ON p.id = a.post_id WHERE p.board_id = ?", boardId);
            jdbc.update("DELETE FROM post WHERE board_id = ?", boardId);
            jdbc.update("DELETE FROM board WHERE id = ?", boardId);
        }
        if (otherSiteId != null) {
            jdbc.update("DELETE FROM site WHERE id = ?", otherSiteId);
        }
        for (String key : storageKeys) {
            try {
                fileStorage.delete(key);
            } catch (RuntimeException ignored) {
            }
        }
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

    private long insertBoard(String slug, boolean publicYn, boolean deleted, Long siteId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO board (name, public_yn, attachment_yn, deleted, slug, site_id, create_date, update_date)"
                            + " VALUES ('슬러그 게시판', ?, 1, ?, ?, ?, NOW(6), NOW(6))", Statement.RETURN_GENERATED_KEYS);
            ps.setBoolean(1, publicYn);
            ps.setBoolean(2, deleted);
            ps.setString(3, slug);
            ps.setObject(4, siteId);
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        boardIds.add(id);
        return id;
    }

    private long insertPost(long boardId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, create_date, update_date)"
                    + " VALUES (?, '슬러그 글', '<p>본문</p>', 1, 0, 'tester', NOW(6), NOW(6))", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, boardId);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private long insertAttachment(long postId) {
        String key = fileStorage.store("pdf".getBytes(StandardCharsets.UTF_8), "a.pdf");
        storageKeys.add(key);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO post_attachment (post_id, original_filename, content_type, file_size, storage_key, create_date)"
                    + " VALUES (?, 'a.pdf', 'application/pdf', 3, ?, NOW(6))", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, postId);
            ps.setString(2, key);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private static String uniqueSlug() {
        return "ps-" + System.nanoTime();
    }

    @Test
    @DisplayName("기본 슬러그 board-{id} 게시판은 목록·상세·첨부가 열리고 숫자 id 경로는 전부 404(GET·HEAD)")
    void defaultSlugOpens_numericIdIsGone() throws Exception {
        long board = insertBoard(null, true, false, null);
        long post = insertPost(board);
        long attachment = insertAttachment(post);

        assertThat(perform(get("/main/boards/board-{b}", board)).getResponse().getStatus()).isEqualTo(200);
        assertThat(perform(get("/main/boards/board-{b}/posts/{p}", board, post)).getResponse().getStatus()).isEqualTo(200);
        assertThat(perform(get("/main/boards/board-{b}/posts/{p}/attachments/{a}", board, post, attachment)).getResponse().getStatus()).isEqualTo(200);

        assertThat(perform(get("/main/boards/{b}", board)).getResponse().getStatus()).isEqualTo(404);
        assertThat(perform(get("/main/boards/{b}/posts/{p}", board, post)).getResponse().getStatus()).isEqualTo(404);
        assertThat(perform(get("/main/boards/{b}/posts/{p}/attachments/{a}", board, post, attachment)).getResponse().getStatus()).isEqualTo(404);
        assertThat(perform(head("/main/boards/{b}/posts/{p}/attachments/{a}", board, post, attachment)).getResponse().getStatus()).isEqualTo(404);
        assertThat(perform(head("/main/boards/{b}", board)).getResponse().getStatus()).isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"board-0%s", "board-+%s", "BOARD-%s", "Board-%s"})
    @DisplayName("정규형이 아닌 기본 슬러그 표기는 404")
    void nonCanonicalDefaultSlug(String pattern) throws Exception {
        long board = insertBoard(null, true, false, null);
        assertThat(perform(get("/main/boards/" + String.format(pattern, board))).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("사용자 슬러그 게시판은 그 슬러그로 열리고, 옛 기본 슬러그는 정규형 Location으로 301(목록 쿼리 유지·상세·첨부·HEAD)")
    void customSlug_andMovedDefault() throws Exception {
        String slug = uniqueSlug();
        long board = insertBoard(slug, true, false, null);
        long post = insertPost(board);
        long attachment = insertAttachment(post);

        assertThat(perform(get("/main/boards/" + slug)).getResponse().getStatus()).isEqualTo(200);
        assertThat(perform(get("/main/boards/" + slug + "/posts/" + post)).getResponse().getStatus()).isEqualTo(200);
        assertThat(perform(get("/main/boards/" + slug.toUpperCase(java.util.Locale.ROOT))).getResponse().getStatus()).as("대문자").isEqualTo(404);

        // 템플릿 문자열은 MockMvc가 %를 다시 인코딩하므로 URI를 그대로 넘긴다
        MvcResult list = perform(get(java.net.URI.create("/main/boards/board-" + board + "?page=1&keyword=%ED%95%9C")));
        assertThat(list.getResponse().getStatus()).isEqualTo(301);
        assertThat(list.getResponse().getHeader("Location")).isEqualTo("/main/boards/" + slug + "?page=1&keyword=%ED%95%9C");

        MvcResult detail = perform(get("/main/boards/board-{b}/posts/+{p}", board, post));
        assertThat(detail.getResponse().getStatus()).isEqualTo(301);
        assertThat(detail.getResponse().getHeader("Location")).as("게시글 id는 정규형으로").isEqualTo("/main/boards/" + slug + "/posts/" + post);

        MvcResult file = perform(get("/main/boards/board-{b}/posts/{p}/attachments/{a}", board, post, attachment));
        assertThat(file.getResponse().getStatus()).isEqualTo(301);
        assertThat(file.getResponse().getHeader("Location")).isEqualTo("/main/boards/" + slug + "/posts/" + post + "/attachments/" + attachment);
        MvcResult fileHead = perform(head("/main/boards/board-{b}/posts/{p}/attachments/{a}", board, post, attachment));
        assertThat(fileHead.getResponse().getStatus()).isEqualTo(301);
        assertThat(perform(head("/main/boards/board-{b}", board)).getResponse().getStatus()).isEqualTo(301);

        assertThat(perform(get("/main/boards/board-{b}/posts/abc", board)).getResponse().getStatus()).as("읽을 수 없는 게시글 id는 301 없이 404")
                .isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT view_count FROM post WHERE id = ?", Long.class, post)).as("301은 조회수를 세지 않는다(200 한 번만)")
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT download_count FROM post_attachment WHERE id = ?", Long.class, attachment))
                .as("301은 다운로드로 세지 않는다").isZero();
    }

    @Test
    @DisplayName("비공개·삭제·다른 사이트 게시판은 기본 슬러그도 사용자 슬러그도 301 없이 같은 404(새 슬러그 비노출)")
    void hiddenBoards_noRedirect() throws Exception {
        GeneratedKeyHolder siteKey = new GeneratedKeyHolder();
        jdbc.update(con -> con.prepareStatement("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, default_yn)"
                + " VALUES ('slugother', '다른 사이트', 'PUBLIC', 0, 1, 0)", Statement.RETURN_GENERATED_KEYS), siteKey);
        otherSiteId = siteKey.getKey().longValue();
        for (long board : List.of(
                insertBoard(uniqueSlug(), false, false, null),
                insertBoard(uniqueSlug(), true, true, null),
                insertBoard(uniqueSlug(), true, false, otherSiteId))) {
            String slug = jdbc.queryForObject("SELECT slug FROM board WHERE id = ?", String.class, board);
            MvcResult moved = perform(get("/main/boards/board-{b}", board));
            assertThat(moved.getResponse().getStatus()).isEqualTo(404);
            assertThat(moved.getResponse().getHeader("Location")).isNull();
            assertThat(perform(get("/main/boards/" + slug)).getResponse().getStatus()).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("공개 목록·상세·메인의 링크는 현재 슬러그를 쓴다")
    void templateLinksUseCurrentSlug() throws Exception {
        String slug = uniqueSlug();
        long board = insertBoard(slug, true, false, null);
        long post = insertPost(board);

        String listHtml = perform(get("/main/boards/" + slug)).getResponse().getContentAsString();
        assertThat(listHtml).contains("href=\"/main/boards/" + slug + "/posts/" + post + "\"").doesNotContain("/main/boards/" + board + "\"");
        String detailHtml = perform(get("/main/boards/" + slug + "/posts/" + post)).getResponse().getContentAsString();
        assertThat(detailHtml).contains("href=\"/main/boards/" + slug + "\"");
        String homeHtml = perform(get("/main")).getResponse().getContentAsString();
        assertThat(homeHtml).contains("href=\"/main/boards/" + slug + "/posts/" + post + "\"");

        long plain = insertBoard(null, true, false, null);
        long plainPost = insertPost(plain);
        assertThat(perform(get("/main/boards/board-{b}", plain)).getResponse().getContentAsString())
                .contains("href=\"/main/boards/board-" + plain + "/posts/" + plainPost + "\"");
    }
}
