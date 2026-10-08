package com.cms.admin.board;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 게시글·첨부·게시판 본문 이미지·게시판 삭제의 실제 스택 시험(PLAN-board.md PR B): SecurityConfig·판정기·캐시·감사·MariaDB·로컬 스토리지.
 * MockMvc 호출이 실제로 커밋하므로 {@link #cleanUp()}에서 만든 행과 파일을 지운다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PostApiIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired PermissionCache cache;
    @Autowired FileStorage fileStorage;
    @Autowired ObjectMapper objectMapper;

    private Member admin;
    private Member manager;
    private final List<Long> boardIds = new ArrayList<>();
    private long auditBaseline;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "pt-admin", Role.ROLE_ADMIN);
        manager = TestMembers.save(memberRepository, "pt-manager", Role.ROLE_MANAGER);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
    }

    @AfterEach
    void cleanUp() {
        for (Long boardId : boardIds) {
            for (String key : jdbc.queryForList("SELECT a.storage_key FROM post_attachment a JOIN post p ON p.id = a.post_id WHERE p.board_id = ?",
                    String.class, boardId)) {
                fileStorage.delete(key);
            }
            jdbc.update("DELETE a FROM post_attachment a JOIN post p ON p.id = a.post_id WHERE p.board_id = ?", boardId);
            jdbc.update("DELETE FROM content_image_ref WHERE owner_type = 'POST' AND owner_id IN (SELECT id FROM post WHERE board_id = ?)", boardId);
            jdbc.update("DELETE FROM post WHERE board_id = ?", boardId);
            jdbc.update("DELETE FROM member_board_permission WHERE board_id = ?", boardId);
        }
        List<Long> imageIds = jdbc.queryForList("SELECT id FROM content_image WHERE uploader_id IN (?, ?)",
                Long.class, admin.getUserId(), manager.getUserId());
        for (Long imageId : imageIds) {
            String key = jdbc.queryForObject("SELECT storage_key FROM content_image WHERE id = ?", String.class, imageId);
            jdbc.update("DELETE FROM content_image_ref WHERE image_id = ?", imageId);
            jdbc.update("DELETE FROM content_image WHERE id = ?", imageId);
            fileStorage.delete(key);
        }
        jdbc.update("UPDATE content_image_usage SET total_bytes = (SELECT COALESCE(SUM(file_size), 0) FROM content_image),"
                + " total_count = (SELECT COUNT(*) FROM content_image) WHERE id = 1");
        for (Long boardId : boardIds) {
            jdbc.update("DELETE FROM board WHERE id = ?", boardId);
        }
        TestMembers.delete(jdbc, List.of(admin.getId(), manager.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE id > ?", auditBaseline);
        cache.invalidate();
    }

    private RequestPostProcessor asAdmin() {
        return TestMembers.asMember(admin);
    }

    private RequestPostProcessor asManager() {
        return TestMembers.asMember(manager);
    }

    private long createBoard(boolean attachmentYn) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("name", "pt-board-" + System.nanoTime(), "publicYn", true, "attachmentYn", attachmentYn));
        MvcResult result = mockMvc.perform(post("/admin/api/boards").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        long id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        boardIds.add(id);
        return id;
    }

    private void grant(long boardId, String... actions) {
        for (String action : actions) {
            jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, ?)", manager.getId(), boardId, action);
        }
        cache.invalidate();
    }

    private MvcResult createPost(long boardId, RequestPostProcessor who, String html, boolean useYn) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("title", "pt-post-" + System.nanoTime(), "content", html,
                "contentFormat", "HTML", "useYn", useYn));
        return mockMvc.perform(post("/admin/api/boards/{b}/posts", boardId).with(who).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private long createPostOk(long boardId) throws Exception {
        MvcResult result = createPost(boardId, asAdmin(), "<p>본문</p>", true);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private MvcResult uploadAttachment(long boardId, long postId, String filename, String contentType, RequestPostProcessor who) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, contentType, "첨부 내용".getBytes());
        return mockMvc.perform(multipart("/admin/api/boards/{b}/posts/{p}/attachments", boardId, postId).file(file).with(who).with(csrf())).andReturn();
    }

    private MvcResult uploadImage(long boardId, RequestPostProcessor who) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", ImageFileValidatorTest.png(32, 16));
        return mockMvc.perform(multipart("/admin/api/boards/{b}/content-images", boardId).file(file).with(who).with(csrf())).andReturn();
    }

    private long uploadImageOk(long boardId, RequestPostProcessor who) throws Exception {
        MvcResult result = uploadImage(boardId, who);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private static String imageHtml(long imageId) {
        return "<p><img src=\"/content-images/" + imageId + "\"></p>";
    }

    // ── ADMIN 생명주기 ───────────────────────────────────────────

    @Test
    @DisplayName("ADMIN 게시글 생성·조회·목록(keyword·useYn)·수정·삭제 — 감사 POST_CREATE/UPDATE/DELETE(라벨 없음), 삭제 후 404")
    void adminPostLifecycle() throws Exception {
        long boardId = createBoard(true);

        MvcResult created = createPost(boardId, asAdmin(), "<p>안녕<script>alert(1)</script></p>", true);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        long postId = body.get("id").asLong();
        assertThat(created.getResponse().getHeader("Location")).endsWith("/posts/" + postId);
        assertThat(body.get("boardId").asLong()).isEqualTo(boardId);
        assertThat(body.get("authorId").asString()).isEqualTo(admin.getUserId());
        assertThat(body.get("content").asString()).as("저장·응답 모두 정리된 HTML").doesNotContain("script");
        assertThat(jdbc.queryForObject("SELECT content FROM post WHERE id = ?", String.class, postId)).doesNotContain("script");

        long hidden = createPostOk(boardId);
        mockMvc.perform(patch("/admin/api/boards/{b}/posts/{p}", boardId, hidden).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"useYn\":false}")).andExpect(status().isOk());

        mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asAdmin()))
                .andExpect(status().isOk());
        JsonNode all = objectMapper.readTree(mockMvc.perform(get("/admin/api/boards/{b}/posts", boardId).with(asAdmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(all.get("totalElements").asLong()).isEqualTo(2);
        assertThat(all.get("content").get(0).has("content")).as("목록 항목에는 본문이 없다").isFalse();
        JsonNode onlyHidden = objectMapper.readTree(mockMvc.perform(get("/admin/api/boards/{b}/posts", boardId).param("useYn", "false").with(asAdmin()))
                .andReturn().getResponse().getContentAsString());
        assertThat(onlyHidden.get("totalElements").asLong()).isEqualTo(1);

        mockMvc.perform(patch("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"수정됨\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT title FROM post WHERE id = ?", String.class, postId)).isEqualTo("수정됨");
        JsonNode byKeyword = objectMapper.readTree(mockMvc.perform(get("/admin/api/boards/{b}/posts", boardId).param("keyword", "수정됨").with(asAdmin()))
                .andReturn().getResponse().getContentAsString());
        assertThat(byKeyword.get("totalElements").asLong()).isEqualTo(1);

        mockMvc.perform(patch("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mockMvc.perform(delete("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asAdmin()).with(csrf())).andExpect(status().isNoContent());
        mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asAdmin())).andExpect(status().isNotFound());
        mockMvc.perform(delete("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asAdmin()).with(csrf())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT deleted FROM post WHERE id = ?", Boolean.class, postId)).isTrue();

        List<String> audits = jdbc.queryForList("SELECT CONCAT(action_type, ':', COALESCE(target_label, '')) FROM admin_action_log"
                + " WHERE id > ? AND target_type = 'POST' AND target_id = ? AND action_result = 'SUCCESS' ORDER BY id", String.class, auditBaseline, postId);
        assertThat(audits).containsExactly(AdminActionTypes.POST_CREATE + ":", AdminActionTypes.POST_UPDATE + ":", AdminActionTypes.POST_DELETE + ":");
    }

    @Test
    @DisplayName("본문 검증: 형식 표식 없음·공백·빈 제목은 400 (공지와 같은 규칙), 없는/삭제된 게시판은 404")
    void createValidation() throws Exception {
        long boardId = createBoard(true);

        mockMvc.perform(post("/admin/api/boards/{b}/posts", boardId).with(asAdmin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"t\",\"content\":\"<p>x</p>\"}")).andExpect(status().isBadRequest());
        assertThat(createPost(boardId, asAdmin(), "<p><br></p>", true).getResponse().getStatus()).isEqualTo(400);
        mockMvc.perform(post("/admin/api/boards/{b}/posts", boardId).with(asAdmin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"   \",\"content\":\"<p>x</p>\",\"contentFormat\":\"HTML\"}")).andExpect(status().isBadRequest());
        assertThat(createPost(Long.MAX_VALUE, asAdmin(), "<p>x</p>", true).getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post WHERE board_id = ?", Long.class, boardId)).isZero();
    }

    // ── 게시판별 권한 경계 ───────────────────────────────────────

    @Test
    @DisplayName("게시판 A 권한만 있는 MANAGER: A의 목록·상세·작성·수정·삭제는 허용, B의 모든 게시글·첨부·이미지 API는 403")
    void managerScopedToOneBoard() throws Exception {
        long boardA = createBoard(true);
        long boardB = createBoard(true);
        long postInB = createPostOk(boardB);
        long attachmentInB = objectMapper.readTree(uploadAttachment(boardB, postInB, "b.pdf", "application/pdf", asAdmin())
                .getResponse().getContentAsString()).get("id").asLong();
        grant(boardA, "READ", "CREATE", "UPDATE", "DELETE");

        mockMvc.perform(get("/admin/api/boards/{b}/posts", boardA).with(asManager())).andExpect(status().isOk());
        MvcResult created = createPost(boardA, asManager(), "<p>A 글</p>", true);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        long postInA = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        assertThat(jdbc.queryForObject("SELECT author_id FROM post WHERE id = ?", String.class, postInA)).isEqualTo(manager.getUserId());
        mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}", boardA, postInA).with(asManager())).andExpect(status().isOk());
        mockMvc.perform(patch("/admin/api/boards/{b}/posts/{p}", boardA, postInA).with(asManager()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"A 수정\"}")).andExpect(status().isOk());
        assertThat(uploadAttachment(boardA, postInA, "a.pdf", "application/pdf", asManager()).getResponse().getStatus()).isEqualTo(201);
        assertThat(uploadImage(boardA, asManager()).getResponse().getStatus()).isEqualTo(201);

        // B: 어떤 동작도 403 — 게시글이 실제로 있어도(존재 여부 비노출) 상태가 바뀌지 않는다
        mockMvc.perform(get("/admin/api/boards/{b}/posts", boardB).with(asManager())).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}", boardB, postInB).with(asManager())).andExpect(status().isForbidden());
        assertThat(createPost(boardB, asManager(), "<p>x</p>", true).getResponse().getStatus()).isEqualTo(403);
        mockMvc.perform(patch("/admin/api/boards/{b}/posts/{p}", boardB, postInB).with(asManager()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"탈취\"}")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/api/boards/{b}/posts/{p}", boardB, postInB).with(asManager()).with(csrf())).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}/attachments", boardB, postInB).with(asManager())).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}/attachments/{a}/content", boardB, postInB, attachmentInB).with(asManager()))
                .andExpect(status().isForbidden());
        assertThat(uploadAttachment(boardB, postInB, "x.pdf", "application/pdf", asManager()).getResponse().getStatus()).isEqualTo(403);
        mockMvc.perform(delete("/admin/api/boards/{b}/posts/{p}/attachments/{a}", boardB, postInB, attachmentInB).with(asManager()).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(uploadImage(boardB, asManager()).getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT title FROM post WHERE id = ?", String.class, postInB)).startsWith("pt-post-");
        assertThat(jdbc.queryForObject("SELECT deleted FROM post WHERE id = ?", Boolean.class, postInB)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_attachment WHERE post_id = ?", Long.class, postInB)).isEqualTo(1);

        mockMvc.perform(delete("/admin/api/boards/{b}/posts/{p}", boardA, postInA).with(asManager()).with(csrf())).andExpect(status().isConflict());   // 첨부가 남아 있다
    }

    @Test
    @DisplayName("READ만 있는 MANAGER는 조회만 되고 작성·수정·삭제·첨부 변경·이미지 업로드는 403, 쓰기만 있고 READ가 없으면 모두 403(의존 규칙)")
    void managerActionDependencies() throws Exception {
        long boardId = createBoard(true);
        long postId = createPostOk(boardId);
        long attachmentId = objectMapper.readTree(uploadAttachment(boardId, postId, "r.pdf", "application/pdf", asAdmin())
                .getResponse().getContentAsString()).get("id").asLong();

        grant(boardId, "READ");
        mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asManager())).andExpect(status().isOk());
        mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}/attachments/{a}/content", boardId, postId, attachmentId).with(asManager()))
                .andExpect(status().isOk());
        assertThat(createPost(boardId, asManager(), "<p>x</p>", true).getResponse().getStatus()).isEqualTo(403);
        mockMvc.perform(patch("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asManager()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asManager()).with(csrf())).andExpect(status().isForbidden());
        assertThat(uploadAttachment(boardId, postId, "x.pdf", "application/pdf", asManager()).getResponse().getStatus()).isEqualTo(403);
        mockMvc.perform(delete("/admin/api/boards/{b}/posts/{p}/attachments/{a}", boardId, postId, attachmentId).with(asManager()).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(uploadImage(boardId, asManager()).getResponse().getStatus()).as("READ만으로는 이미지 업로드 불가(CREATE∨UPDATE 재판정)").isEqualTo(403);

        jdbc.update("DELETE FROM member_board_permission WHERE member_id = ?", manager.getId());
        grant(boardId, "CREATE", "UPDATE", "DELETE");
        mockMvc.perform(get("/admin/api/boards/{b}/posts", boardId).with(asManager())).andExpect(status().isForbidden());
        assertThat(createPost(boardId, asManager(), "<p>x</p>", true).getResponse().getStatus()).isEqualTo(403);
        assertThat(uploadImage(boardId, asManager()).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("IDOR: 다른 게시판 경로로 접근한 게시글·첨부는 모두 404 (ADMIN도 마찬가지)")
    void crossBoardAndCrossPostAccessIs404() throws Exception {
        long boardA = createBoard(true);
        long boardB = createBoard(true);
        long postInA = createPostOk(boardA);
        long otherPostInA = createPostOk(boardA);
        long attachmentOfOther = objectMapper.readTree(uploadAttachment(boardA, otherPostInA, "o.pdf", "application/pdf", asAdmin())
                .getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}", boardB, postInA).with(asAdmin())).andExpect(status().isNotFound());
        mockMvc.perform(patch("/admin/api/boards/{b}/posts/{p}", boardB, postInA).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}")).andExpect(status().isNotFound());
        mockMvc.perform(delete("/admin/api/boards/{b}/posts/{p}", boardB, postInA).with(asAdmin()).with(csrf())).andExpect(status().isNotFound());
        assertThat(uploadAttachment(boardB, postInA, "x.pdf", "application/pdf", asAdmin()).getResponse().getStatus()).isEqualTo(404);
        mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}/attachments", boardB, postInA).with(asAdmin())).andExpect(status().isNotFound());
        // 같은 게시판의 다른 게시글 첨부 ID
        mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}/attachments/{a}/content", boardA, postInA, attachmentOfOther).with(asAdmin()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/admin/api/boards/{b}/posts/{p}/attachments/{a}", boardA, postInA, attachmentOfOther).with(asAdmin()).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_attachment WHERE id = ?", Long.class, attachmentOfOther)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleted FROM post WHERE id = ?", Boolean.class, postInA)).isFalse();
    }

    // ── 첨부 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("첨부: 업로드(행·파일)→목록→다운로드(octet-stream·nosniff)→삭제(행·파일), 6번째는 409, 허용 밖 확장자는 400, 첨부가 남은 게시글 삭제는 409")
    void attachmentLifecycle() throws Exception {
        long boardId = createBoard(true);
        long postId = createPostOk(boardId);

        MvcResult uploaded = uploadAttachment(boardId, postId, "../../보고서.pdf", "application/pdf", asAdmin());
        assertThat(uploaded.getResponse().getStatus()).isEqualTo(201);
        long attachmentId = objectMapper.readTree(uploaded.getResponse().getContentAsString()).get("id").asLong();
        assertThat(jdbc.queryForObject("SELECT original_filename FROM post_attachment WHERE id = ?", String.class, attachmentId)).isEqualTo("보고서.pdf");
        String key = jdbc.queryForObject("SELECT storage_key FROM post_attachment WHERE id = ?", String.class, attachmentId);
        assertThat(fileStorage.load(key)).isEqualTo("첨부 내용".getBytes());

        JsonNode list = objectMapper.readTree(mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}/attachments", boardId, postId).with(asAdmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list).hasSize(1);
        MvcResult download = mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}/attachments/{a}/content", boardId, postId, attachmentId).with(asAdmin()))
                .andExpect(status().isOk()).andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo("첨부 내용".getBytes());
        assertThat(download.getResponse().getContentType()).isEqualTo("application/octet-stream");
        assertThat(download.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");

        assertThat(uploadAttachment(boardId, postId, "run.exe", "application/octet-stream", asAdmin()).getResponse().getStatus()).isEqualTo(400);
        assertThat(uploadAttachment(boardId, postId, "a.pdf", "image/png", asAdmin()).getResponse().getStatus()).isEqualTo(400);
        for (int i = 0; i < 4; i++) {
            assertThat(uploadAttachment(boardId, postId, "f" + i + ".pdf", "application/pdf", asAdmin()).getResponse().getStatus()).isEqualTo(201);
        }
        assertThat(uploadAttachment(boardId, postId, "over.pdf", "application/pdf", asAdmin()).getResponse().getStatus()).as("게시글당 5개").isEqualTo(409);

        mockMvc.perform(delete("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asAdmin()).with(csrf())).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT deleted FROM post WHERE id = ?", Boolean.class, postId)).isFalse();

        mockMvc.perform(delete("/admin/api/boards/{b}/posts/{p}/attachments/{a}", boardId, postId, attachmentId).with(asAdmin()).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_attachment WHERE id = ?", Long.class, attachmentId)).isZero();
        assertThatThrownBy(() -> fileStorage.load(key)).as("커밋 후 파일 삭제").isInstanceOf(StorageFileNotFoundException.class);
    }

    @Test
    @DisplayName("첨부를 허용하지 않는 게시판(attachmentYn=false): 새 업로드만 400이고 기존 첨부의 조회·다운로드·삭제는 그대로 된다")
    void attachmentDisabledBlocksOnlyNewUploads() throws Exception {
        long boardId = createBoard(true);
        long postId = createPostOk(boardId);
        long attachmentId = objectMapper.readTree(uploadAttachment(boardId, postId, "keep.pdf", "application/pdf", asAdmin())
                .getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(patch("/admin/api/boards/{id}", boardId).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"attachmentYn\":false}")).andExpect(status().isOk());

        MvcResult blocked = uploadAttachment(boardId, postId, "new.pdf", "application/pdf", asAdmin());
        assertThat(blocked.getResponse().getStatus()).isEqualTo(400);
        assertThat(blocked.getResponse().getContentAsString()).contains("첨부를 허용하지 않는 게시판");
        mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}/attachments", boardId, postId).with(asAdmin())).andExpect(status().isOk());
        mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}/attachments/{a}/content", boardId, postId, attachmentId).with(asAdmin()))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/admin/api/boards/{b}/posts/{p}/attachments/{a}", boardId, postId, attachmentId).with(asAdmin()).with(csrf()))
                .andExpect(status().isNoContent());
    }

    // ── 본문 이미지 출처 ─────────────────────────────────────────

    @Test
    @DisplayName("게시판 이미지: BOARD 출처로 저장, 같은 게시판 글만 참조 가능 — 다른 게시판 이미지는 400이고 기존 참조는 그대로")
    void boardImageScopeAndReferenceEligibility() throws Exception {
        long boardA = createBoard(true);
        long boardB = createBoard(true);
        long imageA = uploadImageOk(boardA, asAdmin());
        assertThat(jdbc.queryForObject("SELECT CONCAT(scope_type, ':', scope_id) FROM content_image WHERE id = ?", String.class, imageA))
                .isEqualTo("BOARD:" + boardA);

        MvcResult sameBoard = createPost(boardA, asAdmin(), imageHtml(imageA), true);
        assertThat(sameBoard.getResponse().getStatus()).isEqualTo(201);
        long postInA = objectMapper.readTree(sameBoard.getResponse().getContentAsString()).get("id").asLong();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image_ref WHERE owner_type = 'POST' AND owner_id = ? AND image_id = ?",
                Long.class, postInA, imageA)).isEqualTo(1);

        MvcResult otherBoard = createPost(boardB, asAdmin(), imageHtml(imageA), true);
        assertThat(otherBoard.getResponse().getStatus()).isEqualTo(400);
        assertThat(otherBoard.getResponse().getContentAsString()).contains("다른 게시판·공지에서 올린 이미지");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post WHERE board_id = ?", Long.class, boardB)).isZero();

        long imageB = uploadImageOk(boardB, asAdmin());
        mockMvc.perform(patch("/admin/api/boards/{b}/posts/{p}", boardA, postInA).with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", imageHtml(imageB), "contentFormat", "HTML"))))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForList("SELECT image_id FROM content_image_ref WHERE owner_type = 'POST' AND owner_id = ?", Long.class, postInA))
                .as("거부된 수정은 기존 참조를 지우지 않는다").containsExactly(imageA);

        // 본문에서 이미지를 빼면 참조도 사라진다
        mockMvc.perform(patch("/admin/api/boards/{b}/posts/{p}", boardA, postInA).with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "<p>글만</p>", "contentFormat", "HTML"))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image_ref WHERE owner_type = 'POST' AND owner_id = ?", Long.class, postInA)).isZero();
    }

    // ── 게시판 삭제 ──────────────────────────────────────────────

    @Test
    @DisplayName("게시판 삭제: 살아 있는 게시글이 있으면 409, 게시글을 지우면 204 + 권한 행 삭제·캐시 반영·BOARD_DELETE 감사, 이후 모든 접근 404")
    void deleteBoard() throws Exception {
        long boardId = createBoard(true);
        long postId = createPostOk(boardId);
        grant(boardId, "READ", "CREATE");
        mockMvc.perform(get("/admin/api/boards/{b}/posts", boardId).with(asManager())).andExpect(status().isOk());

        mockMvc.perform(delete("/admin/api/boards/{id}", boardId).with(asAdmin()).with(csrf())).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT deleted FROM board WHERE id = ?", Boolean.class, boardId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_board_permission WHERE board_id = ?", Long.class, boardId)).isEqualTo(2);

        mockMvc.perform(delete("/admin/api/boards/{id}", boardId).with(asManager()).with(csrf())).andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asAdmin()).with(csrf())).andExpect(status().isNoContent());

        mockMvc.perform(delete("/admin/api/boards/{id}", boardId).with(asAdmin()).with(csrf())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT deleted FROM board WHERE id = ?", Boolean.class, boardId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_board_permission WHERE board_id = ?", Long.class, boardId)).isZero();

        mockMvc.perform(get("/admin/api/boards/{b}/posts", boardId).with(asManager())).andExpect(status().isForbidden());   // 캐시 반영
        mockMvc.perform(get("/admin/api/boards/{b}/posts", boardId).with(asAdmin())).andExpect(status().isNotFound());
        assertThat(createPost(boardId, asAdmin(), "<p>x</p>", true).getResponse().getStatus()).isEqualTo(404);
        mockMvc.perform(get("/admin/api/boards/{id}", boardId).with(asAdmin())).andExpect(status().isNotFound());
        mockMvc.perform(patch("/admin/api/boards/{id}", boardId).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")).andExpect(status().isNotFound());
        mockMvc.perform(delete("/admin/api/boards/{id}", boardId).with(asAdmin()).with(csrf())).andExpect(status().isNotFound());
        assertThat(uploadImage(boardId, asAdmin()).getResponse().getStatus()).isEqualTo(404);

        List<String> audits = jdbc.queryForList("SELECT CONCAT(action_type, ':', COALESCE(target_label, '')) FROM admin_action_log"
                + " WHERE id > ? AND target_type = 'BOARD' AND target_id = ? AND action_type = ? AND action_result = 'SUCCESS'",
                String.class, auditBaseline, String.valueOf(boardId), AdminActionTypes.BOARD_DELETE);
        assertThat(audits).containsExactly(AdminActionTypes.BOARD_DELETE + ":");
    }

    // ── 게시글 관리 화면 게이트 ──────────────────────────────────

    @Test
    @DisplayName("게시글 관리 화면: ADMIN은 200, 게시판 권한이 없는 MANAGER는 403, 어느 게시판에든 READ가 있으면 200 — 내 게시판 목록은 권한 있는 게시판만")
    void postsPageGateAndMyBoards() throws Exception {
        long boardA = createBoard(true);
        long boardB = createBoard(true);

        mockMvc.perform(get("/admin/board/posts").with(asAdmin())).andExpect(status().isOk());
        mockMvc.perform(get("/admin/board/posts").with(asManager())).andExpect(status().isForbidden());   // HTML 403 본문은 실기 검증에서 확인(MockMvc는 sendError 본문을 렌더링하지 않는다)

        grant(boardA, "READ", "CREATE");
        mockMvc.perform(get("/admin/board/posts").with(asManager())).andExpect(status().isOk());
        JsonNode mine = objectMapper.readTree(mockMvc.perform(get("/admin/api/members/me/boards").with(asManager()))
                .andReturn().getResponse().getContentAsString());
        List<Long> ids = new ArrayList<>();
        mine.forEach(row -> ids.add(row.get("boardId").asLong()));
        assertThat(ids).contains(boardA).doesNotContain(boardB);
    }

    // ── 통합 검색 ────────────────────────────────────────────────

    @Test
    @DisplayName("통합 검색 게시글 섹션: ADMIN은 모든 게시판의 미삭제 글(노출 여부 무관), MANAGER는 READ 게시판의 글만, 권한이 없으면 섹션 키 자체가 없다")
    void unifiedSearchPostsSectionFollowsBoardReadPermission() throws Exception {
        long boardA = createBoard(true);
        long boardB = createBoard(true);
        String keyword = "검색대상" + System.nanoTime();
        long inA = objectMapper.readTree(createPost(boardA, asAdmin(), "<p>a</p>", true).getResponse().getContentAsString()).get("id").asLong();
        long inB = objectMapper.readTree(createPost(boardB, asAdmin(), "<p>b</p>", false).getResponse().getContentAsString()).get("id").asLong();
        long deletedInA = objectMapper.readTree(createPost(boardA, asAdmin(), "<p>c</p>", true).getResponse().getContentAsString()).get("id").asLong();
        jdbc.update("UPDATE post SET title = ? WHERE id IN (?, ?, ?)", keyword + "-글", inA, inB, deletedInA);
        jdbc.update("UPDATE post SET deleted = 1 WHERE id = ?", deletedInA);

        JsonNode asAdminResult = objectMapper.readTree(mockMvc.perform(get("/admin/api/search-results").param("keyword", keyword).with(asAdmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(asAdminResult.get("posts").get("total").asLong()).as("삭제 글 제외, 미노출 글 포함").isEqualTo(2);
        List<Long> adminIds = new ArrayList<>();
        asAdminResult.get("posts").get("items").forEach(item -> adminIds.add(item.get("id").asLong()));
        assertThat(adminIds).containsExactlyInAnyOrder(inA, inB);

        JsonNode noGrant = objectMapper.readTree(mockMvc.perform(get("/admin/api/search-results").param("keyword", keyword).with(asManager()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(noGrant.has("posts")).as("읽을 수 있는 게시판이 없으면 섹션 키 생략").isFalse();

        grant(boardA, "READ");
        JsonNode withGrant = objectMapper.readTree(mockMvc.perform(get("/admin/api/search-results").param("keyword", keyword).with(asManager()))
                .andReturn().getResponse().getContentAsString());
        assertThat(withGrant.get("posts").get("total").asLong()).isEqualTo(1);
        JsonNode only = withGrant.get("posts").get("items").get(0);
        assertThat(only.get("id").asLong()).isEqualTo(inA);
        assertThat(only.get("boardId").asLong()).isEqualTo(boardA);
        assertThat(only.has("content")).as("본문은 담지 않는다").isFalse();

        jdbc.update("DELETE FROM member_board_permission WHERE member_id = ?", manager.getId());
        cache.invalidate();
        JsonNode revoked = objectMapper.readTree(mockMvc.perform(get("/admin/api/search-results").param("keyword", keyword).with(asManager()))
                .andReturn().getResponse().getContentAsString());
        assertThat(revoked.has("posts")).as("회수 직후 섹션이 사라진다").isFalse();
    }
}
