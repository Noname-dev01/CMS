package com.cms.publicweb.home;

import com.cms.common.storage.FileStorage;
import com.cms.support.BrowserTestSupport;
import com.cms.support.CmsTestApplication;
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
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공개 메인 "새 글" 썸네일의 실패 대체를 <b>실제 브라우저</b>로 고정한다(PLAN-home-cover-og-image.md D2). 메인은 스크립트가 없어, 칸 자체가 자리표시 배경이고
 * 안쪽 span의 CSS 배경이 그 위를 덮는 방식이 실패(404·429)한 이미지에서 깨진 아이콘을 남기지 않는지가 이 시험의 핵심이다(`<img alt="">`는 Chromium이 깨진 아이콘을 그려 실측으로 기각) — MockMvc는 렌더링을 보지 못한다.
 * 비교 기준은 같은 칸에서 썸네일 span을 뺀 모양과의 화면 일치다(깨진 아이콘이 그려지면 달라진다). 익명 컨텍스트로 연다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HomeThumbFallbackBrowserTest extends BrowserTestSupport {

    private static final String UPLOADER = "home-thumb-browser-test";

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

    private long board() {
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted, create_date, update_date) VALUES (?, 1, 1, 0, NOW(6), NOW(6))",
                "HTB_" + System.nanoTime());
        long id = jdbc.queryForObject("SELECT MAX(id) FROM board", Long.class);
        boardIds.add(id);
        return id;
    }

    private long imageWithFile(long boardId) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 40; x++) {
            for (int y = 0; y < 30; y++) {
                image.setRGB(x, y, 0xD9534F); // 자리표시(회색 바탕)와 눈에 띄게 다른 색
            }
        }
        ImageIO.write(image, "png", out);
        byte[] png = out.toByteArray();
        String key = fileStorage.store(png, "image.png");
        rootKeys.add(key);
        return insertImageRow(boardId, key, png.length);
    }

    /** 파일이 없는 이미지 행 — 공개 판정은 통과하지만 파일 열기에서 404가 된다(실패를 결정적으로 만든다). */
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
                + " VALUES (?, ?, '<p>본문</p>', 1, 0, ?, 'htb-admin', ?, ?)", boardId, title, coverImageId, createDate, createDate);
        if (coverImageId != null) {
            long postId = jdbc.queryForObject("SELECT MAX(id) FROM post WHERE board_id = ?", Long.class, boardId);
            jdbc.update("INSERT INTO content_image_ref (owner_type, owner_id, image_id) VALUES ('POST', ?, ?)", postId, coverImageId);
        }
    }

    @Test
    @DisplayName("실패한 썸네일은 깨진 아이콘 없이 자리표시 칸만 남고(썸네일 배경을 뺀 칸과 화면 일치), 정상 썸네일은 그대로 그려진다")
    void failedThumbnailLeavesOnlyPlaceholder() throws Exception {
        long board = board();
        LocalDateTime now = LocalDateTime.now();
        post(board, "HTB-정상", now, imageWithFile(board));
        post(board, "HTB-실패", now.minusMinutes(1), imageWithoutFile(board));

        context = newContext();
        Page page = context.newPage();
        page.navigate("http://localhost:" + port + "/main");
        // 두 썸네일 요청(성공 1·실패 1)이 끝나고 두 프레임이 그려질 때까지 기다린다 — 배경 이미지는 <img>와 달리 완료 여부를 DOM이 알려 주지 않아 리소스 타이밍으로 본다
        page.waitForFunction("() => performance.getEntriesByType('resource')"
                + ".filter(r => r.name.includes('/content-images/') && r.responseEnd > 0).length === 2");
        page.evaluate("() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))");

        byte[] failedShot = thumbShot(page, "HTB-실패", false);
        byte[] placeholderShot = thumbShot(page, "HTB-실패", true);
        assertThat(Arrays.equals(failedShot, placeholderShot))
                .as("깨진 이미지 아이콘이 그려지지 않고 썸네일 span을 뺀 자리표시 칸과 화면이 같다").isTrue();
        assertThat(Arrays.equals(thumbShot(page, "HTB-정상", false), placeholderShot))
                .as("정상 썸네일은 자리표시와 다르게 그려진다(위 비교가 무의미하게 통과하지 않는다는 대조)").isFalse();
    }

    @Test
    @DisplayName("좁은 화면(320px)에서도 썸네일 행의 제목이 최소 폭을 확보하고 날짜가 행 밖으로 넘치지 않는다 — 긴 게시판명 포함(코드 리뷰 R1)")
    void narrowViewportKeepsTitleAndDateInsideRow() throws Exception {
        long board = board();
        jdbc.update("UPDATE board SET name = ? WHERE id = ?", "아주아주긴게시판이름입니다열글자넘김", board); // 게시판명 상한(10rem)까지 차지하는 경우
        post(board, "HTB-좁은화면 제목", LocalDateTime.now(), imageWithFile(board));

        context = newContext(new com.microsoft.playwright.Browser.NewContextOptions().setViewportSize(320, 700));
        Page page = context.newPage();
        page.navigate("http://localhost:" + port + "/main");
        page.waitForSelector(".home-post-item .home-post-thumb");

        @SuppressWarnings("unchecked")
        java.util.Map<String, Number> m = (java.util.Map<String, Number>) page.evaluate("() => { const li = [...document.querySelectorAll('.home-post-item')]"
                + ".find(x => x.textContent.includes('HTB-좁은화면')); const row = li.querySelector('a').getBoundingClientRect();"
                + " const t = li.querySelector('.home-post-title').getBoundingClientRect(); const d = li.querySelector('.home-post-date').getBoundingClientRect();"
                + " return { titleW: t.width, dateOver: d.right - row.right }; }");
        assertThat(m.get("titleW").doubleValue()).as("제목 폭(px) — 줄바꿈 없이는 0까지 줄어든다").isGreaterThanOrEqualTo(120);
        assertThat(m.get("dateOver").doubleValue()).as("날짜가 행 오른쪽 밖으로 넘치지 않는다").isLessThanOrEqualTo(0);
    }

    /** 썸네일 칸 스크린샷. withoutImg가 true면 칸 안쪽 썸네일 span을 지운 뒤 찍는다(자리표시 배경만 남은 기준 모양). */
    private byte[] thumbShot(Page page, String title, boolean withoutImg) {
        String selector = ".home-post-item:has-text('" + title + "') .home-post-thumb";
        if (withoutImg) {
            page.locator(selector).locator(".home-post-thumb-img").evaluate("e => e.remove()");
        }
        return page.locator(selector).screenshot();
    }
}
