package com.cms.admin.board;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionCache;
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
 * 게시판 표시 설정·{@code version}·쓰기 중지의 관리 API 실제 스택 시험(PLAN-board-definition.md PR 1). MockMvc 호출이 실제로 커밋하므로
 * {@link #cleanUp()}에서 만든 행과 파일을 지운다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class BoardDisplaySettingsApiIntegrationTest extends MariaDbContainerSupport {

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
        admin = TestMembers.save(memberRepository, "bd-admin", Role.ROLE_ADMIN);
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
        Map<String, Object> request = new java.util.HashMap<>(Map.of("name", "bd-board-" + System.nanoTime(), "publicYn", true, "attachmentYn", true));
        request.putAll(extra);
        MvcResult result = mockMvc.perform(post("/admin/api/boards").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        long id = body(result).get("id").asLong();
        boardIds.add(id);
        return id;
    }

    private int versionOf(long boardId) {
        return jdbc.queryForObject("SELECT version FROM board WHERE id = ?", Integer.class, boardId);
    }

    /** 현재 DB 버전을 붙여 PATCH한다. {@code json}은 키가 하나 이상인 객체 리터럴의 속 내용(중괄호 없이)이다. */
    private MvcResult patchFresh(long boardId, String jsonMembers) throws Exception {
        return patchRaw(boardId, "{" + jsonMembers + ",\"version\":" + versionOf(boardId) + "}");
    }

    private MvcResult patchRaw(long boardId, String json) throws Exception {
        return mockMvc.perform(patch("/admin/api/boards/{id}", boardId).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private MvcResult createPost(long boardId) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("title", "bd-post-" + System.nanoTime(), "content", "<p>본문</p>",
                "contentFormat", "HTML", "useYn", true));
        return mockMvc.perform(post("/admin/api/boards/{b}/posts", boardId).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    // ── version 계약 ─────────────────────────────────────────────

    @Test
    @DisplayName("생성 응답의 version은 1이고 표시 설정은 현행 기본값이다(누락한 새 필드 = 기본값)")
    void createUsesDefaults() throws Exception {
        long id = createBoard(Map.of());

        MvcResult got = mockMvc.perform(get("/admin/api/boards/{id}", id).with(asAdmin())).andReturn();
        JsonNode board = body(got);
        assertThat(board.get("version").asInt()).isEqualTo(1);
        assertThat(board.get("listPageSize").asInt()).isEqualTo(10);
        assertThat(board.get("showViewCount").asBoolean()).isFalse();
        assertThat(board.get("newBadgeDays").asInt()).isZero();
        assertThat(board.get("writableYn").asBoolean()).isTrue();
        assertThat(absent(board, "description")).isTrue();
        assertThat(absent(board, "authorLabel")).isTrue();
    }

    @Test
    @DisplayName("PATCH의 version: 누락·0 → 400, 낡은 값 → 409(DB 불변), 맞는 값 → 200 + 1 증가, 같은 값으로 다시 보내면 409")
    void patchVersionContract() throws Exception {
        long id = createBoard(Map.of());

        assertThat(patchRaw(id, "{\"name\":\"x\"}").getResponse().getStatus()).as("version 누락").isEqualTo(400);
        assertThat(patchRaw(id, "{\"name\":\"x\",\"version\":0}").getResponse().getStatus()).as("version 0").isEqualTo(400);
        assertThat(patchRaw(id, "{\"version\":1}").getResponse().getStatus()).as("version만 보낸 PATCH = 변경할 필드 없음").isEqualTo(400);

        MvcResult stale = patchRaw(id, "{\"name\":\"stale\",\"version\":999}");
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT name FROM board WHERE id = ?", String.class, id)).isNotEqualTo("stale");
        assertThat(versionOf(id)).as("거절된 요청은 버전을 올리지 않는다").isEqualTo(1);

        MvcResult ok = patchRaw(id, "{\"name\":\"fresh\",\"version\":1}");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(ok).get("version").asInt()).isEqualTo(2);
        assertThat(versionOf(id)).isEqualTo(2);

        assertThat(patchRaw(id, "{\"name\":\"again\",\"version\":1}").getResponse().getStatus()).as("이미 쓴 버전").isEqualTo(409);
    }

    @Test
    @DisplayName("표시 대상 변경을 함께 보내도 한 요청의 version은 정확히 1만 오른다")
    void siteChangeBumpsVersionOnce() throws Exception {
        long siteId = jdbc.queryForObject("SELECT id FROM site WHERE site_key = 'main'", Long.class);
        long id = createBoard(Map.of());

        assertThat(patchFresh(id, "\"name\":\"scoped\",\"siteScope\":\"" + siteId + "\"").getResponse().getStatus()).isEqualTo(200);

        assertThat(versionOf(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("DELETE의 version: 누락 → 400, 낡은 값 → 409(삭제되지 않음), 맞는 값 → 204")
    void deleteVersionContract() throws Exception {
        long id = createBoard(Map.of());

        assertThat(mockMvc.perform(delete("/admin/api/boards/{id}", id).with(asAdmin()).with(csrf())).andReturn().getResponse().getStatus())
                .as("version 누락").isEqualTo(400);
        assertThat(mockMvc.perform(delete("/admin/api/boards/{id}?version=7", id).with(asAdmin()).with(csrf())).andReturn().getResponse().getStatus())
                .as("낡은 version").isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT deleted FROM board WHERE id = ?", Boolean.class, id)).isFalse();

        assertThat(mockMvc.perform(delete("/admin/api/boards/{id}?version=1", id).with(asAdmin()).with(csrf())).andReturn().getResponse().getStatus())
                .isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT deleted FROM board WHERE id = ?", Boolean.class, id)).isTrue();
    }

    // ── 값 범위·정리 ────────────────────────────────────────────

    @Test
    @DisplayName("목록 크기는 10·20·30·50만, 새 글 기간은 0~30만 허용한다 — 경계값 왕복")
    void rangeBoundaries() throws Exception {
        long id = createBoard(Map.of());

        for (int bad : new int[] {0, 9, 15, 40, 51, 100}) {
            assertThat(patchFresh(id, "\"listPageSize\":" + bad).getResponse().getStatus()).as("목록 크기 " + bad).isEqualTo(400);
        }
        for (int bad : new int[] {-1, 31, 365}) {
            assertThat(patchFresh(id, "\"newBadgeDays\":" + bad).getResponse().getStatus()).as("기간 " + bad).isEqualTo(400);
        }
        assertThat(versionOf(id)).as("거절된 요청은 모두 버전 불변").isEqualTo(1);

        assertThat(patchFresh(id, "\"listPageSize\":50,\"newBadgeDays\":30").getResponse().getStatus()).isEqualTo(200);
        assertThat(patchFresh(id, "\"listPageSize\":10,\"newBadgeDays\":0").getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT CONCAT(list_page_size, '|', new_badge_days) FROM board WHERE id = ?", String.class, id)).isEqualTo("10|0");

        MvcResult badCreate = mockMvc.perform(post("/admin/api/boards").with(asAdmin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bd-bad\",\"publicYn\":true,\"attachmentYn\":true,\"listPageSize\":15}")).andReturn();
        assertThat(badCreate.getResponse().getStatus()).as("생성도 같은 범위").isEqualTo(400);
    }

    @Test
    @DisplayName("설명·표시명의 길이는 앞뒤 공백을 정리한 뒤 검사한다 — 정리 후 500자는 통과, 501자는 400, 공백만이면 삭제")
    void textLengthIsCheckedAfterTrim() throws Exception {
        long id = createBoard(Map.of());

        String padded = "  " + "가".repeat(500) + "  ";   // 정리 전 504자, 정리 후 500자
        MvcResult ok = patchFresh(id, "\"description\":\"" + padded + "\"");
        assertThat(ok.getResponse().getStatus()).as("정리 전 길이로 막지 않는다").isEqualTo(200);
        assertThat(body(ok).get("description").asString()).hasSize(500);

        assertThat(patchFresh(id, "\"description\":\"" + "가".repeat(501) + "\"").getResponse().getStatus()).isEqualTo(400);
        assertThat(patchFresh(id, "\"authorLabel\":\"" + "나".repeat(51) + "\"").getResponse().getStatus()).isEqualTo(400);
        assertThat(patchFresh(id, "\"authorLabel\":\"" + "나".repeat(50) + "\"").getResponse().getStatus()).isEqualTo(200);

        MvcResult cleared = patchFresh(id, "\"description\":\"" + " ".repeat(600) + "\"");
        assertThat(cleared.getResponse().getStatus()).as("긴 공백만 = 삭제").isEqualTo(200);
        assertThat(absent(body(cleared), "description")).isTrue();
    }

    @Test
    @DisplayName("설명·표시명 3상태: 값 설정 → 키 누락 PATCH는 유지 → null도 유지 → 빈 문자열은 삭제")
    void descriptionAndLabelThreeStates() throws Exception {
        long id = createBoard(Map.of("description", "  안내 문구  ", "authorLabel", "홍보팀"));
        assertThat(jdbc.queryForObject("SELECT description FROM board WHERE id = ?", String.class, id)).as("생성 때 정리 저장").isEqualTo("안내 문구");

        assertThat(patchFresh(id, "\"name\":\"renamed\"").getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT CONCAT(description, '|', author_label) FROM board WHERE id = ?", String.class, id))
                .as("키 누락 = 유지").isEqualTo("안내 문구|홍보팀");

        assertThat(patchFresh(id, "\"name\":\"renamed2\",\"description\":null,\"authorLabel\":null").getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT CONCAT(description, '|', author_label) FROM board WHERE id = ?", String.class, id))
                .as("null = 유지").isEqualTo("안내 문구|홍보팀");

        assertThat(patchFresh(id, "\"description\":\"\",\"authorLabel\":\"\"").getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT description IS NULL AND author_label IS NULL FROM board WHERE id = ?", Boolean.class, id))
                .as("빈 문자열 = 삭제(NULL 저장)").isTrue();
    }

    // ── 쓰기 중지 ───────────────────────────────────────────────

    @Test
    @DisplayName("쓰기 중지: 생성·수정·첨부 업로드·본문 이미지 업로드는 400이고, 조회·첨부 삭제·게시글 삭제는 된다")
    void writeStopContract() throws Exception {
        long boardId = createBoard(Map.of());
        long postId = body(createPost(boardId)).get("id").asLong();
        MockMultipartFile file = new MockMultipartFile("file", "keep.pdf", "application/pdf", "첨부 내용".getBytes());
        MvcResult uploaded = mockMvc.perform(multipart("/admin/api/boards/{b}/posts/{p}/attachments", boardId, postId).file(file)
                .with(asAdmin()).with(csrf())).andReturn();
        assertThat(uploaded.getResponse().getStatus()).isEqualTo(201);
        long attachmentId = body(uploaded).get("id").asLong();

        assertThat(patchFresh(boardId, "\"writableYn\":false").getResponse().getStatus()).isEqualTo(200);

        MvcResult created = createPost(boardId);
        assertThat(created.getResponse().getStatus()).as("생성").isEqualTo(400);
        assertThat(created.getResponse().getContentAsString()).contains("쓰기가 중지된 게시판");
        assertThat(mockMvc.perform(patch("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"변경\"}")).andReturn().getResponse().getStatus()).as("수정").isEqualTo(400);
        MockMultipartFile second = new MockMultipartFile("file", "new.pdf", "application/pdf", "다른 내용".getBytes());
        assertThat(mockMvc.perform(multipart("/admin/api/boards/{b}/posts/{p}/attachments", boardId, postId).file(second)
                .with(asAdmin()).with(csrf())).andReturn().getResponse().getStatus()).as("첨부 업로드").isEqualTo(400);
        MockMultipartFile image = new MockMultipartFile("file", "a.png", "image/png", ImageFileValidatorTest.png(32, 16));
        assertThat(mockMvc.perform(multipart("/admin/api/boards/{b}/content-images", boardId).file(image).with(asAdmin()).with(csrf()))
                .andReturn().getResponse().getStatus()).as("본문 이미지 업로드").isEqualTo(400);

        assertThat(jdbc.queryForObject("SELECT title FROM post WHERE id = ?", String.class, postId)).as("수정 거절 — 제목 불변").startsWith("bd-post-");
        assertThat(mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asAdmin())).andReturn().getResponse().getStatus())
                .as("상세 조회").isEqualTo(200);
        assertThat(mockMvc.perform(get("/admin/api/boards/{b}/posts", boardId).with(asAdmin())).andReturn().getResponse().getStatus())
                .as("목록 조회").isEqualTo(200);
        assertThat(mockMvc.perform(delete("/admin/api/boards/{b}/posts/{p}/attachments/{a}", boardId, postId, attachmentId)
                .with(asAdmin()).with(csrf())).andReturn().getResponse().getStatus()).as("첨부 삭제").isEqualTo(204);
        assertThat(mockMvc.perform(delete("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asAdmin()).with(csrf()))
                .andReturn().getResponse().getStatus()).as("첨부 삭제 뒤 게시글 삭제").isEqualTo(204);

        assertThat(patchFresh(boardId, "\"writableYn\":true").getResponse().getStatus()).isEqualTo(200);
        assertThat(createPost(boardId).getResponse().getStatus()).as("다시 켜면 생성 가능").isEqualTo(201);
    }

    @Test
    @DisplayName("공통 공지 게시판은 쓰기 중지로 바꿀 수 없다(400, 버전 불변)")
    void commonNoticeBoardCannotBeStopped() throws Exception {
        long noticeId = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
        int before = versionOf(noticeId);

        MvcResult result = patchFresh(noticeId, "\"writableYn\":false");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(versionOf(noticeId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT writable_yn FROM board WHERE id = ?", Boolean.class, noticeId)).isTrue();
    }

    @Test
    @DisplayName("내 게시판 목록에 writableYn이 실린다 — 화면이 쓰기 버튼을 숨기는 데 쓴다")
    void myBoardsExposeWritable() throws Exception {
        long boardId = createBoard(Map.of());
        assertThat(patchFresh(boardId, "\"writableYn\":false").getResponse().getStatus()).isEqualTo(200);

        MvcResult mine = mockMvc.perform(get("/admin/api/members/me/boards").with(asAdmin())).andReturn();

        assertThat(mine.getResponse().getStatus()).isEqualTo(200);
        JsonNode found = null;
        for (JsonNode node : body(mine)) {
            if (node.get("boardId").asLong() == boardId) {
                found = node;
            }
        }
        assertThat(found).isNotNull();
        assertThat(found.get("writableYn").asBoolean()).isFalse();
    }
}
