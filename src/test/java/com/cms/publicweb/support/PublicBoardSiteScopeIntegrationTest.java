package com.cms.publicweb.support;

import com.cms.common.storage.FileStorage;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.MediaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;

/**
 * 사이트별 게시판·공지의 공개 노출 경계(PLAN-site-menu.md PR ④): 공통(site_id NULL) 게시판은 모든 사이트에, 전용 게시판은 그 사이트에만 보이고 다른
 * 사이트에서는 없는 ID와 <b>같은 404</b>다. 공지는 사이트 전용 공지 게시판이 있으면 그것(공통 대체), 없으면 공통 공지 게시판이다. 사이트
 * {@code main}(시드)·{@code alpha}·{@code beta}를 두고 목록·상세·메인(공지·새 글)·본문 이미지를 교차 확인한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PublicBoardSiteScopeIntegrationTest extends MariaDbContainerSupport {

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileStorage fileStorage;

    private String ip;
    private long alpha;
    private long beta;
    private long commonNoticeBoard;
    private final java.util.List<String> storageKeys = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        int n = IP_SEQUENCE.getAndIncrement();
        ip = "10.98." + (n / 250) + "." + (n % 250 + 1);
        cleanAll();
        alpha = site("alpha", true);
        beta = site("beta", true);
        commonNoticeBoard = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
    }

    @AfterEach
    void cleanUp() {
        cleanAll();
    }

    private void cleanAll() {
        jdbc.update("DELETE r FROM content_image_ref r JOIN content_image i ON i.id = r.image_id WHERE i.uploader_id = 'psc-test'");
        jdbc.update("DELETE FROM content_image WHERE uploader_id = 'psc-test'");
        storageKeys.forEach(fileStorage::delete);
        storageKeys.clear();
        jdbc.update("DELETE FROM post WHERE title LIKE 'psc-%'");
        jdbc.update("DELETE FROM board WHERE site_id IS NOT NULL OR name LIKE 'psc-%'");
        MediaTestSupport.cleanLibrary(jdbc, fileStorage);
        jdbc.update("DELETE FROM site WHERE id > 2");
    }

    private long site(String key, boolean use) {
        jdbc.update("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, default_yn) VALUES (?, ?, 'PUBLIC', 0, ?, 0)", key, key, use);
        return jdbc.queryForObject("SELECT id FROM site WHERE site_key = ?", Long.class, key);
    }

    private long board(String name, Long siteId, String boardKey) {
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted, board_key, site_id, create_date, update_date) VALUES (?, 1, 1, 0, ?, ?, NOW(), NOW())",
                name, boardKey, siteId);
        return jdbc.queryForObject("SELECT id FROM board WHERE name = ?", Long.class, name);
    }

    private long post(long boardId, String title) {
        jdbc.update("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, create_date, update_date) VALUES (?, ?, '<p>본문</p>', 1, 0, 'admin', NOW(), NOW())",
                boardId, title);
        return jdbc.queryForObject("SELECT MAX(id) FROM post", Long.class);
    }

    private RequestPostProcessor fromIp() {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private int status(String path) throws Exception {
        return mockMvc.perform(get(path).with(fromIp())).andReturn().getResponse().getStatus();
    }

    private String html(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path).with(fromIp())).andReturn();
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    @Test
    @DisplayName("게시판 목록·상세: 공통은 모든 사이트에, 전용은 그 사이트에만 — 다른 사이트에서는 없는 ID와 같은 404(GET·HEAD)")
    void boardVisibilityFollowsSite() throws Exception {
        long common = board("psc-공통", null, null);
        long onlyAlpha = board("psc-알파", alpha, null);
        long commonPost = post(common, "psc-공통글");
        long alphaPost = post(onlyAlpha, "psc-알파글");

        assertThat(html("/alpha/boards/board-" + common)).contains("psc-공통글");
        assertThat(html("/beta/boards/board-" + common)).contains("psc-공통글");
        assertThat(html("/alpha/boards/board-" + onlyAlpha)).contains("psc-알파글");
        assertThat(html("/alpha/boards/board-" + onlyAlpha + "/posts/" + alphaPost)).contains("psc-알파글");

        long missing = onlyAlpha + 100000;
        int expected = status("/beta/boards/board-" + missing);
        assertThat(expected).isEqualTo(404);
        assertThat(status("/beta/boards/board-" + onlyAlpha)).as("다른 사이트 전용 목록").isEqualTo(expected);
        assertThat(status("/main/boards/board-" + onlyAlpha)).as("기본 사이트에서 전용 목록").isEqualTo(expected);
        assertThat(status("/beta/boards/board-" + onlyAlpha + "/posts/" + alphaPost)).as("다른 사이트 전용 상세").isEqualTo(expected);
        assertThat(mockMvc.perform(head("/beta/boards/board-" + onlyAlpha).with(fromIp())).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(status("/beta/boards/board-" + common + "/posts/" + commonPost)).isEqualTo(200);
    }

    @Test
    @DisplayName("공지: 전용 공지 게시판이 없는 사이트는 공통 공지를, 있는 사이트는 전용만(공통 대체) 본다. 전용 공지 글은 다른 사이트 /notices/{id}에서 404")
    void noticeResolvesPerSite() throws Exception {
        post(commonNoticeBoard, "psc-공통공지");
        long alphaNotice = board("psc-알파공지", alpha, "NOTICE:" + alpha);
        long alphaNoticePost = post(alphaNotice, "psc-알파공지글");

        assertThat(html("/beta/notices")).contains("psc-공통공지").doesNotContain("psc-알파공지글");
        assertThat(html("/main/notices")).contains("psc-공통공지");
        assertThat(html("/alpha/notices")).contains("psc-알파공지글").doesNotContain("psc-공통공지");

        assertThat(status("/alpha/notices/" + alphaNoticePost)).isEqualTo(200);
        assertThat(status("/beta/notices/" + alphaNoticePost)).as("다른 사이트의 전용 공지 글").isEqualTo(404);
        assertThat(status("/main/notices/" + alphaNoticePost)).isEqualTo(404);
    }

    @Test
    @DisplayName("전용 공지 게시판을 지우면(소속·키 해제) 그 사이트는 공통 공지로 돌아간다")
    void noticeFallsBackAfterSiteNoticeBoardRemoved() throws Exception {
        post(commonNoticeBoard, "psc-공통공지");
        long alphaNotice = board("psc-알파공지", alpha, "NOTICE:" + alpha);
        post(alphaNotice, "psc-알파공지글");
        assertThat(html("/alpha/notices")).doesNotContain("psc-공통공지");

        jdbc.update("DELETE FROM post WHERE board_id = ?", alphaNotice);
        jdbc.update("UPDATE board SET deleted = 1, site_id = NULL, board_key = NULL WHERE id = ?", alphaNotice);

        assertThat(html("/alpha/notices")).contains("psc-공통공지");
    }

    @Test
    @DisplayName("메인: 공지 섹션은 해석된 공지 게시판, 새 글은 공통 + 그 사이트 전용 일반 게시판 글(공지 게시판 글 제외)")
    void homeSectionsFollowSite() throws Exception {
        post(commonNoticeBoard, "psc-공통공지");
        long alphaNotice = board("psc-알파공지", alpha, "NOTICE:" + alpha);
        post(alphaNotice, "psc-알파공지글");
        long common = board("psc-공통", null, null);
        long onlyAlpha = board("psc-알파", alpha, null);
        long onlyBeta = board("psc-베타", beta, null);
        post(common, "psc-공통새글");
        post(onlyAlpha, "psc-알파새글");
        post(onlyBeta, "psc-베타새글");

        String alphaHome = html("/alpha");
        assertThat(alphaHome).contains("psc-알파공지글", "psc-공통새글", "psc-알파새글");
        assertThat(alphaHome).doesNotContain("psc-공통공지", "psc-베타새글");

        String betaHome = html("/beta");
        assertThat(betaHome).contains("psc-공통공지", "psc-공통새글", "psc-베타새글");
        assertThat(betaHome).doesNotContain("psc-알파공지글", "psc-알파새글");

        String mainHome = html("/main");
        assertThat(mainHome).contains("psc-공통공지", "psc-공통새글");
        assertThat(mainHome).doesNotContain("psc-알파새글", "psc-베타새글", "psc-알파공지글");
    }

    @Test
    @DisplayName("본문 이미지: 사이트 전용 게시판의 공개 글이 참조하는 이미지는 소속 사이트가 활성일 때만 익명 200이고, 비활성이면 404 — 공통 게시판은 영향 없음")
    void contentImageHiddenWhenOwningSiteInactive() throws Exception {
        long onlyAlpha = board("psc-알파", alpha, null);
        long common = board("psc-공통", null, null);
        long alphaPost = post(onlyAlpha, "psc-알파글");
        long commonPost = post(common, "psc-공통글");
        long alphaImage = image(onlyAlpha, alphaPost);
        long commonImage = image(common, commonPost);

        assertThat(status("/content-images/" + alphaImage)).as("활성").isEqualTo(200);
        assertThat(status("/content-images/" + commonImage)).isEqualTo(200);

        jdbc.update("UPDATE site SET use_yn = 0 WHERE id = ?", alpha);
        assertThat(status("/content-images/" + alphaImage)).as("소속 사이트 비활성").isEqualTo(404);
        assertThat(status("/content-images/" + commonImage)).as("공통 게시판 이미지는 영향 없음").isEqualTo(200);

        jdbc.update("UPDATE site SET use_yn = 1 WHERE id = ?", alpha);
        assertThat(status("/content-images/" + alphaImage)).as("재활성").isEqualTo(200);
    }

    /** 게시판 출처({@code BOARD:boardId}) 이미지 한 장(파일 포함)과 그 글의 참조를 만든다. */
    private long image(long boardId, long postId) throws Exception {
        byte[] png = com.cms.common.image.ImageFileValidatorTest.png(8, 8);
        String key = fileStorage.store(png, "image.png");
        storageKeys.add(key);
        jdbc.update("INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, create_date, scope_type, scope_id)"
                + " VALUES (?, 'image/png', ?, 'psc-test', NOW(6), 'BOARD', ?)", key, png.length, boardId);
        long imageId = jdbc.queryForObject("SELECT id FROM content_image WHERE storage_key = ?", Long.class, key);
        jdbc.update("INSERT INTO content_image_ref (owner_type, owner_id, image_id) VALUES ('POST', ?, ?)", postId, imageId);
        return imageId;
    }
}
