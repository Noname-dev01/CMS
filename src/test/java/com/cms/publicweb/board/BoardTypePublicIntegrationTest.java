package com.cms.publicweb.board;

import com.cms.common.storage.FileStorage;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 게시판 유형(LIST·GALLERY·WEBZINE)별 공개 목록 화면 시험(PLAN-board-definition.md PR 6 D4~D7). 글 목록의 모양만 유형에 따라 다르고 검색·페이지 이동·
 * 고정 영역·공개 판정은 그대로여야 한다. 행은 JDBC로 직접 만들고 요청마다 고유 원격 IP를 쓴다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class BoardTypePublicIntegrationTest extends MariaDbContainerSupport {

    private static final String UPLOADER = "board-type-pub-test";
    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileStorage fileStorage;

    private final List<Long> boardIds = new ArrayList<>();
    private final List<Long> postIds = new ArrayList<>();
    private final List<String> rootKeys = new ArrayList<>();
    private long noticeBoardId;
    private String noticeTypeBackup;
    private String ip;

    @BeforeEach
    void setUp() {
        int n = IP_SEQUENCE.getAndIncrement();
        ip = "10.91." + (n / 250) + "." + (n % 250 + 1);
        noticeBoardId = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
        noticeTypeBackup = jdbc.queryForObject("SELECT board_type FROM board WHERE id = ?", String.class, noticeBoardId);
    }

    @AfterEach
    void cleanUp() {
        for (Long postId : postIds) {
            jdbc.update("DELETE FROM content_image_ref WHERE owner_type = 'POST' AND owner_id = ?", postId);
            jdbc.update("DELETE FROM post WHERE id = ?", postId);
        }
        jdbc.update("DELETE r FROM content_image_ref r JOIN content_image i ON i.id = r.image_id WHERE i.uploader_id = ?", UPLOADER);
        jdbc.update("DELETE FROM content_image WHERE uploader_id = ?", UPLOADER);
        jdbc.update("UPDATE content_image_usage SET total_bytes = (SELECT COALESCE(SUM(file_size), 0) FROM content_image),"
                + " total_count = (SELECT COUNT(*) FROM content_image) WHERE id = 1");
        for (Long boardId : boardIds) {
            jdbc.update("DELETE FROM board WHERE id = ?", boardId);
        }
        jdbc.update("UPDATE board SET board_type = ? WHERE id = ?", noticeTypeBackup, noticeBoardId);
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

    private long insertBoard(String type, int pinMax) {
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted, board_type, pin_max_count, create_date, update_date)"
                + " VALUES (?, 1, 1, 0, ?, ?, NOW(6), NOW(6))", "BTP_" + System.nanoTime(), type, pinMax);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM board", Long.class);
        boardIds.add(id);
        return id;
    }

    /** 실제 파일이 있는 BOARD 출처 이미지 행을 만든다. 공개 판정은 글의 참조({@link #insertPost})가 정한다. */
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

    private long insertPost(long boardId, String title, String content, LocalDateTime createDate, boolean pinned, boolean useYn, Long coverImageId) {
        jdbc.update("INSERT INTO post (board_id, title, content, use_yn, deleted, pinned, cover_image_id, author_id, create_date, update_date)"
                        + " VALUES (?, ?, ?, ?, 0, ?, ?, 'btp-admin', ?, ?)",
                boardId, title, content, useYn, pinned, coverImageId, createDate, createDate);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM post WHERE board_id = ?", Long.class, boardId);
        postIds.add(id);
        if (coverImageId != null) {
            // 대표는 그 글이 참조하는 이미지다(PR 5 불변식) — 공개 판정이 이 참조를 따른다
            jdbc.update("INSERT INTO content_image_ref (owner_type, owner_id, image_id) VALUES ('POST', ?, ?)", id, coverImageId);
        }
        return id;
    }

    private long insertPost(long boardId, String title, LocalDateTime createDate, Long coverImageId) {
        return insertPost(boardId, title, "<p>본문</p>", createDate, false, true, coverImageId);
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
    @DisplayName("목록형(기본): 마크업이 현행 그대로다 — 격자·웹진 클래스·썸네일 요청이 없다")
    void listTypeKeepsCurrentMarkup() throws Exception {
        long board = insertBoard("LIST", 0);
        long image = insertImage(board);
        insertPost(board, "목록형-글", LocalDateTime.now(), image);

        String page = boardList(board);

        assertThat(page).contains("목록형-글");
        assertThat(count(page, "board-list-item")).isEqualTo(1);
        assertThat(page).doesNotContain("board-gallery").doesNotContain("board-webzine").doesNotContain("board-thumb")
                .doesNotContain("content-images").doesNotContain("<img");
        assertThat(page).as("썸네일이 없는 목록형에는 대체 스크립트를 싣지 않는다").doesNotContain("board-thumb-fallback.js").doesNotContain("<script");
    }

    @Test
    @DisplayName("알 수 없는 저장값은 목록형으로 렌더링된다 — 500이 아니다")
    void unknownTypeRendersAsList() throws Exception {
        long board = insertBoard("FUTURE", 0);
        insertPost(board, "미지-유형", LocalDateTime.now(), null);

        String page = boardList(board);

        assertThat(page).contains("미지-유형");
        assertThat(count(page, "board-list-item")).isEqualTo(1);
        assertThat(page).doesNotContain("board-gallery").doesNotContain("board-webzine");
    }

    @Test
    @DisplayName("갤러리형: 대표 있는 글은 지연 로딩 썸네일, 없는 글은 CSS 자리표시 — 익명이 그 썸네일 주소를 열 수 있다")
    void galleryShowsThumbnailsAndPlaceholders() throws Exception {
        long board = insertBoard("GALLERY", 0);
        long image = insertImage(board);
        LocalDateTime now = LocalDateTime.now();
        insertPost(board, "갤러리-대표있음", now, image);
        insertPost(board, "갤러리-대표없음", now.minusMinutes(1), null);

        String page = boardList(board);

        assertThat(count(page, "board-gallery-item")).isEqualTo(2);
        assertThat(page).doesNotContain("board-list-item").doesNotContain("board-webzine");
        String thumbUrl = "/content-images/" + image + "?size=thumb";
        assertThat(count(page, thumbUrl)).as("대표 있는 글만 썸네일").isEqualTo(1);
        assertThat(page).contains("loading=\"lazy\"").contains("alt=\"\"");
        assertThat(count(page, "board-thumb-empty")).as("대표 없는 글은 요청 없는 자리표시").isEqualTo(1);
        assertThat(page).doesNotContain("board-webzine-excerpt");
        assertThat(count(page, "<script src=\"/js/public/board-thumb-fallback.js\"></script>")).as("썸네일 실패 대체 스크립트 1개").isEqualTo(1);

        assertThat(mockMvc.perform(get("/content-images/{id}", image).param("size", "thumb").with(fromTestIp()))
                .andReturn().getResponse().getStatus()).as("목록이 가리키는 썸네일은 공개 판정을 통과한다").isEqualTo(200);
    }

    @Test
    @DisplayName("웹진형: 요약을 보이고(평문 이스케이프), 긴 본문은 120자에서 …로 자르며, 대표 없는 글은 자리표시다")
    void webzineShowsExcerpts() throws Exception {
        long board = insertBoard("WEBZINE", 0);
        long image = insertImage(board);
        LocalDateTime now = LocalDateTime.now();
        insertPost(board, "웹진-짧은", "<p>짧은 요약 본문입니다</p>", now, false, true, image);
        insertPost(board, "웹진-긴글", "<p>" + "가".repeat(200) + "</p>", now.minusMinutes(1), false, true, null);
        insertPost(board, "웹진-태그문자", "<p>&lt;script&gt;alert('x')&lt;/script&gt;</p>", now.minusMinutes(2), false, true, null);
        insertPost(board, "웹진-이미지만", "<p><img src=\"/content-images/999999\"></p>", now.minusMinutes(3), false, true, null);

        String page = boardList(board);

        assertThat(count(page, "board-webzine-item")).isEqualTo(4);
        assertThat(page).contains("짧은 요약 본문입니다");
        assertThat(page).contains("가".repeat(120) + "…").doesNotContain("가".repeat(121));
        assertThat(page).as("본문 속 태그 문자열은 이스케이프되어 출력된다")
                .doesNotContain("<script>alert('x')</script>").contains("&lt;script&gt;alert(");
        assertThat(count(page, "board-webzine-excerpt")).as("텍스트가 없는 글은 요약 줄이 없다").isEqualTo(3);
        assertThat(count(page, "/content-images/" + image + "?size=thumb")).isEqualTo(1);
        assertThat(count(page, "board-thumb-empty")).isEqualTo(3);
        assertThat(count(page, "<script src=\"/js/public/board-thumb-fallback.js\"></script>")).as("썸네일 실패 대체 스크립트 1개").isEqualTo(1);
    }

    @Test
    @DisplayName("고정 영역은 모든 유형에서 목록형 한 줄이다 — 고정 글의 썸네일은 일반 목록의 한 번뿐이고 요약은 계산하지 않는다")
    void pinnedAreaStaysOneLinePerType() throws Exception {
        for (String type : List.of("GALLERY", "WEBZINE")) {
            long board = insertBoard(type, 1);
            long image = insertImage(board);
            insertPost(board, type + "-고정글", "<p>고정 본문 요약</p>", LocalDateTime.now(), true, true, image);

            String page = boardList(board);

            assertThat(page).as(type).contains("board-pinned-list").contains("board-pinned-badge");
            assertThat(count(page, type + "-고정글")).as(type + ": 영역과 일반 목록에 모두").isEqualTo(2);
            assertThat(count(page, "board-list-item")).as(type + ": 영역은 목록형 한 줄").isEqualTo(1);
            assertThat(count(page, "/content-images/" + image + "?size=thumb")).as(type + ": 썸네일 요청은 일반 목록의 한 번").isEqualTo(1);
            assertThat(count(page, "고정 본문 요약")).as(type + ": 요약은 일반 목록에만(웹진) 또는 없음(갤러리)")
                    .isEqualTo(type.equals("WEBZINE") ? 1 : 0);
        }
    }

    @Test
    @DisplayName("검색 결과와 2페이지도 같은 유형의 마크업이다")
    void searchAndPagingKeepType() throws Exception {
        long board = insertBoard("GALLERY", 0);
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        for (int i = 0; i < 11; i++) {
            insertPost(board, String.format("페이징-%02d번", i), base.minusMinutes(i), null);
        }

        String page1 = boardList(board, "page", "1");
        assertThat(count(page1, "board-gallery-item")).isEqualTo(1);
        assertThat(page1).contains("2 / 2").doesNotContain("board-list-item");

        String search = boardList(board, "keyword", "페이징-03");
        assertThat(count(search, "board-gallery-item")).isEqualTo(1);
        assertThat(search).contains("페이징-03번").doesNotContain("board-list-item");
    }

    @Test
    @DisplayName("공지 게시판도 유형을 따른다 — 갤러리형으로 바꾸면 같은 화면 한 벌이 격자로 나온다")
    void noticeBoardFollowsType() throws Exception {
        jdbc.update("UPDATE board SET board_type = 'GALLERY' WHERE id = ?", noticeBoardId);
        insertPost(noticeBoardId, "공지-갤러리", LocalDateTime.now().plusYears(1), null);

        String page = boardList(noticeBoardId);

        assertThat(page).contains("공지-갤러리").contains("board-gallery-item").doesNotContain("board-list-item");
    }

    @Test
    @DisplayName("비노출 글은 갤러리 목록에 나오지 않고 그 대표 썸네일 주소도 404다 (공개 판정 그대로)")
    void hiddenPostCoverStaysHidden() throws Exception {
        long board = insertBoard("GALLERY", 0);
        long hiddenImage = insertImage(board);
        insertPost(board, "숨김-글", "<p>본문</p>", LocalDateTime.now(), false, false, hiddenImage);
        insertPost(board, "보임-글", LocalDateTime.now().minusMinutes(1), null);

        String page = boardList(board);

        assertThat(page).contains("보임-글").doesNotContain("숨김-글").doesNotContain("/content-images/" + hiddenImage);
        assertThat(mockMvc.perform(get("/content-images/{id}", hiddenImage).param("size", "thumb").with(fromTestIp()))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("제목은 모든 유형에서 이스케이프되어 출력된다")
    void titlesAreEscapedInEveryType() throws Exception {
        for (String type : List.of("LIST", "GALLERY", "WEBZINE")) {
            long board = insertBoard(type, 0);
            insertPost(board, "<script>alert('" + type + "')</script>", LocalDateTime.now(), null);

            String page = boardList(board);

            assertThat(page).as(type).doesNotContain("<script>alert('" + type + "')</script>").contains("&lt;script&gt;");
        }
    }
}
