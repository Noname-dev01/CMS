package com.cms.publicweb.contentimage;

import com.cms.common.image.ImageThumbnailer;
import com.cms.common.storage.FileStorage;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 {@code GET/HEAD /content-images/{id}?size=thumb}(PLAN-board-definition.md PR 5 D5). 썸네일은 원본과 같은 공개 판정을 거치고, 썸네일 행이 없으면
 * 원본을 주며, 알 수 없는 {@code size}는 원본을 주지 않고 404다. 픽스처는 JDBC·{@link FileStorage}로 직접 만든다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PublicContentImageThumbIntegrationTest extends MariaDbContainerSupport {

    private static final String UPLOADER = "thumb-pub-test";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileStorage fileStorage;

    private final List<Long> boardIds = new ArrayList<>();
    private final List<String> rootKeys = new ArrayList<>();
    private final List<String> thumbKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long boardId : boardIds) {
            jdbc.update("DELETE FROM content_image_ref WHERE owner_type = 'POST' AND owner_id IN (SELECT id FROM post WHERE board_id = ?)", boardId);
            jdbc.update("DELETE FROM post WHERE board_id = ?", boardId);
        }
        jdbc.update("DELETE r FROM content_image_ref r JOIN content_image i ON i.id = r.image_id WHERE i.uploader_id = ?", UPLOADER);
        jdbc.update("DELETE FROM content_image WHERE uploader_id = ?", UPLOADER); // 썸네일 행은 CASCADE
        jdbc.update("UPDATE content_image_usage SET total_bytes = (SELECT COALESCE(SUM(file_size), 0) FROM content_image),"
                + " total_count = (SELECT COUNT(*) FROM content_image) WHERE id = 1");
        for (Long boardId : boardIds) {
            jdbc.update("DELETE FROM board WHERE id = ?", boardId);
        }
        rootKeys.forEach(key -> safeDelete(() -> fileStorage.delete(key)));
        thumbKeys.forEach(key -> safeDelete(() -> fileStorage.delete(key, "thumb")));
    }

    private static void safeDelete(Runnable delete) {
        try {
            delete.run();
        } catch (RuntimeException ignored) {
        }
    }

    private static byte[] png(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private long board() {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO board (name, public_yn, attachment_yn, deleted, create_date, update_date) VALUES ('thumb-pub', 1, 1, 0, NOW(6), NOW(6))",
                    Statement.RETURN_GENERATED_KEYS);
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        boardIds.add(id);
        return id;
    }

    /** BOARD 출처 이미지 + 그 이미지를 본문이 참조하는 글. {@code useYn=false}면 공개되지 않는 글이다. */
    private long imagePublishedBy(long boardId, byte[] original, boolean useYn) {
        String key = fileStorage.store(original, "image.png");
        rootKeys.add(key);
        jdbc.update("INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, scope_type, scope_id, namespace, create_date)"
                + " VALUES (?, 'image/png', ?, ?, 'BOARD', ?, '', NOW(6))", key, original.length, UPLOADER, boardId);
        long imageId = jdbc.queryForObject("SELECT id FROM content_image WHERE storage_key = ?", Long.class, key);
        jdbc.update("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, create_date, update_date) VALUES (?, 't', '<p>x</p>', ?, 0, 'tester', NOW(6), NOW(6))",
                boardId, useYn);
        long postId = jdbc.queryForObject("SELECT MAX(id) FROM post WHERE board_id = ?", Long.class, boardId);
        jdbc.update("INSERT INTO content_image_ref (owner_type, owner_id, image_id) VALUES ('POST', ?, ?)", postId, imageId);
        return imageId;
    }

    /** 실제 생성기로 만든 썸네일을 {@code thumb} 네임스페이스에 저장하고 행을 넣는다. */
    private byte[] attachThumbnail(long imageId, byte[] original) {
        ImageThumbnailer.Thumbnail thumb = ImageThumbnailer.create(original, "image/png").orElseThrow();
        String key = fileStorage.store(thumb.content(), "thumb.png", "thumb");
        thumbKeys.add(key);
        jdbc.update("INSERT INTO content_image_thumb (image_id, storage_key, content_type, file_size, create_date) VALUES (?, ?, ?, ?, NOW(6))",
                imageId, key, thumb.contentType(), thumb.content().length);
        return thumb.content();
    }

    @Test
    @DisplayName("공개 글이 참조하는 이미지는 ?size=thumb에 썸네일을 준다(GET·HEAD, 원본은 그대로)")
    void servesThumbnail() throws Exception {
        long boardId = board();
        byte[] original = png(1000, 600);
        long imageId = imagePublishedBy(boardId, original, true);
        byte[] thumbnail = attachThumbnail(imageId, original);

        MvcResult thumbGet = mockMvc.perform(get("/content-images/{id}", imageId).param("size", "thumb")).andExpect(status().isOk()).andReturn();
        assertThat(thumbGet.getResponse().getContentAsByteArray()).isEqualTo(thumbnail);
        assertThat(thumbGet.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(thumbGet.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(thumbGet.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(thumbnail.length).isLessThan(original.length);

        MvcResult thumbHead = mockMvc.perform(head("/content-images/{id}", imageId).param("size", "thumb")).andExpect(status().isOk()).andReturn();
        assertThat(thumbHead.getResponse().getContentLengthLong()).isEqualTo(thumbnail.length);
        assertThat(thumbHead.getResponse().getContentAsByteArray()).isEmpty();

        assertThat(mockMvc.perform(get("/content-images/{id}", imageId)).andReturn().getResponse().getContentAsByteArray()).isEqualTo(original);
        // size 외의 다른 쿼리 매개변수는 무시된다
        assertThat(mockMvc.perform(get("/content-images/{id}", imageId).param("foo", "1")).andReturn().getResponse().getContentAsByteArray()).isEqualTo(original);
    }

    @Test
    @DisplayName("썸네일 행이 없는 이미지(작은 원본·대표가 아닌 이미지)는 ?size=thumb에 원본을 준다")
    void fallsBackToOriginal() throws Exception {
        long boardId = board();
        byte[] original = png(100, 60);
        long imageId = imagePublishedBy(boardId, original, true);

        MvcResult result = mockMvc.perform(get("/content-images/{id}", imageId).param("size", "thumb")).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(original);
    }

    @Test
    @DisplayName("공개되지 않은 이미지·없는 이미지는 ?size=thumb도 원본과 같은 404다")
    void sameNotFoundAsOriginal() throws Exception {
        long boardId = board();
        byte[] original = png(1000, 600);
        long hidden = imagePublishedBy(boardId, original, false);
        attachThumbnail(hidden, original);

        for (String id : new String[]{String.valueOf(hidden), "999999999", "abc"}) {
            mockMvc.perform(get("/content-images/{id}", id)).andExpect(status().isNotFound());
            mockMvc.perform(get("/content-images/{id}", id).param("size", "thumb")).andExpect(status().isNotFound());
            mockMvc.perform(head("/content-images/{id}", id).param("size", "thumb")).andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("알 수 없는 size 값(대소문자·빈 값·다른 값·중복)은 원본을 주지 않고 404다")
    void unknownSizeIs404() throws Exception {
        long boardId = board();
        byte[] original = png(1000, 600);
        long imageId = imagePublishedBy(boardId, original, true);
        attachThumbnail(imageId, original);

        for (String bad : new String[]{"THUMB", "Thumb", "", "large", "original", "thumb ", "0"}) {
            mockMvc.perform(get("/content-images/{id}", imageId).param("size", bad)).andExpect(status().isNotFound());
            mockMvc.perform(head("/content-images/{id}", imageId).param("size", bad)).andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/content-images/{id}", imageId).param("size", "thumb", "thumb")).andExpect(status().isNotFound());
        mockMvc.perform(get("/content-images/{id}", imageId).param("size", "thumb", "large")).andExpect(status().isNotFound());
        // 정상 요청은 여전히 200
        mockMvc.perform(get("/content-images/{id}", imageId).param("size", "thumb")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("썸네일 파일이 디스크에 없으면 404다")
    void missingThumbnailFile() throws Exception {
        long boardId = board();
        byte[] original = png(1000, 600);
        long imageId = imagePublishedBy(boardId, original, true);
        attachThumbnail(imageId, original);
        String key = jdbc.queryForObject("SELECT storage_key FROM content_image_thumb WHERE image_id = ?", String.class, imageId);
        fileStorage.delete(key, "thumb");

        mockMvc.perform(get("/content-images/{id}", imageId).param("size", "thumb")).andExpect(status().isNotFound());
        // 원본은 영향이 없다
        mockMvc.perform(get("/content-images/{id}", imageId)).andExpect(status().isOk());
    }
}
