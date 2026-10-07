package com.cms.admin.contentimage;

import com.cms.admin.contentimage.service.ContentImageService;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.PermissionCache;
import com.cms.common.image.ImageFileValidatorTest;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 본문 이미지 업로드·참조·공개 다운로드 통합 시험(PLAN-html-editor.md 쟁점 6·9·10). 실제 SecurityConfig·판정기·MariaDB·로컬 스토리지.
 * MockMvc 호출이 실제로 커밋하므로 만든 행은 {@link #cleanUp()}에서 지우고 카운터를 재계산한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class ContentImageIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired PermissionCache cache;
    @Autowired FileStorage fileStorage;
    @Autowired ContentImageService contentImageService;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired ObjectMapper objectMapper;

    private Member admin;
    private Member manager;
    private final List<Long> noticeIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "ci-admin", Role.ROLE_ADMIN);
        manager = TestMembers.save(memberRepository, "ci-manager", Role.ROLE_MANAGER);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : noticeIds) {
            jdbc.update("DELETE FROM content_image_ref WHERE owner_type = 'NOTICE' AND owner_id = ?", id);
            jdbc.update("DELETE FROM notice WHERE id = ?", id);
        }
        List<Long> imageIds = jdbc.queryForList(
                "SELECT id FROM content_image WHERE uploader_id IN (?, ?)", Long.class, admin.getUserId(), manager.getUserId());
        for (Long imageId : imageIds) {
            String key = jdbc.queryForObject("SELECT storage_key FROM content_image WHERE id = ?", String.class, imageId);
            jdbc.update("DELETE FROM content_image_ref WHERE image_id = ?", imageId);
            jdbc.update("DELETE FROM content_image WHERE id = ?", imageId);
            fileStorage.delete(key);
        }
        jdbc.update("UPDATE content_image_usage SET total_bytes = (SELECT COALESCE(SUM(file_size), 0) FROM content_image),"
                + " total_count = (SELECT COUNT(*) FROM content_image) WHERE id = 1");
        TestMembers.delete(jdbc, List.of(admin.getId(), manager.getId()));
        cache.invalidate();
        SecurityContextHolder.clearContext();
    }

    private void grant(PermissionAction... actions) {
        jdbc.update("DELETE FROM member_permission WHERE member_id = ? AND feature = 'NOTICE'", manager.getId());
        for (PermissionAction action : actions) {
            jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, 'NOTICE', ?)",
                    manager.getId(), action.name());
        }
        cache.invalidate();
    }

    private static MockMultipartFile pngFile() throws Exception {
        return new MockMultipartFile("file", "a.png", "image/png", ImageFileValidatorTest.png(32, 16));
    }

    private MvcResult upload(MockMultipartFile file, RequestPostProcessor who) throws Exception {
        return mockMvc.perform(multipart("/admin/api/notices/content-images").file(file).with(who).with(csrf())).andReturn();
    }

    private long uploadOk(RequestPostProcessor who) throws Exception {
        MvcResult result = upload(pngFile(), who);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        long id = body.get("id").asLong();
        assertThat(body.get("url").asString()).isEqualTo("/content-images/" + id);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/content-images/" + id);
        return id;
    }

    private long createNotice(String contentHtml, boolean useYn) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "title", "ci-notice-" + System.nanoTime(), "content", contentHtml, "contentFormat", "HTML", "useYn", useYn));
        MvcResult result = mockMvc.perform(post("/admin/api/notices").with(TestMembers.asMember(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        long id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        noticeIds.add(id);
        return id;
    }

    private long[] usage() {
        return jdbc.queryForObject("SELECT total_bytes, total_count FROM content_image_usage WHERE id = 1",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)});
    }

    // ── 업로드 권한·검증 ──────────────────────────────────────────

    @Test
    @DisplayName("ADMIN 업로드: 201 + 행·파일·카운터가 함께 늘어난다")
    void adminUpload_persistsRowFileAndCounter() throws Exception {
        long[] before = usage();

        long id = uploadOk(TestMembers.asMember(admin));

        String key = jdbc.queryForObject("SELECT storage_key FROM content_image WHERE id = ?", String.class, id);
        assertThat(fileStorage.load(key)).isNotEmpty();
        long size = jdbc.queryForObject("SELECT file_size FROM content_image WHERE id = ?", Long.class, id);
        assertThat(usage()).containsExactly(before[0] + size, before[1] + 1);
    }

    @Test
    @DisplayName("MANAGER: READ만이면 403, READ+CREATE·READ+UPDATE면 업로드 가능, 권한 없으면 URL 게이트 403")
    void managerUploadRequiresCreateOrUpdate() throws Exception {
        RequestPostProcessor asManager = TestMembers.asMember(manager);

        grant();
        assertThat(upload(pngFile(), asManager).getResponse().getStatus()).isEqualTo(403);
        grant(PermissionAction.READ);
        assertThat(upload(pngFile(), asManager).getResponse().getStatus()).isEqualTo(403);
        grant(PermissionAction.READ, PermissionAction.DELETE);
        assertThat(upload(pngFile(), asManager).getResponse().getStatus()).isEqualTo(403);

        grant(PermissionAction.READ, PermissionAction.CREATE);
        uploadOk(asManager);
        grant(PermissionAction.READ, PermissionAction.UPDATE);
        uploadOk(asManager);
    }

    @Test
    @DisplayName("이미지가 아니거나 APNG·MIME 불일치·5MB 초과면 400이고 카운터는 그대로다")
    void invalidUploads_rejectedWithoutSideEffects() throws Exception {
        long[] before = usage();
        RequestPostProcessor asAdmin = TestMembers.asMember(admin);

        assertThat(upload(new MockMultipartFile("file", "a.png", "image/png", "not image".getBytes()), asAdmin)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(upload(new MockMultipartFile("file", "a.png", "image/png",
                ImageFileValidatorTest.apng(ImageFileValidatorTest.png(4, 4))), asAdmin).getResponse().getStatus()).isEqualTo(400);
        assertThat(upload(new MockMultipartFile("file", "a.jpg", "image/jpeg", ImageFileValidatorTest.png(4, 4)), asAdmin)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(upload(new MockMultipartFile("file", "a.svg", "image/svg+xml", "<svg/>".getBytes()), asAdmin)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(upload(new MockMultipartFile("file", "big.png", "image/png", new byte[5 * 1024 * 1024 + 1]), asAdmin)
                .getResponse().getStatus()).isEqualTo(400);

        assertThat(usage()).containsExactly(before);
    }

    @Test
    @DisplayName("개수 상한에 도달하면 409, 동시 업로드 2건이 남은 1칸을 다투면 정확히 1건만 성공한다")
    void countLimit_isExactUnderConcurrency() throws Exception {
        long realCount = usage()[1];
        try {
            jdbc.update("UPDATE content_image_usage SET total_count = 10000 WHERE id = 1");
            assertThat(upload(pngFile(), TestMembers.asMember(admin)).getResponse().getStatus()).isEqualTo(409);

            jdbc.update("UPDATE content_image_usage SET total_count = 9999 WHERE id = 1");
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return upload(pngFile(), TestMembers.asMember(admin)).getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            pool.shutdown();

            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertThat(usage()[1]).isEqualTo(10_000);
        } finally {
            jdbc.update("UPDATE content_image_usage SET total_count = ? WHERE id = 1", realCount);
        }
    }

    @Test
    @DisplayName("바이트 상한을 넘으면 409")
    void byteLimit_conflict() throws Exception {
        long realBytes = usage()[0];
        try {
            jdbc.update("UPDATE content_image_usage SET total_bytes = ? WHERE id = 1", 1024L * 1024 * 1024 - 10);
            assertThat(upload(pngFile(), TestMembers.asMember(admin)).getResponse().getStatus()).isEqualTo(409);
        } finally {
            jdbc.update("UPDATE content_image_usage SET total_bytes = ? WHERE id = 1", realBytes);
        }
    }

    @Test
    @DisplayName("업로드 후 트랜잭션이 롤백되면 파일·행·카운터가 모두 원복된다")
    void rollback_cleansFileAndCounter() throws Exception {
        long[] before = usage();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                new com.cms.config.auth.CustomUserDetails(admin), null,
                new com.cms.config.auth.CustomUserDetails(admin).getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        String[] storedKey = new String[1];

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            long id = contentImageService.uploadForNotice(pngFileUnchecked()).id();
            storedKey[0] = jdbc.queryForObject("SELECT storage_key FROM content_image WHERE id = ?", String.class, id);
            assertThat(fileStorage.load(storedKey[0])).isNotEmpty();
            throw new IllegalStateException("강제 롤백");
        })).hasMessage("강제 롤백");

        assertThatThrownBy(() -> fileStorage.load(storedKey[0])).isInstanceOf(StorageFileNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image WHERE storage_key = ?", Long.class, storedKey[0])).isZero();
        assertThat(usage()).containsExactly(before);
    }

    private static MockMultipartFile pngFileUnchecked() {
        try {
            return pngFile();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 참조·공개 다운로드 ────────────────────────────────────────

    @Test
    @DisplayName("공개 공지가 참조한 이미지는 익명 200(인라인·nosniff·no-store), HEAD는 본문 없이 200")
    void publishedNoticeImage_isPublic() throws Exception {
        long imageId = uploadOk(TestMembers.asMember(admin));
        long noticeId = createNotice("<p>본문<img src=\"/content-images/" + imageId + "\"></p>", true);

        assertThat(jdbc.queryForList("SELECT image_id FROM content_image_ref WHERE owner_type = 'NOTICE' AND owner_id = ?",
                Long.class, noticeId)).containsExactly(imageId);
        mockMvc.perform(get("/content-images/{id}", imageId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Content-Disposition"));
        MvcResult headResult = mockMvc.perform(head("/content-images/{id}", imageId)).andExpect(status().isOk()).andReturn();
        assertThat(headResult.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(headResult.getResponse().getContentLengthLong()).isPositive();
    }

    @Test
    @DisplayName("비노출·삭제 공지만 참조하거나 미참조면 익명 404, NOTICE 조회 권한자는 미리보기 200")
    void hiddenOrUnreferenced_notPublic() throws Exception {
        long unreferenced = uploadOk(TestMembers.asMember(admin));
        long hiddenImage = uploadOk(TestMembers.asMember(admin));
        long hiddenNotice = createNotice("<p><img src=\"/content-images/" + hiddenImage + "\"></p>", false);

        mockMvc.perform(get("/content-images/{id}", unreferenced)).andExpect(status().isNotFound());
        mockMvc.perform(get("/content-images/{id}", hiddenImage)).andExpect(status().isNotFound());
        mockMvc.perform(head("/content-images/{id}", hiddenImage)).andExpect(status().isNotFound());

        mockMvc.perform(get("/content-images/{id}", hiddenImage).with(TestMembers.asMember(admin))).andExpect(status().isOk());
        grant(PermissionAction.READ);
        mockMvc.perform(get("/content-images/{id}", hiddenImage).with(TestMembers.asMember(manager))).andExpect(status().isOk());
        grant();
        mockMvc.perform(get("/content-images/{id}", hiddenImage).with(TestMembers.asMember(manager))).andExpect(status().isNotFound());

        // 노출로 바꾸면 공개, 소프트 삭제하면 다시 404
        mockMvc.perform(patch("/admin/api/notices/{id}", hiddenNotice).with(TestMembers.asMember(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"useYn\":true}")).andExpect(status().isOk());
        mockMvc.perform(get("/content-images/{id}", hiddenImage)).andExpect(status().isOk());
        jdbc.update("UPDATE notice SET deleted = 1 WHERE id = ?", hiddenNotice);
        mockMvc.perform(get("/content-images/{id}", hiddenImage)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("본문에서 이미지를 빼고 저장하면 참조가 사라져 더 이상 공개되지 않는다")
    void removingImageFromContent_dropsRef() throws Exception {
        long imageId = uploadOk(TestMembers.asMember(admin));
        long noticeId = createNotice("<p>a<img src=\"/content-images/" + imageId + "\"></p>", true);
        mockMvc.perform(get("/content-images/{id}", imageId)).andExpect(status().isOk());

        mockMvc.perform(patch("/admin/api/notices/{id}", noticeId).with(TestMembers.asMember(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"<p>a</p>\",\"contentFormat\":\"HTML\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/content-images/{id}", imageId)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("본문의 없는 이미지 ID는 참조에서 빠진다")
    void nonexistentImageIds_areNotReferenced() throws Exception {
        long noticeId = createNotice("<p>a<img src=\"/content-images/987654321\"></p>", true);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image_ref WHERE owner_type = 'NOTICE' AND owner_id = ?",
                Long.class, noticeId)).isZero();
    }

    @Test
    @DisplayName("비숫자·0·Long 범위 밖·없는 ID는 404, 비-GET/HEAD는 거부된다")
    void malformedIdsAndMethods() throws Exception {
        for (String id : Set.of("abc", "0", "-1", "9223372036854775808", "999999999")) {
            mockMvc.perform(get("/content-images/" + id)).andExpect(status().isNotFound());
        }
        int postStatus = mockMvc.perform(post("/content-images/1").with(csrf())).andReturn().getResponse().getStatus();
        int putStatus = mockMvc.perform(put("/content-images/1").with(TestMembers.asMember(admin)).with(csrf()))
                .andReturn().getResponse().getStatus();
        assertThat(postStatus).isIn(302, 403);
        assertThat(putStatus).isEqualTo(403);
    }
}
