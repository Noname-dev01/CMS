package com.cms.admin.board;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionCache;
import com.cms.common.attachment.AttachmentFilePolicy;
import com.cms.common.image.ImageFileValidatorTest;
import com.cms.common.storage.FileStorage;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 게시판 작성 정책(첨부 개수·크기·확장자, 본문 이미지, 예약 게시)의 관리 API 실제 스택 시험(PLAN-board-definition.md PR 2). MockMvc 호출이
 * 실제로 커밋하므로 {@link #cleanUp()}에서 만든 행과 파일을 지운다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class BoardWritePolicyApiIntegrationTest extends MariaDbContainerSupport {

    private static final String FAR_START = "2090-01-01T00:00";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired PermissionCache cache;
    @Autowired FileStorage fileStorage;
    @Autowired ObjectMapper objectMapper;

    private Member admin;
    private final List<Long> boardIds = new ArrayList<>();
    private long auditBaseline;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "wp-admin", Role.ROLE_ADMIN);
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
        for (Long imageId : jdbc.queryForList("SELECT id FROM content_image WHERE uploader_id = ?", Long.class, admin.getUserId())) {
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
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE id > ?", auditBaseline);
        cache.invalidate();
    }

    private RequestPostProcessor asAdmin() {
        return TestMembers.asMember(admin);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 응답은 null 필드를 생략하므로 "값 없음"은 키 누락 또는 null이다. */
    private static boolean absent(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull();
    }

    private long createBoard(Map<String, Object> extra) throws Exception {
        MvcResult result = createBoardRaw(extra);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        long id = body(result).get("id").asLong();
        boardIds.add(id);
        return id;
    }

    private MvcResult createBoardRaw(Map<String, Object> extra) throws Exception {
        Map<String, Object> request = new HashMap<>(Map.of("name", "wp-board-" + System.nanoTime(), "publicYn", true, "attachmentYn", true));
        request.putAll(extra);
        return mockMvc.perform(post("/admin/api/boards").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))).andReturn();
    }

    private int versionOf(long boardId) {
        return jdbc.queryForObject("SELECT version FROM board WHERE id = ?", Integer.class, boardId);
    }

    /** 현재 DB 버전을 붙여 PATCH한다. {@code jsonMembers}는 키가 하나 이상인 객체 리터럴의 속 내용(중괄호 없이)이다. */
    private MvcResult patchBoard(long boardId, String jsonMembers) throws Exception {
        return mockMvc.perform(patch("/admin/api/boards/{id}", boardId).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{" + jsonMembers + ",\"version\":" + versionOf(boardId) + "}")).andReturn();
    }

    private MvcResult createPost(long boardId, String contentHtml, String extraMembers) throws Exception {
        String json = "{\"title\":\"wp-post-" + System.nanoTime() + "\",\"content\":" + objectMapper.writeValueAsString(contentHtml)
                + ",\"contentFormat\":\"HTML\",\"useYn\":true" + (extraMembers == null ? "" : "," + extraMembers) + "}";
        return mockMvc.perform(post("/admin/api/boards/{b}/posts", boardId).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private long createPostOk(long boardId, String contentHtml, String extraMembers) throws Exception {
        MvcResult result = createPost(boardId, contentHtml, extraMembers);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asLong();
    }

    private MvcResult patchPost(long boardId, long postId, String json) throws Exception {
        return mockMvc.perform(patch("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private MvcResult uploadAttachment(long boardId, long postId, String filename, String contentType, byte[] bytes) throws Exception {
        return mockMvc.perform(multipart("/admin/api/boards/{b}/posts/{p}/attachments", boardId, postId)
                .file(new MockMultipartFile("file", filename, contentType, bytes)).with(asAdmin()).with(csrf())).andReturn();
    }

    private MvcResult uploadImage(long boardId) throws Exception {
        return mockMvc.perform(multipart("/admin/api/boards/{b}/content-images", boardId)
                .file(new MockMultipartFile("file", "a.png", "image/png", ImageFileValidatorTest.png(32, 16))).with(asAdmin()).with(csrf())).andReturn();
    }

    private long uploadImageOk(long boardId) throws Exception {
        MvcResult result = uploadImage(boardId);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return body(result).get("id").asLong();
    }

    private int refCount(long postId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM content_image_ref WHERE owner_type = 'POST' AND owner_id = ?", Integer.class, postId);
    }

    private static String img(long imageId) {
        return "<img src=\"/content-images/" + imageId + "\">";
    }

    // ── 정책 저장·응답 ─────────────────────────────────────────

    @Test
    @DisplayName("기본값: 정책 없이 만든 게시판은 5개·10MB·전체 확장자·이미지 허용·예약 허용(현행 동작)이고 응답에 확장자 키가 없다")
    void defaultsAreCurrentBehavior() throws Exception {
        long boardId = createBoard(Map.of());
        JsonNode board = body(mockMvc.perform(get("/admin/api/boards/{id}", boardId).with(asAdmin())).andReturn());
        assertThat(board.get("attachMaxCount").asInt()).isEqualTo(5);
        assertThat(board.get("attachMaxSizeMb").asInt()).isEqualTo(10);
        assertThat(absent(board, "attachExtensions")).as("전체 허용 = 키 누락").isTrue();
        assertThat(board.get("bodyImageYn").asBoolean()).isTrue();
        assertThat(board.get("publishPeriodYn").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT attach_extensions FROM board WHERE id = ?", String.class, boardId)).isNull();
    }

    @Test
    @DisplayName("왕복: 정책을 설정하면 응답·DB에 정규형(소문자·정렬)으로 남고, 전체 확장자를 고르면 NULL로 돌아가며, 누락 PATCH는 정책을 유지한다")
    void policyRoundTrip() throws Exception {
        long boardId = createBoard(Map.of("attachMaxCount", 2, "attachMaxSizeMb", 3, "attachExtensions", List.of("PDF", " pdf ", "hwp"),
                "bodyImageYn", false, "publishPeriodYn", false));
        JsonNode created = body(mockMvc.perform(get("/admin/api/boards/{id}", boardId).with(asAdmin())).andReturn());
        assertThat(created.get("attachMaxCount").asInt()).isEqualTo(2);
        assertThat(created.get("attachMaxSizeMb").asInt()).isEqualTo(3);
        assertThat(created.get("attachExtensions")).extracting(JsonNode::asText).containsExactly("hwp", "pdf");
        assertThat(created.get("bodyImageYn").asBoolean()).isFalse();
        assertThat(created.get("publishPeriodYn").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT attach_extensions FROM board WHERE id = ?", String.class, boardId)).isEqualTo("hwp,pdf");

        // 정책과 무관한 PATCH — 정책 유지
        assertThat(patchBoard(boardId, "\"name\":\"개명\"").getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForMap("SELECT attach_max_count, attach_max_size_mb, attach_extensions, body_image_yn, publish_period_yn FROM board WHERE id = ?",
                boardId)).containsEntry("attach_max_count", 2).containsEntry("attach_max_size_mb", 3).containsEntry("attach_extensions", "hwp,pdf");

        // 일부 → 전체: NULL로 정규화되고 응답에서 키가 빠진다
        String all = objectMapper.writeValueAsString(AttachmentFilePolicy.allowedExtensions());
        MvcResult toAll = patchBoard(boardId, "\"attachExtensions\":" + all);
        assertThat(toAll.getResponse().getStatus()).isEqualTo(200);
        assertThat(absent(body(toAll), "attachExtensions")).isTrue();
        assertThat(jdbc.queryForObject("SELECT attach_extensions FROM board WHERE id = ?", String.class, boardId)).isNull();
    }

    @Test
    @DisplayName("검증: 범위 밖 개수·크기, 빈 확장자·전역 밖 확장자, 51개 배열은 400이고 DB는 그대로이며, 정확히 50개는 통과한다")
    void invalidPolicyRejected() throws Exception {
        long boardId = createBoard(Map.of("attachMaxCount", 3));
        for (String bad : new String[] {"\"attachMaxCount\":0", "\"attachMaxCount\":6", "\"attachMaxSizeMb\":0", "\"attachMaxSizeMb\":11",
                "\"attachExtensions\":[]", "\"attachExtensions\":[\"pdf\",\"exe\"]", "\"attachExtensions\":[null]"}) {
            assertThat(patchBoard(boardId, bad).getResponse().getStatus()).as(bad).isEqualTo(400);
        }
        String fiftyOne = objectMapper.writeValueAsString(java.util.Collections.nCopies(51, "pdf"));
        assertThat(patchBoard(boardId, "\"attachExtensions\":" + fiftyOne).getResponse().getStatus()).as("51개").isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT attach_max_count FROM board WHERE id = ?", Integer.class, boardId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT attach_extensions FROM board WHERE id = ?", String.class, boardId)).isNull();

        String fifty = objectMapper.writeValueAsString(java.util.Collections.nCopies(50, "pdf"));
        assertThat(patchBoard(boardId, "\"attachExtensions\":" + fifty).getResponse().getStatus()).as("50개").isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT attach_extensions FROM board WHERE id = ?", String.class, boardId)).isEqualTo("pdf");
        assertThat(createBoardRaw(Map.of("attachExtensions", List.of("exe"))).getResponse().getStatus()).as("생성도 같은 검증").isEqualTo(400);
    }

    @Test
    @DisplayName("내 게시판 목록: 정책이 실효 값으로 나가고, 확장자 제한이 없으면 전역 전체를 펼쳐서 준다")
    void myBoardsExposeEffectivePolicy() throws Exception {
        long limited = createBoard(Map.of("attachMaxCount", 2, "attachMaxSizeMb", 4, "attachExtensions", List.of("pdf", "hwp"), "bodyImageYn", false));
        long open = createBoard(Map.of());
        JsonNode mine = body(mockMvc.perform(get("/admin/api/members/me/boards").with(asAdmin())).andReturn());
        JsonNode limitedNode = null;
        JsonNode openNode = null;
        for (JsonNode item : mine) {
            if (item.get("boardId").asLong() == limited) {
                limitedNode = item;
            } else if (item.get("boardId").asLong() == open) {
                openNode = item;
            }
        }
        assertThat(limitedNode).isNotNull();
        assertThat(limitedNode.get("attachMaxCount").asInt()).isEqualTo(2);
        assertThat(limitedNode.get("attachMaxSizeMb").asInt()).isEqualTo(4);
        assertThat(limitedNode.get("attachExtensions")).extracting(JsonNode::asText).containsExactly("hwp", "pdf");
        assertThat(limitedNode.get("bodyImageYn").asBoolean()).isFalse();
        assertThat(openNode).isNotNull();
        assertThat(openNode.get("attachExtensions")).extracting(JsonNode::asText).containsExactlyElementsOf(AttachmentFilePolicy.allowedExtensions());
    }

    // ── 첨부 ──────────────────────────────────────────────────

    @Test
    @DisplayName("첨부 크기: 게시판 상한 1MB는 정확히 1MB까지 200, 1바이트 넘으면 400")
    void attachmentSizeLimit() throws Exception {
        long boardId = createBoard(Map.of("attachMaxSizeMb", 1));
        long postId = createPostOk(boardId, "<p>본문</p>", null);
        assertThat(uploadAttachment(boardId, postId, "exact.pdf", "application/pdf", new byte[1024 * 1024]).getResponse().getStatus()).isEqualTo(201);
        MvcResult over = uploadAttachment(boardId, postId, "over.pdf", "application/pdf", new byte[1024 * 1024 + 1]);
        assertThat(over.getResponse().getStatus()).isEqualTo(400);
        assertThat(over.getResponse().getContentAsString()).contains("1MB");
    }

    @Test
    @DisplayName("첨부 확장자: pdf만 허용한 게시판은 png 400·pdf 201, 전역 밖 exe는 전역 메시지로 400")
    void attachmentExtensionLimit() throws Exception {
        long boardId = createBoard(Map.of("attachExtensions", List.of("pdf")));
        long postId = createPostOk(boardId, "<p>본문</p>", null);
        MvcResult png = uploadAttachment(boardId, postId, "a.png", "image/png", "x".getBytes());
        assertThat(png.getResponse().getStatus()).isEqualTo(400);
        assertThat(png.getResponse().getContentAsString()).contains("이 게시판에서 허용하지 않는");
        MvcResult exe = uploadAttachment(boardId, postId, "run.exe", "application/octet-stream", "x".getBytes());
        assertThat(exe.getResponse().getStatus()).isEqualTo(400);
        assertThat(exe.getResponse().getContentAsString()).contains("허용되지 않는 파일 형식");
        assertThat(uploadAttachment(boardId, postId, "ok.pdf", "application/pdf", "x".getBytes()).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("천장: DB를 직접 고쳐 전역 밖 확장자·초과 크기를 넣어도 전역 상한이 먼저 막는다")
    void globalCeilingHolds() throws Exception {
        long boardId = createBoard(Map.of());
        jdbc.update("UPDATE board SET attach_extensions = 'exe,pdf', attach_max_size_mb = 99 WHERE id = ?", boardId);
        long postId = createPostOk(boardId, "<p>본문</p>", null);
        assertThat(uploadAttachment(boardId, postId, "run.exe", "application/octet-stream", "x".getBytes()).getResponse().getStatus()).isEqualTo(400);
        assertThat(uploadAttachment(boardId, postId, "big.pdf", "application/pdf", new byte[10 * 1024 * 1024 + 1]).getResponse().getStatus())
                .as("멀티파트 한도 또는 서비스 천장 — 어느 쪽이든 거절").isGreaterThanOrEqualTo(400);
    }

    @Test
    @DisplayName("상한을 5→2로 낮춰도 이미 3개인 글은 그대로 보이고 삭제되며, 새 업로드는 2개 미만이 될 때까지 409다")
    void loweringLimitKeepsExistingAttachments() throws Exception {
        long boardId = createBoard(Map.of());
        long postId = createPostOk(boardId, "<p>본문</p>", null);
        List<Long> attachmentIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            MvcResult uploaded = uploadAttachment(boardId, postId, "f" + i + ".pdf", "application/pdf", ("내용" + i).getBytes());
            assertThat(uploaded.getResponse().getStatus()).isEqualTo(201);
            attachmentIds.add(body(uploaded).get("id").asLong());
        }
        assertThat(patchBoard(boardId, "\"attachMaxCount\":2").getResponse().getStatus()).isEqualTo(200);

        assertThat(mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}/attachments", boardId, postId).with(asAdmin())).andReturn().getResponse()
                .getContentAsString()).as("기존 3개 조회").contains("f0.pdf", "f1.pdf", "f2.pdf");
        assertThat(mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}/attachments/{a}/content", boardId, postId, attachmentIds.get(0)).with(asAdmin()))
                .andReturn().getResponse().getStatus()).as("기존 첨부 다운로드").isEqualTo(200);

        MvcResult blocked = uploadAttachment(boardId, postId, "new.pdf", "application/pdf", "새".getBytes());
        assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
        assertThat(blocked.getResponse().getContentAsString()).contains("2개");

        assertThat(mockMvc.perform(delete("/admin/api/boards/{b}/posts/{p}/attachments/{a}", boardId, postId, attachmentIds.get(0))
                .with(asAdmin()).with(csrf())).andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(uploadAttachment(boardId, postId, "new.pdf", "application/pdf", "새".getBytes()).getResponse().getStatus())
                .as("2개로 줄어도 상한(2)에 이르러 막힌다").isEqualTo(409);
        assertThat(mockMvc.perform(delete("/admin/api/boards/{b}/posts/{p}/attachments/{a}", boardId, postId, attachmentIds.get(1))
                .with(asAdmin()).with(csrf())).andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(uploadAttachment(boardId, postId, "new.pdf", "application/pdf", "새".getBytes()).getResponse().getStatus()).isEqualTo(201);
    }

    // ── 본문 이미지 ───────────────────────────────────────────

    @Test
    @DisplayName("본문 이미지를 끄면 업로드·새 이미지가 든 글 생성이 400이고, 쓰기 중지가 겹치면 쓰기 중지 문구가 먼저다")
    void bodyImageOffBlocksUploadAndCreate() throws Exception {
        long boardId = createBoard(Map.of());
        long imageId = uploadImageOk(boardId);
        assertThat(patchBoard(boardId, "\"bodyImageYn\":false").getResponse().getStatus()).isEqualTo(200);

        MvcResult upload = uploadImage(boardId);
        assertThat(upload.getResponse().getStatus()).isEqualTo(400);
        assertThat(upload.getResponse().getContentAsString()).contains("본문 이미지를 허용하지 않는");

        MvcResult create = createPost(boardId, "<p>글" + img(imageId) + "</p>", null);
        assertThat(create.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post WHERE board_id = ?", Integer.class, boardId)).as("글이 만들어지지 않는다").isZero();
        assertThat(createPost(boardId, "<p>글자만</p>", null).getResponse().getStatus()).as("이미지 없는 글은 가능").isEqualTo(201);

        assertThat(patchBoard(boardId, "\"writableYn\":false").getResponse().getStatus()).isEqualTo(200);
        assertThat(uploadImage(boardId).getResponse().getContentAsString()).contains("쓰기가 중지된 게시판");
    }

    @Test
    @DisplayName("본문 이미지를 끈 뒤: 이미지 든 기존 글의 제목 수정·본문 그대로 재전송은 200이고 참조가 유지되며, 새 이미지를 넣으면 400, 이미지를 지우면 200")
    void bodyImageOffKeepsExistingImages() throws Exception {
        long boardId = createBoard(Map.of());
        long keptImage = uploadImageOk(boardId);
        long strayImage = uploadImageOk(boardId);       // 업로드만 하고 어느 글에도 넣지 않은 이미지
        String original = "<p>앞" + img(keptImage) + "뒤</p>";
        long postId = createPostOk(boardId, original, null);
        assertThat(refCount(postId)).isEqualTo(1);

        assertThat(patchBoard(boardId, "\"bodyImageYn\":false").getResponse().getStatus()).isEqualTo(200);

        assertThat(patchPost(boardId, postId, "{\"title\":\"제목만 수정\"}").getResponse().getStatus()).as("제목만").isEqualTo(200);
        assertThat(refCount(postId)).isEqualTo(1);
        String resend = "{\"content\":" + objectMapper.writeValueAsString("<p>고친 앞" + img(keptImage) + "뒤</p>") + ",\"contentFormat\":\"HTML\"}";
        assertThat(patchPost(boardId, postId, resend).getResponse().getStatus()).as("기존 이미지를 둔 채 본문 수정").isEqualTo(200);
        assertThat(refCount(postId)).as("참조 유지").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content FROM post WHERE id = ?", String.class, postId)).contains("/content-images/" + keptImage);

        String withNew = "{\"content\":" + objectMapper.writeValueAsString("<p>고친 앞" + img(keptImage) + img(strayImage) + "뒤</p>") + ",\"contentFormat\":\"HTML\"}";
        MvcResult rejected = patchPost(boardId, postId, withNew);
        assertThat(rejected.getResponse().getStatus()).as("새 이미지").isEqualTo(400);
        assertThat(rejected.getResponse().getContentAsString()).contains("본문 이미지를 허용하지 않는");
        assertThat(refCount(postId)).as("거부돼도 기존 참조 유지").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content FROM post WHERE id = ?", String.class, postId)).doesNotContain("/content-images/" + strayImage);

        String removed = "{\"content\":\"<p>이미지 없음</p>\",\"contentFormat\":\"HTML\"}";
        assertThat(patchPost(boardId, postId, removed).getResponse().getStatus()).as("이미지 제거").isEqualTo(200);
        assertThat(refCount(postId)).isZero();
    }

    @Test
    @DisplayName("본문 이미지를 끈 뒤: 존재하지 않는 이미지 ID가 든 기존 글도 텍스트를 고칠 수 있다(깨진 ID는 새 이미지가 아니다)")
    void bodyImageOffToleratesBrokenImageIds() throws Exception {
        long boardId = createBoard(Map.of());
        long postId = createPostOk(boardId, "<p>글" + img(999_999_999L) + "</p>", null);
        assertThat(refCount(postId)).as("존재하지 않는 ID는 참조로 저장되지 않는다").isZero();

        assertThat(patchBoard(boardId, "\"bodyImageYn\":false").getResponse().getStatus()).isEqualTo(200);
        String edit = "{\"content\":" + objectMapper.writeValueAsString("<p>고친 글" + img(999_999_999L) + "</p>") + ",\"contentFormat\":\"HTML\"}";
        assertThat(patchPost(boardId, postId, edit).getResponse().getStatus()).isEqualTo(200);
    }

    // ── 예약 게시 ─────────────────────────────────────────────

    @Test
    @DisplayName("예약 게시를 끄면 기간이 있는 글 생성은 400이고, 기간 없음·빈 기간({})은 201이다")
    void periodOffBlocksCreateWithPeriod() throws Exception {
        long boardId = createBoard(Map.of("publishPeriodYn", false));
        MvcResult rejected = createPost(boardId, "<p>본문</p>", "\"publishPeriod\":{\"start\":\"" + FAR_START + "\"}");
        assertThat(rejected.getResponse().getStatus()).isEqualTo(400);
        assertThat(rejected.getResponse().getContentAsString()).contains("예약 게시를 허용하지 않는");
        assertThat(createPost(boardId, "<p>본문</p>", "\"publishPeriod\":{\"end\":\"" + FAR_START + "\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post WHERE board_id = ?", Integer.class, boardId)).isZero();

        assertThat(createPost(boardId, "<p>본문</p>", null).getResponse().getStatus()).isEqualTo(201);
        assertThat(createPost(boardId, "<p>본문</p>", "\"publishPeriod\":{}").getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("예약 게시를 끈 뒤: 예약 글의 기존 기간은 유지되고 제목 수정은 200, 기간 재설정은 400, 비우기({})는 200이며 즉시 공개 조건에서 풀린다")
    void periodOffKeepsExistingPeriodAndAllowsClearing() throws Exception {
        long boardId = createBoard(Map.of());
        long postId = createPostOk(boardId, "<p>본문</p>", "\"publishPeriod\":{\"start\":\"" + FAR_START + "\"}");
        assertThat(patchBoard(boardId, "\"publishPeriodYn\":false").getResponse().getStatus()).isEqualTo(200);

        assertThat(patchPost(boardId, postId, "{\"title\":\"제목만 수정\"}").getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT publish_start FROM post WHERE id = ?", java.sql.Timestamp.class, postId)).as("기존 기간 유지").isNotNull();

        MvcResult reset = patchPost(boardId, postId, "{\"publishPeriod\":{\"start\":\"2091-01-01T00:00\"}}");
        assertThat(reset.getResponse().getStatus()).isEqualTo(400);
        assertThat(reset.getResponse().getContentAsString()).contains("예약 게시를 허용하지 않는");
        assertThat(jdbc.queryForObject("SELECT publish_start FROM post WHERE id = ?", java.sql.Timestamp.class, postId).toLocalDateTime().getYear())
                .as("거부 — 기간 불변").isEqualTo(2090);

        MvcResult cleared = patchPost(boardId, postId, "{\"publishPeriod\":{}}");
        assertThat(cleared.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT publish_start FROM post WHERE id = ?", java.sql.Timestamp.class, postId)).as("기간 해제").isNull();
        assertThat(jdbc.queryForObject("SELECT publish_end FROM post WHERE id = ?", java.sql.Timestamp.class, postId)).isNull();
    }
}
