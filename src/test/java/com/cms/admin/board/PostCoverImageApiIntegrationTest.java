package com.cms.admin.board;

import com.cms.admin.contentimage.config.ContentImageProperties;
import com.cms.admin.contentimage.service.ContentImageThumbnailService;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionCache;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StorageFileNotFoundException;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 게시글 대표 이미지(PLAN-board-definition.md PR 5)의 관리 API 실제 스택 시험. MockMvc 호출이 실제로 커밋하므로 {@link #cleanUp()}에서 행과 파일
 * (썸네일 포함)을 지운다. 입력 계약(엄격 파싱·중복 키)은 DTO를 직접 만들지 않고 원문 JSON으로 보낸다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PostCoverImageApiIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired PermissionCache cache;
    @Autowired FileStorage fileStorage;
    @Autowired ObjectMapper objectMapper;
    @Autowired ContentImageProperties imageProperties;
    @Autowired ContentImageThumbnailService thumbnailService;

    private Member admin;
    private final List<Long> boardIds = new ArrayList<>();
    private long auditBaseline;
    private long originalMaxBytes;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "cv-admin", Role.ROLE_ADMIN);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
        originalMaxBytes = imageProperties.getMaxTotalBytes();
    }

    @AfterEach
    void cleanUp() {
        imageProperties.setMaxTotalBytes(originalMaxBytes);
        for (Long boardId : boardIds) {
            jdbc.update("UPDATE post SET cover_image_id = NULL WHERE board_id = ?", boardId);
            jdbc.update("DELETE FROM content_image_ref WHERE owner_type = 'POST' AND owner_id IN (SELECT id FROM post WHERE board_id = ?)", boardId);
            jdbc.update("DELETE FROM post WHERE board_id = ?", boardId);
            jdbc.update("DELETE FROM member_board_permission WHERE board_id = ?", boardId);
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT t.storage_key FROM content_image_thumb t JOIN content_image i ON i.id = t.image_id WHERE i.uploader_id = ?",
                admin.getUserId())) {
            fileStorage.delete((String) row.get("storage_key"), "thumb");
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT id, storage_key FROM content_image WHERE uploader_id = ?", admin.getUserId())) {
            jdbc.update("DELETE FROM content_image_ref WHERE image_id = ?", row.get("id"));
            jdbc.update("DELETE FROM content_image WHERE id = ?", row.get("id")); // 썸네일 행은 FK CASCADE
            fileStorage.delete((String) row.get("storage_key"));
        }
        jdbc.update("UPDATE content_image_usage SET total_bytes = (SELECT COALESCE(SUM(file_size), 0) FROM content_image),"
                + " total_count = (SELECT COUNT(*) FROM content_image) WHERE id = 1");
        for (Long boardId : boardIds) {
            jdbc.update("DELETE FROM board WHERE id = ?", boardId);
        }
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE id > ?", auditBaseline);
        cache.invalidate();
    }

    // ------------------------------------------------------------ 도우미

    private RequestPostProcessor asAdmin() {
        return TestMembers.asMember(admin);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static byte[] png(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private long createBoard(String extraMembers) throws Exception {
        String json = "{\"name\":\"cv-board-" + System.nanoTime() + "\",\"publicYn\":true,\"attachmentYn\":true"
                + (extraMembers == null ? "" : "," + extraMembers) + "}";
        MvcResult result = mockMvc.perform(post("/admin/api/boards").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        long id = body(result).get("id").asLong();
        boardIds.add(id);
        return id;
    }

    private long uploadImage(long boardId, byte[] bytes) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/admin/api/boards/{b}/content-images", boardId)
                .file(new MockMultipartFile("file", "a.png", "image/png", bytes)).with(asAdmin()).with(csrf())).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asLong();
    }

    private static String img(long imageId) {
        return "<img src=\"/content-images/" + imageId + "\">";
    }

    private MvcResult createPostRaw(long boardId, String contentHtml, String extraMembers) throws Exception {
        String json = "{\"title\":\"cv-post-" + System.nanoTime() + "\",\"content\":" + objectMapper.writeValueAsString(contentHtml)
                + ",\"contentFormat\":\"HTML\",\"useYn\":true" + (extraMembers == null ? "" : "," + extraMembers) + "}";
        return mockMvc.perform(post("/admin/api/boards/{b}/posts", boardId).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private long createPostOk(long boardId, String contentHtml, String extraMembers) throws Exception {
        MvcResult result = createPostRaw(boardId, contentHtml, extraMembers);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asLong();
    }

    private MvcResult patchPost(long boardId, long postId, String json) throws Exception {
        return mockMvc.perform(patch("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private Long coverOf(long postId) {
        return jdbc.queryForObject("SELECT cover_image_id FROM post WHERE id = ?", Long.class, postId);
    }

    private int thumbRows(long imageId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM content_image_thumb WHERE image_id = ?", Integer.class, imageId);
    }

    private long counterBytes() {
        return jdbc.queryForObject("SELECT total_bytes FROM content_image_usage WHERE id = 1", Long.class);
    }

    private long counterCount() {
        return jdbc.queryForObject("SELECT total_count FROM content_image_usage WHERE id = 1", Long.class);
    }

    private long thumbSize(long imageId) {
        return jdbc.queryForObject("SELECT file_size FROM content_image_thumb WHERE image_id = ?", Long.class, imageId);
    }

    private int postCount(long boardId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM post WHERE board_id = ?", Integer.class, boardId);
    }

    // ------------------------------------------------------------ 지정·썸네일 생성

    @Test
    @DisplayName("큰 이미지를 대표로 지정하면 썸네일 행·파일이 생기고 카운터는 바이트만 늘어난다")
    void createWithCoverGeneratesThumbnail() throws Exception {
        long boardId = createBoard(null);
        long imageId = uploadImage(boardId, png(1200, 800));
        long bytesBefore = counterBytes();
        long countBefore = counterCount();

        MvcResult result = createPostRaw(boardId, "<p>x</p>" + img(imageId), "\"coverImageId\":" + imageId);

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(result).get("coverImageId").asLong()).isEqualTo(imageId);
        long postId = body(result).get("id").asLong();
        assertThat(coverOf(postId)).isEqualTo(imageId);
        assertThat(thumbRows(imageId)).isEqualTo(1);
        String key = jdbc.queryForObject("SELECT storage_key FROM content_image_thumb WHERE image_id = ?", String.class, imageId);
        assertThat(fileStorage.load(key, "thumb")).hasSize((int) thumbSize(imageId));
        assertThat(counterBytes()).isEqualTo(bytesBefore + thumbSize(imageId));
        assertThat(counterCount()).isEqualTo(countBefore);

        JsonNode detail = body(mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asAdmin())).andReturn());
        assertThat(detail.get("coverImageId").asLong()).isEqualTo(imageId);
    }

    @Test
    @DisplayName("작은 이미지는 대표로 지정되지만 썸네일 행·파일이 없다")
    void smallImageNeedsNoThumbnail() throws Exception {
        long boardId = createBoard(null);
        long imageId = uploadImage(boardId, png(32, 16));
        long bytesBefore = counterBytes();

        long postId = createPostOk(boardId, img(imageId), "\"coverImageId\":" + imageId);

        assertThat(coverOf(postId)).isEqualTo(imageId);
        assertThat(thumbRows(imageId)).isZero();
        assertThat(counterBytes()).isEqualTo(bytesBefore);
    }

    @Test
    @DisplayName("같은 이미지를 두 글이 대표로 쓰면 썸네일은 한 번만 만들고 카운터도 한 번만 늘어난다")
    void sharedImageThumbnailIsCreatedOnce() throws Exception {
        long boardId = createBoard(null);
        long imageId = uploadImage(boardId, png(1000, 700));
        createPostOk(boardId, img(imageId), "\"coverImageId\":" + imageId);
        long afterFirst = counterBytes();
        String key = jdbc.queryForObject("SELECT storage_key FROM content_image_thumb WHERE image_id = ?", String.class, imageId);

        createPostOk(boardId, img(imageId), "\"coverImageId\":" + imageId);

        assertThat(thumbRows(imageId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT storage_key FROM content_image_thumb WHERE image_id = ?", String.class, imageId)).isEqualTo(key);
        assertThat(counterBytes()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("같은 이미지를 두 글이 동시에 대표로 지정해도 둘 다 성공하고 썸네일은 하나다")
    void concurrentCoverOfSameImage() throws Exception {
        long boardId = createBoard(null);
        long imageId = uploadImage(boardId, png(1000, 700));
        long bytesBefore = counterBytes();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Callable<Integer> task = () -> {
                    start.await();
                    return createPostRaw(boardId, img(imageId), "\"coverImageId\":" + imageId).getResponse().getStatus();
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(thumbRows(imageId)).isEqualTo(1);
        assertThat(counterBytes()).isEqualTo(bytesBefore + thumbSize(imageId));
        String key = jdbc.queryForObject("SELECT storage_key FROM content_image_thumb WHERE image_id = ?", String.class, imageId);
        assertThat(fileStorage.load(key, "thumb")).isNotEmpty();
    }

    // ------------------------------------------------------------ 검증·롤백

    @Test
    @DisplayName("본문에 없는 이미지·존재하지 않는 이미지는 400이고 글이 만들어지지 않는다")
    void coverMustBeInBody() throws Exception {
        long boardId = createBoard(null);
        long inBody = uploadImage(boardId, png(32, 16));
        long notInBody = uploadImage(boardId, png(32, 16));

        MvcResult notReferenced = createPostRaw(boardId, img(inBody), "\"coverImageId\":" + notInBody);
        MvcResult missing = createPostRaw(boardId, img(inBody), "\"coverImageId\":999999999");

        assertThat(notReferenced.getResponse().getStatus()).isEqualTo(400);
        assertThat(notReferenced.getResponse().getContentAsString()).contains("본문에 들어 있는 이미지");
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(postCount(boardId)).isZero();
    }

    @Test
    @DisplayName("입력 계약: 정수·null 외의 값과 중복 키는 400이고 기존 대표는 그대로다")
    void strictParsing() throws Exception {
        long boardId = createBoard(null);
        long imageId = uploadImage(boardId, png(32, 16));
        long postId = createPostOk(boardId, img(imageId), "\"coverImageId\":" + imageId);

        for (String bad : new String[]{"\"\"", "\"" + imageId + "\"", "1.5", "true", "[]", "{}", "0", "-1", "99999999999999999999"}) {
            MvcResult result = patchPost(boardId, postId, "{\"coverImageId\":" + bad + "}");
            assertThat(result.getResponse().getStatus()).as("값 " + bad).isEqualTo(400);
            assertThat(coverOf(postId)).as("값 " + bad).isEqualTo(imageId);
        }
        MvcResult duplicate = patchPost(boardId, postId, "{\"coverImageId\":" + imageId + ",\"coverImageId\":null}");
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(400);
        assertThat(coverOf(postId)).isEqualTo(imageId);

        MvcResult createDuplicate = createPostRaw(boardId, img(imageId), "\"coverImageId\":" + imageId + ",\"coverImageId\":" + imageId);
        assertThat(createDuplicate.getResponse().getStatus()).isEqualTo(400);
        assertThat(createPostRaw(boardId, img(imageId), "\"coverImageId\":\"\"").getResponse().getStatus()).isEqualTo(400);
        assertThat(postCount(boardId)).isEqualTo(1);
    }

    @Test
    @DisplayName("손상되어 썸네일을 만들 수 없는 이미지는 400이고 본문 저장·썸네일 행·카운터가 모두 그대로다")
    void undecodableImageRollsBackEverything() throws Exception {
        long boardId = createBoard(null);
        long goodImage = uploadImage(boardId, png(32, 16));
        long badImage = uploadImage(boardId, png(900, 900));
        byte[] original = png(900, 900);
        String brokenKey = fileStorage.store(Arrays.copyOf(original, original.length / 2), "image.png");
        jdbc.update("UPDATE content_image SET storage_key = ? WHERE id = ?", brokenKey, badImage);
        long postId = createPostOk(boardId, img(goodImage) + img(badImage), null);
        long bytesBefore = counterBytes();
        String contentBefore = jdbc.queryForObject("SELECT content FROM post WHERE id = ?", String.class, postId);

        MvcResult result = patchPost(boardId, postId, "{\"content\":\"<p>changed</p>" + img(badImage).replace("\"", "\\\"")
                + "\",\"contentFormat\":\"HTML\",\"coverImageId\":" + badImage + "}");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("대표 이미지를 만들 수 없습니다");
        assertThat(coverOf(postId)).isNull();
        assertThat(thumbRows(badImage)).isZero();
        assertThat(counterBytes()).isEqualTo(bytesBefore);
        assertThat(jdbc.queryForObject("SELECT content FROM post WHERE id = ?", String.class, postId)).isEqualTo(contentBefore);
        fileStorage.delete(brokenKey);
    }

    @Test
    @DisplayName("원본 파일이 디스크에 없는 이미지는 대표로 지정할 수 없다(400)")
    void missingOriginalFile() throws Exception {
        long boardId = createBoard(null);
        long imageId = uploadImage(boardId, png(900, 900));
        String key = jdbc.queryForObject("SELECT storage_key FROM content_image WHERE id = ?", String.class, imageId);
        fileStorage.delete(key);

        MvcResult result = createPostRaw(boardId, img(imageId), "\"coverImageId\":" + imageId);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(postCount(boardId)).isZero();
        assertThat(thumbRows(imageId)).isZero();
    }

    @Test
    @DisplayName("썸네일 용량이 카운터 상한을 넘으면 409이고 전부 롤백된다")
    void counterLimitRollsBack() throws Exception {
        long boardId = createBoard(null);
        long imageId = uploadImage(boardId, png(1000, 700));
        imageProperties.setMaxTotalBytes(counterBytes());
        long bytesBefore = counterBytes();

        MvcResult result = createPostRaw(boardId, img(imageId), "\"coverImageId\":" + imageId);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(postCount(boardId)).isZero();
        assertThat(thumbRows(imageId)).isZero();
        assertThat(counterBytes()).isEqualTo(bytesBefore);
    }

    @Test
    @DisplayName("생성 자리가 모두 차 있으면 503 + Retry-After이고 본문도 저장되지 않는다")
    void noSlotReturns503() throws Exception {
        long boardId = createBoard(null);
        long imageId = uploadImage(boardId, png(1000, 700));
        var slots = thumbnailService.generationSlots();
        int held = slots.availablePermits();
        slots.acquire(held);
        try {
            MvcResult result = createPostRaw(boardId, img(imageId), "\"coverImageId\":" + imageId);

            assertThat(result.getResponse().getStatus()).isEqualTo(503);
            assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("2");
            assertThat(body(result).get("code").asText()).isEqualTo("SERVICE_BUSY");
        } finally {
            slots.release(held);
        }
        assertThat(postCount(boardId)).isZero();
        assertThat(thumbRows(imageId)).isZero();
    }

    // ------------------------------------------------------------ PATCH 3상태·자동 해제

    @Test
    @DisplayName("PATCH: 키 없음은 유지, null은 해제, 정수는 지정, 대표만 바꾸는 요청도 유효하고 빈 요청은 여전히 400")
    void patchThreeStates() throws Exception {
        long boardId = createBoard(null);
        long first = uploadImage(boardId, png(32, 16));
        long second = uploadImage(boardId, png(40, 20));
        long postId = createPostOk(boardId, img(first) + img(second), "\"coverImageId\":" + first);

        assertThat(patchPost(boardId, postId, "{\"title\":\"새 제목\"}").getResponse().getStatus()).isEqualTo(200);
        assertThat(coverOf(postId)).isEqualTo(first);

        assertThat(patchPost(boardId, postId, "{\"coverImageId\":" + second + "}").getResponse().getStatus()).isEqualTo(200);
        assertThat(coverOf(postId)).isEqualTo(second);

        MvcResult cleared = patchPost(boardId, postId, "{\"coverImageId\":null}");
        assertThat(cleared.getResponse().getStatus()).isEqualTo(200);
        assertThat(coverOf(postId)).isNull();
        assertThat(body(cleared).has("coverImageId") && !body(cleared).get("coverImageId").isNull()).isFalse();

        assertThat(patchPost(boardId, postId, "{}").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("본문에서 대표 이미지를 지우면 키가 없을 때 서버가 해제하고, 키가 있으면 그 값이 우선한다")
    void autoClearWhenImageLeavesBody() throws Exception {
        long boardId = createBoard(null);
        long first = uploadImage(boardId, png(32, 16));
        long second = uploadImage(boardId, png(40, 20));
        long postId = createPostOk(boardId, img(first) + img(second), "\"coverImageId\":" + first);

        patchPost(boardId, postId, "{\"content\":" + objectMapper.writeValueAsString(img(second)) + ",\"contentFormat\":\"HTML\"}");
        assertThat(coverOf(postId)).isNull();

        long postId2 = createPostOk(boardId, img(first) + img(second), "\"coverImageId\":" + first);
        patchPost(boardId, postId2, "{\"content\":" + objectMapper.writeValueAsString(img(second)) + ",\"contentFormat\":\"HTML\",\"coverImageId\":" + second + "}");
        assertThat(coverOf(postId2)).isEqualTo(second);
    }

    @Test
    @DisplayName("다른 관리자가 그 사이 바꾼 대표가 새 본문에 남아 있으면 키 없는 저장이 그 값을 유지한다(낡은 화면)")
    void staleScreenDoesNotOverwriteOtherAdminsCover() throws Exception {
        long boardId = createBoard(null);
        long x = uploadImage(boardId, png(32, 16));
        long y = uploadImage(boardId, png(40, 20));
        long postId = createPostOk(boardId, img(x) + img(y), "\"coverImageId\":" + x);
        // B가 대표만 Y로 바꾼다
        assertThat(patchPost(boardId, postId, "{\"coverImageId\":" + y + "}").getResponse().getStatus()).isEqualTo(200);
        // A의 낡은 화면: 본문에서 X를 지우고 대표 키는 보내지 않는다
        patchPost(boardId, postId, "{\"content\":" + objectMapper.writeValueAsString(img(y)) + ",\"contentFormat\":\"HTML\"}");

        assertThat(coverOf(postId)).isEqualTo(y);
    }

    @Test
    @DisplayName("쓰기가 중지된 게시판에서는 대표 이미지 변경도 400이다")
    void writeStoppedBlocksCoverChange() throws Exception {
        long boardId = createBoard(null);
        long imageId = uploadImage(boardId, png(32, 16));
        long postId = createPostOk(boardId, img(imageId), null);
        int version = jdbc.queryForObject("SELECT version FROM board WHERE id = ?", Integer.class, boardId);
        mockMvc.perform(patch("/admin/api/boards/{id}", boardId).with(asAdmin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"writableYn\":false,\"version\":" + version + "}")).andReturn();

        MvcResult result = patchPost(boardId, postId, "{\"coverImageId\":" + imageId + "}");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(coverOf(postId)).isNull();
    }

    // ------------------------------------------------------------ 삭제

    @Test
    @DisplayName("이미지를 지우면 썸네일 행(CASCADE)·두 파일·카운터 바이트가 함께 정리된다")
    void deletingImageRemovesThumbnail() throws Exception {
        long boardId = createBoard(null);
        long imageId = uploadImage(boardId, png(1000, 700));
        long baselineBytes = counterBytes() - 0;
        long postId = createPostOk(boardId, img(imageId), "\"coverImageId\":" + imageId);
        String thumbKey = jdbc.queryForObject("SELECT storage_key FROM content_image_thumb WHERE image_id = ?", String.class, imageId);
        String originalKey = jdbc.queryForObject("SELECT storage_key FROM content_image WHERE id = ?", String.class, imageId);
        long imageSize = jdbc.queryForObject("SELECT file_size FROM content_image WHERE id = ?", Long.class, imageId);
        long withThumb = counterBytes();
        assertThat(withThumb).isEqualTo(baselineBytes + thumbSize(imageId));

        // 대표가 쓰는 이미지는 지울 수 없다(참조 409)
        assertThat(mockMvc.perform(delete("/admin/api/media/{id}", imageId).with(asAdmin()).with(csrf())).andReturn().getResponse().getStatus()).isEqualTo(409);
        // 본문에서 빼면 참조·대표가 사라지고 지울 수 있다
        patchPost(boardId, postId, "{\"content\":\"<p>none</p>\",\"contentFormat\":\"HTML\"}");
        assertThat(coverOf(postId)).isNull();
        assertThat(mockMvc.perform(delete("/admin/api/media/{id}", imageId).with(asAdmin()).with(csrf())).andReturn().getResponse().getStatus()).isEqualTo(204);

        assertThat(thumbRows(imageId)).isZero();
        assertThat(counterBytes()).isEqualTo(withThumb - imageSize - (withThumb - baselineBytes));
        assertThatThrownBy(() -> fileStorage.load(thumbKey, "thumb")).isInstanceOf(StorageFileNotFoundException.class);
        assertThatThrownBy(() -> fileStorage.load(originalKey)).isInstanceOf(StorageFileNotFoundException.class);
    }
}
