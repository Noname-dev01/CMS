package com.cms.publicweb.home;

import com.cms.common.storage.FileStorage;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 대표 이미지의 공개 메인 썸네일과 상세 {@code og:image}(PLAN-home-cover-og-image.md). 공개 판정은 기존 쿼리가 그대로 하고, 이 시험은
 * ① 메인 "새 글"만 썸네일을 쓰고(공지 섹션은 안 씀) ② 비공개·미노출·예약·삭제 글의 이미지 id가 응답에 없고 ③ 메인 한 번의 썸네일 참조가 5건 이하이며
 * ④ 상세 메타가 공개 글의 대표 썸네일 절대 URL을 가리키고 그 경로가 익명으로 열리는지를 실제 스택으로 확인한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class CoverImageExposureIntegrationTest extends MariaDbContainerSupport {

    private static final String UPLOADER = "cover-exposure-test";
    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileStorage fileStorage;
    @Value("${app.base-url}") String baseUrl;

    private final List<Long> boardIds = new ArrayList<>();
    private final List<Long> postIds = new ArrayList<>();
    private final List<String> rootKeys = new ArrayList<>();
    private long noticeBoardId;
    private String ip;

    @BeforeEach
    void setUp() {
        int n = IP_SEQUENCE.getAndIncrement();
        ip = "10.92." + (n / 250) + "." + (n % 250 + 1);
        noticeBoardId = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
    }

    @AfterEach
    void cleanUp() {
        for (Long postId : postIds) {
            jdbc.update("DELETE FROM content_image_ref WHERE owner_type = 'POST' AND owner_id = ?", postId);
            jdbc.update("DELETE FROM post WHERE id = ?", postId);
        }
        jdbc.update("DELETE FROM content_image WHERE uploader_id = ?", UPLOADER);
        jdbc.update("UPDATE content_image_usage SET total_bytes = (SELECT COALESCE(SUM(file_size), 0) FROM content_image),"
                + " total_count = (SELECT COUNT(*) FROM content_image) WHERE id = 1");
        for (Long boardId : boardIds) {
            jdbc.update("DELETE FROM board WHERE id = ?", boardId);
        }
        rootKeys.forEach(key -> {
            try {
                fileStorage.delete(key);
            } catch (RuntimeException ignored) {
            }
        });
    }

    private RequestPostProcessor fromTestIp() {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private long insertBoard(boolean publicYn) {
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted, create_date, update_date) VALUES (?, ?, 1, 0, NOW(6), NOW(6))",
                "COV_" + System.nanoTime(), publicYn);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM board", Long.class);
        boardIds.add(id);
        return id;
    }

    private long insertImage(long boardId) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", out);
        byte[] png = out.toByteArray();
        String key = fileStorage.store(png, "image.png");
        rootKeys.add(key);
        jdbc.update("INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, scope_type, scope_id, namespace, create_date)"
                + " VALUES (?, 'image/png', ?, ?, 'BOARD', ?, '', NOW(6))", key, png.length, UPLOADER, boardId);
        return jdbc.queryForObject("SELECT id FROM content_image WHERE storage_key = ?", Long.class, key);
    }

    /** 대표가 있으면 그 글이 참조하는 이미지로 만든다(PR 5 불변식). */
    private long insertPost(long boardId, String title, boolean useYn, boolean deleted, LocalDateTime publishStart, Long coverImageId) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO post (board_id, title, content, use_yn, deleted, publish_start, cover_image_id, author_id, create_date, update_date)"
                        + " VALUES (?, ?, '<p>본문</p>', ?, ?, ?, ?, 'cov-admin', ?, ?)",
                boardId, title, useYn, deleted, publishStart, coverImageId, now, now);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM post WHERE board_id = ?", Long.class, boardId);
        postIds.add(id);
        if (coverImageId != null) {
            jdbc.update("INSERT INTO content_image_ref (owner_type, owner_id, image_id) VALUES ('POST', ?, ?)", id, coverImageId);
        }
        return id;
    }

    private String home() throws Exception {
        return mockMvc.perform(get("/main").with(fromTestIp())).andReturn().getResponse().getContentAsString();
    }

    private static String thumbRef(long imageId) {
        return "/content-images/" + imageId + "?size=thumb";
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    @DisplayName("메인 새 글: 대표가 있는 글만 썸네일 칸과 /content-images/{id}?size=thumb를 그리고, 없는 글은 칸이 없다")
    void latestPostsShowThumbOnlyWhenCoverExists() throws Exception {
        long board = insertBoard(true);
        long image = insertImage(board);
        insertPost(board, "cov-with", true, false, null, image);
        insertPost(board, "cov-without", true, false, null, null);

        String html = home();

        assertThat(html).contains("style=\"background-image:url(" + thumbRef(image) + ")\"");
        assertThat(count(html, "class=\"home-post-thumb\"")).as("대표가 있는 글 하나만 칸을 그린다").isEqualTo(1);
        assertThat(html).as("장식 칸 — 접근성 트리에서 숨긴다").contains("class=\"home-post-thumb\" aria-hidden=\"true\"");
    }

    @Test
    @DisplayName("공지 섹션은 대표가 있어도 썸네일을 그리지 않는다(메인 썸네일 요청을 새 글 5건 이하로 묶는다)")
    void noticeSectionNeverShowsThumb() throws Exception {
        long image = insertImage(noticeBoardId);
        insertPost(noticeBoardId, "cov-notice", true, false, null, image);

        String html = home();

        assertThat(html).contains("cov-notice").doesNotContain(thumbRef(image));
    }

    @Test
    @DisplayName("공개 불변식: 비공개 게시판·미노출·삭제·예약(시작 전) 글의 대표 이미지 id는 메인 응답에 나오지 않는다")
    void nonPublishedCoversNeverAppear() throws Exception {
        long privateBoard = insertBoard(false);
        long open = insertBoard(true);
        long privateImage = insertImage(privateBoard);
        long hiddenImage = insertImage(open);
        long deletedImage = insertImage(open);
        long scheduledImage = insertImage(open);
        long visibleImage = insertImage(open);
        insertPost(privateBoard, "cov-private", true, false, null, privateImage);
        insertPost(open, "cov-hidden", false, false, null, hiddenImage);
        insertPost(open, "cov-deleted", true, true, null, deletedImage);
        insertPost(open, "cov-scheduled", true, false, LocalDateTime.now().plusDays(1), scheduledImage);
        insertPost(open, "cov-visible", true, false, null, visibleImage);

        String html = home();

        assertThat(html).contains(thumbRef(visibleImage));
        assertThat(html).doesNotContain(thumbRef(privateImage), thumbRef(hiddenImage), thumbRef(deletedImage), thumbRef(scheduledImage));
    }

    @Test
    @DisplayName("메인 한 번의 썸네일 참조는 새 글 섹션 크기(5건)를 넘지 않는다 — content-image 버킷 소비 상한")
    void thumbReferencesAreBoundedBySectionSize() throws Exception {
        long board = insertBoard(true);
        for (int i = 0; i < 7; i++) {
            insertPost(board, "cov-many-" + i, true, false, null, insertImage(board));
        }

        assertThat(count(home(), "size=thumb")).isEqualTo(PublicHomeService.SECTION_SIZE);
    }

    @Test
    @DisplayName("상세 og:image: 대표가 있는 공개 글은 app.base-url 기반 절대 URL을 싣고, 그 경로가 익명으로 200 image/*로 열린다")
    void detailHasAbsoluteOgImageThatOpensAnonymously() throws Exception {
        long board = insertBoard(true);
        long image = insertImage(board);
        long post = insertPost(board, "cov-og", true, false, null, image);

        String html = mockMvc.perform(get("/main/boards/board-{b}/posts/{p}", board, post).with(fromTestIp()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String expected = baseUrl.replaceAll("/+$", "") + thumbRef(image);
        assertThat(html).contains("<meta property=\"og:image\" content=\"" + expected + "\">");
        // 크롤러가 같은 경로를 익명으로 요청한다 — 호스트는 MockMvc 밖이라 경로만 따라간다
        mockMvc.perform(get("/content-images/{id}", image).param("size", "thumb").with(fromTestIp()))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("image/*"));
    }

    @Test
    @DisplayName("상세 og:image: 대표가 없는 글에는 메타가 없고, 미노출·예약 글은 404라 메타 응답 자체가 없다")
    void detailWithoutCoverOrPublicationHasNoOgImage() throws Exception {
        long board = insertBoard(true);
        long image = insertImage(board);
        long plain = insertPost(board, "cov-plain", true, false, null, null);
        long hidden = insertPost(board, "cov-hidden-detail", false, false, null, image);
        long scheduled = insertPost(board, "cov-scheduled-detail", true, false, LocalDateTime.now().plusDays(1), insertImage(board));

        String plainHtml = mockMvc.perform(get("/main/boards/board-{b}/posts/{p}", board, plain).with(fromTestIp()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(plainHtml).doesNotContain("og:image");
        for (long id : new long[] {hidden, scheduled}) {
            String body = mockMvc.perform(get("/main/boards/board-{b}/posts/{p}", board, id).with(fromTestIp()))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("og:image");
        }
    }
}
