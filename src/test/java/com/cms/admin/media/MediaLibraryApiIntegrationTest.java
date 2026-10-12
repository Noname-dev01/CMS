package com.cms.admin.media;

import com.cms.admin.contentimage.service.ContentImageService;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionCache;
import com.cms.common.image.ImageFileValidatorTest;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StorageFileNotFoundException;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.MediaTestSupport;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 미디어 라이브러리 통합 시험(PLAN-media-library.md) — 실제 SecurityConfig·판정기·감사·MariaDB·로컬 스토리지.
 * 업로드·목록·상세·삭제 왕복, 배너·팝업·게시글 본문에서의 사용(사용 중 삭제 409 + 사용처), 소유자 삭제 규칙, 출처별 가시성, 위임 권한 조합을 고정한다.
 * MockMvc 호출이 실제로 커밋하므로 {@link #cleanUp()}이 만든 행·파일·카운터를 되돌린다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class MediaLibraryApiIntegrationTest extends MariaDbContainerSupport {

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired PermissionCache cache;
    @Autowired FileStorage fileStorage;
    @Autowired ObjectMapper objectMapper;

    private Member admin;
    private Member manager;
    private long noticeBoardId;
    private long auditBaseline;
    private String ip;
    private final List<Long> postIds = new ArrayList<>();
    private final List<Long> boardImageIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        int n = IP_SEQUENCE.getAndIncrement();
        ip = "10.91." + (n / 250) + "." + (n % 250 + 1);
        admin = TestMembers.save(memberRepository, "md-admin", Role.ROLE_ADMIN);
        manager = TestMembers.save(memberRepository, "md-manager", Role.ROLE_MANAGER);
        noticeBoardId = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
        cleanOwners();
    }

    @AfterEach
    void cleanUp() {
        for (Long id : postIds) {
            jdbc.update("DELETE FROM content_image_ref WHERE owner_type = 'POST' AND owner_id = ?", id);
            jdbc.update("DELETE FROM post WHERE id = ?", id);
        }
        for (Long id : boardImageIds) {
            String key = jdbc.queryForObject("SELECT storage_key FROM content_image WHERE id = ?", String.class, id);
            jdbc.update("DELETE FROM content_image_ref WHERE image_id = ?", id);
            jdbc.update("DELETE FROM content_image WHERE id = ?", id);
            fileStorage.delete(key);
        }
        cleanOwners();
        MediaTestSupport.cleanLibrary(jdbc, fileStorage);
        TestMembers.delete(jdbc, List.of(admin.getId(), manager.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE id > ?", auditBaseline);
        cache.invalidate();
    }

    private void cleanOwners() {
        jdbc.update("DELETE FROM banner");
        jdbc.update("DELETE FROM popup");
    }

    private RequestPostProcessor asAdmin() {
        return TestMembers.asMember(admin);
    }

    private RequestPostProcessor asManager() {
        return TestMembers.asMember(manager);
    }

    private void grant(String feature, String... actions) {
        for (String action : actions) {
            jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, ?, ?)", manager.getId(), feature, action);
        }
        cache.invalidate();
    }

    private void grantNoticeBoard(String... actions) {
        for (String action : actions) {
            jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, ?)", manager.getId(), noticeBoardId, action);
        }
        cache.invalidate();
    }

    private static byte[] png() throws Exception {
        return ImageFileValidatorTest.png(30, 12);
    }

    private MvcResult upload(String filename, String contentType, byte[] bytes, RequestPostProcessor who) throws Exception {
        return mockMvc.perform(multipart("/admin/api/media")
                .file(new MockMultipartFile("file", filename, contentType, bytes)).with(who).with(csrf())).andReturn();
    }

    private long uploadOk(RequestPostProcessor who) throws Exception {
        MvcResult result = upload("로고/..\\원본 파일.png", "image/png", png(), who);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return json(result).get("id").asLong();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long createBannerWithImageId(long imageId, RequestPostProcessor who, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/admin/api/banners").param("title", "라이브러리 배너").param("imageId", String.valueOf(imageId))
                .with(who).with(csrf())).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(expectedStatus);
        return expectedStatus == 201 ? json(result).get("id").asLong() : -1;
    }

    private long createBannerWithUpload(RequestPostProcessor who) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/admin/api/banners").file(new MockMultipartFile("image", "banner.png", "image/png", png()))
                .param("title", "직접 올린 배너").with(who).with(csrf())).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return json(result).get("id").asLong();
    }

    private long createImagePopup(Long imageId, boolean upload, RequestPostProcessor who, int expectedStatus) throws Exception {
        var builder = multipart("/admin/api/popups").param("title", "라이브러리 팝업").param("popupType", "IMAGE");
        if (upload) {
            builder.file(new MockMultipartFile("image", "popup.png", "image/png", png()));
        } else {
            builder.param("imageId", String.valueOf(imageId));
        }
        MvcResult result = mockMvc.perform(builder.with(who).with(csrf())).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(expectedStatus);
        return expectedStatus == 201 ? json(result).get("id").asLong() : -1;
    }

    private long createNotice(String contentHtml, RequestPostProcessor who, int expectedStatus) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "title", "md-notice-" + System.nanoTime(), "content", contentHtml, "contentFormat", "HTML", "useYn", true));
        MvcResult result = mockMvc.perform(post("/admin/api/boards/{boardId}/posts", noticeBoardId).with(who).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(expectedStatus);
        if (expectedStatus != 201) {
            return -1;
        }
        long id = json(result).get("id").asLong();
        postIds.add(id);
        return id;
    }

    private long[] counter() {
        return jdbc.queryForObject("SELECT total_bytes, total_count FROM content_image_usage WHERE id = 1",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)});
    }

    private long insertBoardImage() throws Exception {
        String key = fileStorage.store(png(), "image.png");
        jdbc.update("INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, scope_type, scope_id, namespace)"
                + " VALUES (?, 'image/png', ?, ?, 'BOARD', ?, '')", key, (long) png().length, admin.getUserId(), noticeBoardId);
        long id = jdbc.queryForObject("SELECT id FROM content_image WHERE storage_key = ? AND namespace = ''", Long.class, key);
        boardImageIds.add(id);
        MediaTestSupport.recomputeCounter(jdbc);
        return id;
    }

    // ===== 생명주기 =====

    @Test
    @DisplayName("ADMIN 업로드→목록→상세→파일 미리보기→삭제 왕복: 행(LIBRARY·루트·정리된 파일명)·파일·카운터가 함께 움직이고 감사 로그가 남는다")
    void lifecycle() throws Exception {
        long[] before = counter();
        byte[] original = png();

        MvcResult created = upload("로고/..\\원본 파일.png", "image/png", original, asAdmin());
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(created);
        long id = body.get("id").asLong();
        assertThat(body.get("origin").asText()).isEqualTo("LIBRARY");
        assertThat(body.get("selectable").asBoolean()).isTrue();
        assertThat(body.get("originalName").asText()).as("경로 요소를 떼고 마지막 세그먼트만").isEqualTo("원본 파일.png");
        assertThat(body.has("storageKey")).as("저장 키는 내보내지 않는다").isFalse();
        assertThat(counter()).containsExactly(before[0] + original.length, before[1] + 1);
        assertThat(jdbc.queryForObject("SELECT namespace FROM content_image WHERE id = ?", String.class, id)).isEmpty();
        String key = jdbc.queryForObject("SELECT storage_key FROM content_image WHERE id = ?", String.class, id);
        assertThat(fileStorage.load(key)).isEqualTo(original);

        MvcResult list = mockMvc.perform(get("/admin/api/media").param("keyword", "원본").with(asAdmin())).andExpect(status().isOk()).andReturn();
        assertThat(json(list).get("content")).anySatisfy(item -> assertThat(item.get("id").asLong()).isEqualTo(id));
        assertThat(json(list).get("usedCount").asLong()).isEqualTo(before[1] + 1);
        assertThat(json(mockMvc.perform(get("/admin/api/media").param("keyword", String.valueOf(id)).with(asAdmin())).andReturn()).get("content"))
                .as("숫자 키워드는 ID 일치").anySatisfy(item -> assertThat(item.get("id").asLong()).isEqualTo(id));
        assertThat(json(mockMvc.perform(get("/admin/api/media").param("keyword", "없는파일명zzz").with(asAdmin())).andReturn()).get("content")).isEmpty();
        mockMvc.perform(get("/admin/api/media").param("keyword", "a".repeat(200)).with(asAdmin())).andExpect(status().isOk());
        mockMvc.perform(get("/admin/api/media").param("keyword", "a".repeat(201)).with(asAdmin())).andExpect(status().isBadRequest());

        MvcResult detail = mockMvc.perform(get("/admin/api/media/{id}", id).with(asAdmin())).andExpect(status().isOk()).andReturn();
        assertThat(json(detail).get("usages")).isEmpty();

        MvcResult file = mockMvc.perform(get("/admin/api/media/{id}/file", id).with(asAdmin())).andExpect(status().isOk()).andReturn();
        assertThat(file.getResponse().getContentAsByteArray()).isEqualTo(original);
        assertThat(file.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(file.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");

        mockMvc.perform(delete("/admin/api/media/{id}", id).with(asAdmin()).with(csrf())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image WHERE id = ?", Integer.class, id)).isZero();
        assertThat(counter()).containsExactly(before[0], before[1]);
        assertThatThrownBy(() -> fileStorage.open(key)).as("커밋 후 파일도 지워진다").isInstanceOf(StorageFileNotFoundException.class);
    }

    @Test
    @DisplayName("업로드 검증 실패(이미지 아님·크기 초과·파일 없음)는 400이고 행·파일·카운터가 그대로다")
    void invalidUploads_noSideEffects() throws Exception {
        long[] before = counter();
        int rows = jdbc.queryForObject("SELECT COUNT(*) FROM content_image", Integer.class);

        assertThat(upload("a.txt", "text/plain", "x".getBytes(), asAdmin()).getResponse().getStatus()).isEqualTo(400);
        assertThat(upload("big.png", "image/png", new byte[5 * 1024 * 1024 + 1], asAdmin()).getResponse().getStatus()).isEqualTo(400);
        assertThat(upload("empty.png", "image/png", new byte[0], asAdmin()).getResponse().getStatus()).isEqualTo(400);

        assertThat(counter()).containsExactly(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image", Integer.class)).isEqualTo(rows);
    }

    @Test
    @DisplayName("지원하지 않는 origin 필터는 400, 감사 로그에 업로드·삭제가 targetId와 함께 남는다")
    void originFilterAndAudit() throws Exception {
        mockMvc.perform(get("/admin/api/media").param("origin", "WEIRD").with(asAdmin())).andExpect(status().isBadRequest());

        long id = uploadOk(asAdmin());
        mockMvc.perform(delete("/admin/api/media/{id}", id).with(asAdmin()).with(csrf())).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT target_id FROM admin_action_log WHERE id > ? AND action_type = ? AND action_result = 'SUCCESS'",
                Long.class, auditBaseline, AdminActionTypes.MEDIA_UPLOAD)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT target_id FROM admin_action_log WHERE id > ? AND action_type = ? AND action_result = 'SUCCESS'",
                Long.class, auditBaseline, AdminActionTypes.MEDIA_DELETE)).isEqualTo(id);
    }

    // ===== 배너·팝업 사용 =====

    @Test
    @DisplayName("배너가 라이브러리 이미지를 고르면 참조 행만 생기고(새 이미지 행·파일 없음), 사용 중 삭제는 409+사용처이며, 공개 배너 이미지는 같은 바이트다. 배너를 지워도 라이브러리 이미지는 남는다")
    void bannerSelectsLibraryImage() throws Exception {
        long imageId = uploadOk(asAdmin());
        long[] afterUpload = counter();
        int imagesAfterUpload = jdbc.queryForObject("SELECT COUNT(*) FROM content_image", Integer.class);

        long bannerId = createBannerWithImageId(imageId, asAdmin(), 201);

        assertThat(jdbc.queryForObject("SELECT image_id FROM content_image_ref WHERE owner_type = 'BANNER' AND owner_id = ?", Long.class, bannerId))
                .isEqualTo(imageId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image", Integer.class)).isEqualTo(imagesAfterUpload);
        assertThat(counter()).as("선택은 카운터를 늘리지 않는다").containsExactly(afterUpload);
        assertThat(jdbc.queryForObject("SELECT storage_key FROM banner WHERE id = ?", String.class, bannerId)).as("사본 컬럼은 쓰지 않는다").isNull();

        MvcResult detail = mockMvc.perform(get("/admin/api/media/{id}", imageId).with(asAdmin())).andReturn();
        assertThat(json(detail).get("usages")).hasSize(1);
        assertThat(json(detail).get("usages").get(0).get("ownerType").asText()).isEqualTo("BANNER");
        assertThat(json(detail).get("usages").get(0).get("label").asText()).contains("라이브러리 배너");

        MvcResult blocked = mockMvc.perform(delete("/admin/api/media/{id}", imageId).with(asAdmin()).with(csrf())).andExpect(status().isConflict()).andReturn();
        assertThat(json(blocked).get("message").asText()).contains("배너 #" + bannerId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image WHERE id = ?", Integer.class, imageId)).isEqualTo(1);

        MvcResult publicImage = mockMvc.perform(get("/banners/{id}/image", bannerId).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        })).andExpect(status().isOk()).andReturn();
        assertThat(publicImage.getResponse().getContentAsByteArray()).isEqualTo(png());

        mockMvc.perform(delete("/admin/api/banners/{id}", bannerId).with(asAdmin()).with(csrf())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image WHERE id = ?", Integer.class, imageId))
                .as("라이브러리 출처 이미지는 소유자 삭제가 날리지 않는다").isEqualTo(1);
        mockMvc.perform(delete("/admin/api/media/{id}", imageId).with(asAdmin()).with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("배너·팝업이 직접 올린 이미지는 BANNER/POPUP 출처로 라이브러리에 등록되고 카운터를 쓰며, 다른 소유자가 함께 쓰면 마지막 소유자를 지울 때 이미지·파일·카운터가 정리된다")
    void ownedImagesAreRemovedWithLastOwner() throws Exception {
        long[] before = counter();
        long bannerId = createBannerWithUpload(asAdmin());
        long imageId = jdbc.queryForObject("SELECT image_id FROM content_image_ref WHERE owner_type = 'BANNER' AND owner_id = ?", Long.class, bannerId);
        assertThat(jdbc.queryForObject("SELECT scope_type FROM content_image WHERE id = ?", String.class, imageId)).isEqualTo("BANNER");
        assertThat(jdbc.queryForObject("SELECT namespace FROM content_image WHERE id = ?", String.class, imageId)).isEqualTo("banner");
        assertThat(counter()).containsExactly(before[0] + png().length, before[1] + 1);
        String key = jdbc.queryForObject("SELECT storage_key FROM content_image WHERE id = ?", String.class, imageId);

        long popupId = createImagePopup(imageId, false, asAdmin(), 201);     // 배너가 올린 이미지를 팝업이 고른다
        assertThat(json(mockMvc.perform(get("/admin/api/media/{id}", imageId).with(asAdmin())).andReturn()).get("usages")).hasSize(2);

        mockMvc.perform(delete("/admin/api/banners/{id}", bannerId).with(asAdmin()).with(csrf())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image WHERE id = ?", Integer.class, imageId)).as("다른 소유자가 쓰는 동안 남는다").isEqualTo(1);
        assertThat(fileStorage.load(key, "banner")).isEqualTo(png());

        mockMvc.perform(delete("/admin/api/popups/{id}", popupId).with(asAdmin()).with(csrf())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image WHERE id = ?", Integer.class, imageId)).as("마지막 소유자가 사라지면 정리").isZero();
        assertThat(counter()).containsExactly(before);
        assertThatThrownBy(() -> fileStorage.open(key, "banner")).as("파일도 정리됨").isInstanceOf(StorageFileNotFoundException.class);
    }

    @Test
    @DisplayName("팝업이 직접 올리면 popup 네임스페이스·POPUP 출처로 등록되고, 삭제하면 이미지 행·파일·카운터가 함께 정리된다")
    void popupDirectUploadAndDelete() throws Exception {
        long[] before = counter();
        long popupId = createImagePopup(null, true, asAdmin(), 201);
        long imageId = jdbc.queryForObject("SELECT image_id FROM content_image_ref WHERE owner_type = 'POPUP' AND owner_id = ?", Long.class, popupId);
        assertThat(jdbc.queryForObject("SELECT namespace FROM content_image WHERE id = ?", String.class, imageId)).isEqualTo("popup");
        assertThat(counter()).containsExactly(before[0] + png().length, before[1] + 1);

        mockMvc.perform(delete("/admin/api/popups/{id}", popupId).with(asAdmin()).with(csrf())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image WHERE id = ?", Integer.class, imageId)).isZero();
        assertThat(counter()).containsExactly(before);
    }

    @Test
    @DisplayName("고를 수 없는 이미지: 없는 ID·게시판 출처는 404, 2MB 초과(용도 한도)는 400, 파일과 imageId를 함께 보내면 400 — 모두 배너·카운터가 그대로다")
    void selectionRejections() throws Exception {
        long boardImage = insertBoardImage();
        jdbc.update("INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, scope_type, namespace) VALUES (?, 'image/png', ?, 'u', 'LIBRARY', '')",
                "2099/01/01/too-big-" + System.nanoTime() + ".png", 3L * 1024 * 1024);
        long big = jdbc.queryForObject("SELECT MAX(id) FROM content_image WHERE scope_type = 'LIBRARY'", Long.class);
        MediaTestSupport.recomputeCounter(jdbc);
        long[] before = counter();

        createBannerWithImageId(999_999_999L, asAdmin(), 404);
        createBannerWithImageId(boardImage, asAdmin(), 404);
        createBannerWithImageId(big, asAdmin(), 400);
        MvcResult both = mockMvc.perform(multipart("/admin/api/banners").file(new MockMultipartFile("image", "b.png", "image/png", png()))
                .param("title", "x").param("imageId", "1").with(asAdmin()).with(csrf())).andReturn();
        assertThat(both.getResponse().getStatus()).isEqualTo(400);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM banner", Integer.class)).isZero();
        assertThat(counter()).containsExactly(before);
    }

    // ===== 권한 =====

    @Test
    @DisplayName("MANAGER: MEDIA 권한이 없으면 403, READ만 있으면 목록·상세·파일만(업로드·삭제 403), CREATE는 READ가 있어야 의미가 있고, 배너 CREATE만으로는 라이브러리에서 고를 수 없으나 직접 업로드는 된다")
    void permissionMatrix() throws Exception {
        long libraryImage = uploadOk(asAdmin());

        mockMvc.perform(get("/admin/api/media").with(asManager())).andExpect(status().isForbidden());
        mockMvc.perform(multipart("/admin/api/media").file(new MockMultipartFile("file", "a.png", "image/png", png())).with(asManager()).with(csrf()))
                .andExpect(status().isForbidden());

        grant("BANNER", "READ", "CREATE", "DELETE");
        createBannerWithImageId(libraryImage, asManager(), 403);                 // MEDIA READ 없이 고르기
        long ownBanner = createBannerWithUpload(asManager());                    // 직접 업로드는 BANNER 권한만으로 된다
        assertThat(ownBanner).isPositive();

        grant("MEDIA", "READ");
        mockMvc.perform(get("/admin/api/media").with(asManager())).andExpect(status().isOk());
        mockMvc.perform(get("/admin/api/media/{id}", libraryImage).with(asManager())).andExpect(status().isOk());
        mockMvc.perform(get("/admin/api/media/{id}/file", libraryImage).with(asManager())).andExpect(status().isOk());
        mockMvc.perform(multipart("/admin/api/media").file(new MockMultipartFile("file", "a.png", "image/png", png())).with(asManager()).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/api/media/{id}", libraryImage).with(asManager()).with(csrf())).andExpect(status().isForbidden());
        long picked = createBannerWithImageId(libraryImage, asManager(), 201);   // MEDIA READ + BANNER CREATE
        assertThat(picked).isPositive();

        grant("MEDIA", "CREATE", "DELETE");
        MvcResult uploaded = upload("m.png", "image/png", png(), asManager());
        assertThat(uploaded.getResponse().getStatus()).isEqualTo(201);
        long managerImage = json(uploaded).get("id").asLong();
        mockMvc.perform(delete("/admin/api/media/{id}", managerImage).with(asManager()).with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("출처별 가시성: 게시판 본문 이미지는 ADMIN에게만 목록·상세·파일·삭제가 보이고, MEDIA 권한이 있는 MANAGER에게는 목록에서 빠지며 ID를 짐작해도 404다")
    void boardOriginImagesAreAdminOnly() throws Exception {
        long boardImage = insertBoardImage();
        long libraryImage = uploadOk(asAdmin());
        grant("MEDIA", "READ", "DELETE");

        MvcResult adminList = mockMvc.perform(get("/admin/api/media").param("size", "100").with(asAdmin())).andReturn();
        assertThat(json(adminList).get("content")).anySatisfy(item -> assertThat(item.get("id").asLong()).isEqualTo(boardImage));
        mockMvc.perform(get("/admin/api/media/{id}/file", boardImage).with(asAdmin())).andExpect(status().isOk());

        MvcResult managerList = mockMvc.perform(get("/admin/api/media").param("size", "100").with(asManager())).andReturn();
        assertThat(json(managerList).get("content")).noneSatisfy(item -> assertThat(item.get("id").asLong()).isEqualTo(boardImage));
        assertThat(json(managerList).get("content")).anySatisfy(item -> assertThat(item.get("id").asLong()).isEqualTo(libraryImage));
        assertThat(json(mockMvc.perform(get("/admin/api/media").param("origin", "BOARD").with(asManager())).andReturn()).get("content")).isEmpty();
        assertThat(json(managerList).get("totalElements").asLong()).as("총건수도 가시 범위만").isEqualTo(
                json(mockMvc.perform(get("/admin/api/media").param("size", "100").param("origin", "LIBRARY").with(asAdmin())).andReturn()).get("totalElements").asLong()
                        + json(mockMvc.perform(get("/admin/api/media").param("size", "100").param("origin", "BANNER").with(asAdmin())).andReturn()).get("totalElements").asLong()
                        + json(mockMvc.perform(get("/admin/api/media").param("size", "100").param("origin", "POPUP").with(asAdmin())).andReturn()).get("totalElements").asLong());
        mockMvc.perform(get("/admin/api/media/{id}", boardImage).with(asManager())).andExpect(status().isNotFound());
        mockMvc.perform(get("/admin/api/media/{id}/file", boardImage).with(asManager())).andExpect(status().isNotFound());
        mockMvc.perform(delete("/admin/api/media/{id}", boardImage).with(asManager()).with(csrf())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image WHERE id = ?", Integer.class, boardImage)).isEqualTo(1);
    }

    // ===== 게시글 본문 =====

    @Test
    @DisplayName("공개 게시글이 라이브러리 이미지를 참조하면 /content-images/{id}가 익명 200이고 사용처에 게시글이 보이며 삭제는 409다. 배너만 참조하는 이미지는 익명 404다")
    void postBodyUsesLibraryImage_publicRules() throws Exception {
        long bannerOnly = uploadOk(asAdmin());
        createBannerWithImageId(bannerOnly, asAdmin(), 201);
        mockMvc.perform(get("/content-images/{id}", bannerOnly).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        })).andExpect(status().isNotFound());

        long imageId = uploadOk(asAdmin());
        mockMvc.perform(get("/content-images/{id}", imageId)).andExpect(status().isNotFound());       // 미참조 + 익명
        long postId = createNotice("<p>본문<img src=\"/content-images/" + imageId + "\"></p>", asAdmin(), 201);

        MvcResult served = mockMvc.perform(get("/content-images/{id}", imageId)).andExpect(status().isOk()).andReturn();
        assertThat(served.getResponse().getContentAsByteArray()).isEqualTo(png());
        MvcResult detail = mockMvc.perform(get("/admin/api/media/{id}", imageId).with(asAdmin())).andReturn();
        assertThat(json(detail).get("usages").get(0).get("ownerType").asText()).isEqualTo("POST");
        assertThat(json(detail).get("usages").get(0).get("label").asText()).contains("게시글");
        MvcResult blocked = mockMvc.perform(delete("/admin/api/media/{id}", imageId).with(asAdmin()).with(csrf())).andExpect(status().isConflict()).andReturn();
        assertThat(json(blocked).get("message").asText()).contains("게시글 #" + postId);
    }

    @Test
    @DisplayName("게시글 저장이 라이브러리 이미지를 새로 넣으려면 MEDIA 읽기 권한이 필요하다(없으면 403이고 글은 저장되지 않는다) — 이미 참조 중인 이미지는 권한이 회수돼도 유지할 수 있다")
    void postSaveRequiresMediaReadForLibraryImages() throws Exception {
        long imageId = uploadOk(asAdmin());
        grantNoticeBoard("READ", "CREATE", "UPDATE");

        int posts = jdbc.queryForObject("SELECT COUNT(*) FROM post", Integer.class);
        createNotice("<p><img src=\"/content-images/" + imageId + "\"></p>", asManager(), 403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post", Integer.class)).as("거부된 저장은 글을 남기지 않는다").isEqualTo(posts);

        grant("MEDIA", "READ");
        long postId = createNotice("<p><img src=\"/content-images/" + imageId + "\"></p>", asManager(), 201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image_ref WHERE owner_type = 'POST' AND owner_id = ? AND image_id = ?",
                Integer.class, postId, imageId)).isEqualTo(1);

        // 권한 회수 후에도 이미 참조 중인 이미지를 둔 채 수정할 수 있다
        jdbc.update("DELETE FROM member_permission WHERE member_id = ? AND feature = 'MEDIA'", manager.getId());
        cache.invalidate();
        String update = objectMapper.writeValueAsString(Map.of("title", "수정", "content", "<p>수정<img src=\"/content-images/" + imageId + "\"></p>",
                "contentFormat", "HTML", "useYn", true));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/admin/api/boards/{b}/posts/{p}", noticeBoardId, postId)
                .with(asManager()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image_ref WHERE owner_type = 'POST' AND owner_id = ? AND image_id = ?",
                Integer.class, postId, imageId)).isEqualTo(1);
    }

    @Test
    @DisplayName("배너가 올린(banner 네임스페이스) 이미지를 공개 게시글 본문에 넣으면 /content-images/{id}가 banner/ 파일을 읽어 익명 GET·HEAD 200으로 같은 바이트를 주고, 배너를 지워도 글이 참조하는 한 이미지·파일은 남는다")
    void bannerOwnedImageInPublicPost_isServedFromItsNamespace() throws Exception {
        long bannerId = createBannerWithUpload(asAdmin());
        long imageId = jdbc.queryForObject("SELECT image_id FROM content_image_ref WHERE owner_type = 'BANNER' AND owner_id = ?", Long.class, bannerId);
        String key = jdbc.queryForObject("SELECT storage_key FROM content_image WHERE id = ?", String.class, imageId);
        long postId = createNotice("<p><img src=\"/content-images/" + imageId + "\"></p>", asAdmin(), 201);

        MvcResult served = mockMvc.perform(get("/content-images/{id}", imageId)).andExpect(status().isOk()).andReturn();
        assertThat(served.getResponse().getContentAsByteArray()).isEqualTo(png());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head("/content-images/{id}", imageId)).andExpect(status().isOk());

        mockMvc.perform(delete("/admin/api/banners/{id}", bannerId).with(asAdmin()).with(csrf())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image WHERE id = ?", Integer.class, imageId))
                .as("배너만 사라졌고 글이 아직 참조하므로 이미지 행이 남는다").isEqualTo(1);
        assertThat(fileStorage.load(key, "banner")).isEqualTo(png());
        assertThat(mockMvc.perform(get("/content-images/{id}", imageId)).andReturn().getResponse().getContentAsByteArray()).isEqualTo(png());
        assertThat(postId).isPositive();
    }

    @Test
    @DisplayName("게시판 출처 규칙은 그대로: 게시판 에디터 이미지는 같은 게시판의 글에만 들어가고 라이브러리 이미지는 어느 글에나 들어간다")
    void boardScopedRuleUnchanged() throws Exception {
        long libraryImage = uploadOk(asAdmin());
        long boardImage = insertBoardImage();      // 공지 게시판 출처

        createNotice("<p><img src=\"/content-images/" + libraryImage + "\"><img src=\"/content-images/" + boardImage + "\"></p>", asAdmin(), 201);
        assertThat(ContentImageService.isLibraryScope("LIBRARY")).isTrue();
        assertThat(ContentImageService.isLibraryScope("BOARD")).isFalse();
        assertThat(ContentImageService.isOwnedScope("LIBRARY")).as("라이브러리 직접 업로드는 소유자 삭제가 날리지 않는다").isFalse();
    }
}
