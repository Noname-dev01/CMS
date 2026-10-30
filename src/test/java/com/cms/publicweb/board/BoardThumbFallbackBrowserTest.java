package com.cms.publicweb.board;

import com.cms.common.storage.FileStorage;
import com.cms.support.BrowserTestSupport;
import com.cms.support.CmsTestApplication;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 갤러리형 목록의 썸네일 실패 대체(`/js/public/board-thumb-fallback.js`)를 <b>실제 브라우저</b>로 고정한다 — MockMvc는 화면 JS·지연 로딩을 재현하지 못한다.
 * ① 실패한 썸네일(여기서는 파일이 없는 이미지 → 404)은 대표 없는 글과 같은 자리표시로 바뀌고 정상 썸네일은 그대로다
 * ② 아직 요청되지 않은 화면 아래쪽 칸은 미리 바꾸지 않고(과잉 대체 방지), 스크롤해서 나중에 실패한 칸만 바꾼다 — DOMContentLoaded 정리만으로는
 * 늦은 실패를 못 잡으므로 캡처 단계 error 리스너가 필요하다. 익명 컨텍스트로 연다(관리자는 미리보기 권한으로 이미지를 통과시킨다).
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BoardThumbFallbackBrowserTest extends BrowserTestSupport {

    private static final String UPLOADER = "thumb-fallback-browser-test";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileStorage fileStorage;

    private final List<Long> boardIds = new ArrayList<>();
    private final List<String> rootKeys = new ArrayList<>();
    private BrowserContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        for (Long boardId : boardIds) {
            jdbc.update("DELETE FROM content_image_ref WHERE owner_type = 'POST' AND owner_id IN (SELECT id FROM post WHERE board_id = ?)", boardId);
            jdbc.update("DELETE FROM post WHERE board_id = ?", boardId);
        }
        jdbc.update("DELETE r FROM content_image_ref r JOIN content_image i ON i.id = r.image_id WHERE i.uploader_id = ?", UPLOADER);
        jdbc.update("DELETE FROM content_image WHERE uploader_id = ?", UPLOADER);
        jdbc.update("UPDATE content_image_usage SET total_bytes = (SELECT COALESCE(SUM(file_size), 0) FROM content_image)"
                + " + (SELECT COALESCE(SUM(file_size), 0) FROM content_image_thumb), total_count = (SELECT COUNT(*) FROM content_image) WHERE id = 1");
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

    private long galleryBoard() {
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted, board_type, list_page_size, create_date, update_date)"
                + " VALUES (?, 1, 1, 0, 'GALLERY', 50, NOW(6), NOW(6))", "TFB_" + System.nanoTime());
        long id = jdbc.queryForObject("SELECT MAX(id) FROM board", Long.class);
        boardIds.add(id);
        return id;
    }

    /** 실제 파일이 있는 이미지 행 — 썸네일 요청은 원본(작은 이미지)으로 200이다. */
    private long imageWithFile(long boardId) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", out);
        byte[] png = out.toByteArray();
        String key = fileStorage.store(png, "image.png");
        rootKeys.add(key);
        return insertImageRow(boardId, key, png.length);
    }

    /** 파일이 없는 이미지 행 — 공개 판정은 통과하지만 파일 열기에서 404가 된다(썸네일 실패를 결정적으로 만든다). */
    private long imageWithoutFile(long boardId) {
        return insertImageRow(boardId, "missing/" + System.nanoTime() + ".png", 100);
    }

    private long insertImageRow(long boardId, String key, long size) {
        jdbc.update("INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, scope_type, scope_id, namespace, create_date)"
                + " VALUES (?, 'image/png', ?, ?, 'BOARD', ?, '', NOW(6))", key, size, UPLOADER, boardId);
        return jdbc.queryForObject("SELECT id FROM content_image WHERE storage_key = ?", Long.class, key);
    }

    private void post(long boardId, String title, LocalDateTime createDate, Long coverImageId) {
        jdbc.update("INSERT INTO post (board_id, title, content, use_yn, deleted, cover_image_id, author_id, create_date, update_date)"
                + " VALUES (?, ?, '<p>본문</p>', 1, 0, ?, 'tfb-admin', ?, ?)", boardId, title, coverImageId, createDate, createDate);
        if (coverImageId != null) {
            long postId = jdbc.queryForObject("SELECT MAX(id) FROM post WHERE board_id = ?", Long.class, boardId);
            jdbc.update("INSERT INTO content_image_ref (owner_type, owner_id, image_id) VALUES ('POST', ?, ?)", postId, coverImageId);
        }
    }

    private String cardState(Page page, String title) {
        return (String) page.evaluate("t => { const li = [...document.querySelectorAll('.board-gallery-item')]"
                + ".find(x => x.querySelector('.board-gallery-title').textContent.includes(t)); if (!li) return 'none';"
                + " const img = li.querySelector('.board-thumb img'); if (img) return img.complete && img.naturalWidth > 0 ? 'loaded' : 'img';"
                + " return li.querySelector('.board-thumb-empty') ? 'placeholder' : 'none'; }", title);
    }

    @Test
    @DisplayName("실패한 썸네일은 자리표시로 바뀌고 정상 썸네일·대표 없는 글은 그대로다 — 깨진 이미지가 남지 않는다")
    void failedThumbnailBecomesPlaceholder() throws Exception {
        long board = galleryBoard();
        LocalDateTime now = LocalDateTime.now();
        post(board, "TFB-정상", now, imageWithFile(board));
        post(board, "TFB-실패", now.minusMinutes(1), imageWithoutFile(board));
        post(board, "TFB-대표없음", now.minusMinutes(2), null);

        context = newContext();
        Page page = context.newPage();
        page.navigate("http://localhost:" + port + "/main/boards/board-" + board);
        page.waitForFunction("() => document.querySelectorAll('.board-thumb-empty').length === 2"
                + " && [...document.querySelectorAll('.board-thumb img')].every(i => i.complete && i.naturalWidth > 0)");

        assertThat(cardState(page, "TFB-정상")).isEqualTo("loaded");
        assertThat(cardState(page, "TFB-실패")).as("실패한 썸네일은 자리표시로").isEqualTo("placeholder");
        assertThat(cardState(page, "TFB-대표없음")).isEqualTo("placeholder");
        assertThat((Number) page.evaluate("() => [...document.querySelectorAll('img')].filter(i => i.complete && i.naturalWidth === 0).length"))
                .as("깨진 이미지가 남지 않는다").isEqualTo(0);
    }

    @Test
    @DisplayName("화면 아래쪽 칸은 요청 전에 미리 바꾸지 않고, 스크롤해서 나중에 실패한 칸만 자리표시로 바꾼다(정상 칸은 정상 로딩)")
    void lazyLoadedFailureIsReplacedLaterWithoutTouchingPendingImages() throws Exception {
        long board = galleryBoard();
        LocalDateTime base = LocalDateTime.now();
        long lateBroken = imageWithoutFile(board);
        long lateGood = imageWithFile(board);
        // 가장 오래된 두 글(목록 맨 아래)만 대표가 있고 위쪽 48칸(목록 크기 50 한도)은 대표 없는 자리표시다. 좁은 화면(1~2열)이라 페이지가 길어
        // 아래쪽 두 칸이 Chrome의 지연 로딩 선행 거리(최대 약 2,500px) 밖에 있어 처음에 요청되지 않는다 — 넓은 화면 4열 42칸에서는 미리 요청됐다
        post(board, "TFB-아래-실패", base.minusMinutes(50), lateBroken);
        post(board, "TFB-아래-정상", base.minusMinutes(49), lateGood);
        for (int i = 0; i < 48; i++) {
            post(board, "TFB-채움-" + i, base.minusMinutes(i), null);
        }

        context = newContext(new Browser.NewContextOptions().setViewportSize(400, 700));
        Page page = context.newPage();
        List<String> imageRequests = new CopyOnWriteArrayList<>();
        page.onRequest(request -> {
            if (request.url().contains("/content-images/")) {
                imageRequests.add(request.url());
            }
        });
        page.navigate("http://localhost:" + port + "/main/boards/board-" + board);
        page.waitForLoadState();

        assertThat(imageRequests).as("화면 아래쪽 썸네일은 아직 요청되지 않았다(지연 로딩)").isEmpty();
        assertThat(cardState(page, "TFB-아래-실패")).as("요청 전 칸을 미리 자리표시로 바꾸지 않는다").isEqualTo("img");
        assertThat(cardState(page, "TFB-아래-정상")).isEqualTo("img");

        page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
        page.waitForFunction("() => { const li = [...document.querySelectorAll('.board-gallery-item')]"
                + ".find(x => x.textContent.includes('TFB-아래-실패')); return li && li.querySelector('.board-thumb-empty'); }");
        page.waitForFunction("() => { const li = [...document.querySelectorAll('.board-gallery-item')]"
                + ".find(x => x.textContent.includes('TFB-아래-정상')); const img = li && li.querySelector('img'); return img && img.complete && img.naturalWidth > 0; }");

        assertThat(imageRequests).as("스크롤 뒤에야 두 썸네일을 요청했다")
                .anyMatch(url -> url.contains("/content-images/" + lateBroken + "?size=thumb"))
                .anyMatch(url -> url.contains("/content-images/" + lateGood + "?size=thumb"));
        assertThat(cardState(page, "TFB-아래-실패")).as("늦게 실패한 칸은 자리표시로").isEqualTo("placeholder");
        assertThat(cardState(page, "TFB-아래-정상")).isEqualTo("loaded");
    }
}
