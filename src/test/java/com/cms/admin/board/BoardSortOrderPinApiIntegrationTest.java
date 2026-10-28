package com.cms.admin.board;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionCache;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 게시판 표시 순서(`sort_order`)와 상단 고정(`pin_max_count`·`post.pinned`)의 관리 API 실제 스택 시험(PLAN-board-definition.md PR 3).
 * MockMvc 호출이 실제로 커밋하므로 {@link #cleanUp()}에서 만든 행을 지운다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class BoardSortOrderPinApiIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired PermissionCache cache;
    @Autowired ObjectMapper objectMapper;

    private Member admin;
    private Member manager;
    private final List<Long> boardIds = new ArrayList<>();
    private long auditBaseline;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "sp-admin", Role.ROLE_ADMIN);
        manager = TestMembers.save(memberRepository, "sp-manager", Role.ROLE_MANAGER);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
    }

    @AfterEach
    void cleanUp() {
        for (Long boardId : boardIds) {
            jdbc.update("DELETE FROM post WHERE board_id = ?", boardId);
            jdbc.update("DELETE FROM member_board_permission WHERE board_id = ?", boardId);
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

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long createBoard(Map<String, Object> extra) throws Exception {
        Map<String, Object> request = new HashMap<>(Map.of("name", "sp-board-" + System.nanoTime(), "publicYn", true, "attachmentYn", true));
        request.putAll(extra);
        MvcResult result = mockMvc.perform(post("/admin/api/boards").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        long id = body(result).get("id").asLong();
        boardIds.add(id);
        return id;
    }

    private int versionOf(long boardId) {
        return jdbc.queryForObject("SELECT version FROM board WHERE id = ?", Integer.class, boardId);
    }

    private MvcResult patchBoard(long boardId, String jsonMembers) throws Exception {
        return mockMvc.perform(patch("/admin/api/boards/{id}", boardId).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{" + jsonMembers + ",\"version\":" + versionOf(boardId) + "}")).andReturn();
    }

    private MvcResult createPost(long boardId, String extraMembers, RequestPostProcessor who) throws Exception {
        String json = "{\"title\":\"sp-post-" + System.nanoTime() + "\",\"content\":\"<p>x</p>\",\"contentFormat\":\"HTML\",\"useYn\":true"
                + (extraMembers == null ? "" : "," + extraMembers) + "}";
        return mockMvc.perform(post("/admin/api/boards/{b}/posts", boardId).with(who).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private long createPostOk(long boardId, String extraMembers) throws Exception {
        MvcResult result = createPost(boardId, extraMembers, asAdmin());
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asLong();
    }

    private MvcResult patchPost(long boardId, long postId, String json, RequestPostProcessor who) throws Exception {
        return mockMvc.perform(patch("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(who).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private boolean pinnedInDb(long postId) {
        return jdbc.queryForObject("SELECT pinned + 0 FROM post WHERE id = ?", Integer.class, postId) == 1;
    }

    private long pinnedCount(long boardId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM post WHERE board_id = ? AND pinned = 1 AND deleted = 0", Long.class, boardId);
    }

    private void grantBoard(long boardId, String... actions) {
        for (String action : actions) {
            jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, ?)", manager.getId(), boardId, action);
        }
        cache.invalidate();
    }

    // ── 표시 순서 ──────────────────────────────────────────────

    @Test
    @DisplayName("기본값: 순서·상한 없이 만든 게시판은 순서 0·고정 끔(현행 동작)이다")
    void defaultsAreCurrentBehavior() throws Exception {
        long boardId = createBoard(Map.of());
        JsonNode board = body(mockMvc.perform(get("/admin/api/boards/{id}", boardId).with(asAdmin())).andReturn());
        assertThat(board.get("sortOrder").asInt()).isZero();
        assertThat(board.get("pinMaxCount").asInt()).isZero();
    }

    @Test
    @DisplayName("순서·상한 왕복: 생성 값이 응답과 DB에 저장되고, 누락 PATCH는 유지하며, 값을 보낸 PATCH는 바꾸고 version을 +1 한다")
    void sortOrderAndPinMaxRoundTrip() throws Exception {
        long boardId = createBoard(Map.of("sortOrder", 40, "pinMaxCount", 3));
        assertThat(jdbc.queryForObject("SELECT CONCAT(sort_order, '|', pin_max_count) FROM board WHERE id = ?", String.class, boardId)).isEqualTo("40|3");

        int version = versionOf(boardId);
        MvcResult keep = patchBoard(boardId, "\"name\":\"sp-renamed\"");
        assertThat(keep.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(keep).get("sortOrder").asInt()).as("누락 = 유지").isEqualTo(40);
        assertThat(body(keep).get("pinMaxCount").asInt()).isEqualTo(3);

        MvcResult change = patchBoard(boardId, "\"sortOrder\":7,\"pinMaxCount\":0");
        assertThat(change.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(change).get("sortOrder").asInt()).isEqualTo(7);
        assertThat(body(change).get("pinMaxCount").asInt()).isZero();
        assertThat(versionOf(boardId)).isEqualTo(version + 2);
    }

    @Test
    @DisplayName("범위 밖 순서·상한은 400이고 게시판은 바뀌지 않는다 (경계 0·9999·5는 통과)")
    void rangeValidation() throws Exception {
        long boardId = createBoard(Map.of("sortOrder", 5));
        int version = versionOf(boardId);
        for (String json : List.of("\"sortOrder\":-1", "\"sortOrder\":10000", "\"pinMaxCount\":-1", "\"pinMaxCount\":6")) {
            assertThat(patchBoard(boardId, json).getResponse().getStatus()).as(json).isEqualTo(400);
        }
        assertThat(versionOf(boardId)).isEqualTo(version);
        assertThat(patchBoard(boardId, "\"sortOrder\":9999,\"pinMaxCount\":5").getResponse().getStatus()).isEqualTo(200);
        assertThat(patchBoard(boardId, "\"sortOrder\":0,\"pinMaxCount\":0").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("version만 보낸 PATCH는 여전히 '변경할 필드 없음' 400이고, 순서만 보낸 PATCH는 유효하다")
    void sortOrderCountsAsChange() throws Exception {
        long boardId = createBoard(Map.of());
        assertThat(patchBoard(boardId, "\"version\":" + versionOf(boardId)).getResponse().getStatus()).isEqualTo(400);
        assertThat(patchBoard(boardId, "\"sortOrder\":3").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("세 목록(게시판 관리·내 게시판·권한 매트릭스)이 같은 순서(sort_order → id)다 — 같은 값은 id 순")
    void listsShareOrdering() throws Exception {
        long late = createBoard(Map.of("sortOrder", 9000));
        long tieFirst = createBoard(Map.of("sortOrder", 8000));
        long tieSecond = createBoard(Map.of("sortOrder", 8000));
        long early = createBoard(Map.of("sortOrder", 7000));
        grantBoard(late, "READ");
        grantBoard(tieFirst, "READ");
        grantBoard(tieSecond, "READ");
        grantBoard(early, "READ");
        List<Long> expected = List.of(early, tieFirst, tieSecond, late);

        List<Long> adminList = new ArrayList<>();
        for (JsonNode node : body(mockMvc.perform(get("/admin/api/boards").with(asAdmin())).andReturn())) {
            adminList.add(node.get("id").asLong());
        }
        assertThat(adminList.stream().filter(expected::contains).toList()).as("게시판 관리 목록").isEqualTo(expected);

        List<Long> myList = new ArrayList<>();
        for (JsonNode node : body(mockMvc.perform(get("/admin/api/members/me/boards").with(asManager())).andReturn())) {
            myList.add(node.get("boardId").asLong());
        }
        assertThat(myList.stream().filter(expected::contains).toList()).as("내 게시판").isEqualTo(expected);

        List<Long> matrix = new ArrayList<>();
        for (JsonNode node : body(mockMvc.perform(get("/admin/api/members/{id}/permissions", manager.getId()).with(asAdmin())).andReturn()).get("boards")) {
            matrix.add(node.get("boardId").asLong());
        }
        assertThat(matrix.stream().filter(expected::contains).toList()).as("권한 매트릭스").isEqualTo(expected);
    }

    // ── 상단 고정 ──────────────────────────────────────────────

    @Test
    @DisplayName("고정 생성·수정·해제 왕복: 응답과 DB에 반영되고 목록 응답에도 pinned가 나온다")
    void pinRoundTrip() throws Exception {
        long boardId = createBoard(Map.of("pinMaxCount", 2));
        long pinnedPost = createPostOk(boardId, "\"pinned\":true");
        long plainPost = createPostOk(boardId, null);
        assertThat(pinnedInDb(pinnedPost)).isTrue();
        assertThat(pinnedInDb(plainPost)).as("누락 = false").isFalse();

        assertThat(patchPost(boardId, plainPost, "{\"pinned\":true}", asAdmin()).getResponse().getStatus()).isEqualTo(200);
        assertThat(pinnedInDb(plainPost)).isTrue();
        assertThat(patchPost(boardId, plainPost, "{\"title\":\"sp-renamed\"}", asAdmin()).getResponse().getStatus()).isEqualTo(200);
        assertThat(pinnedInDb(plainPost)).as("누락 = 유지").isTrue();

        JsonNode detail = body(mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}", boardId, plainPost).with(asAdmin())).andReturn());
        assertThat(detail.get("pinned").asBoolean()).isTrue();
        JsonNode list = body(mockMvc.perform(get("/admin/api/boards/{b}/posts", boardId).with(asAdmin())).andReturn());
        for (JsonNode item : list.get("content")) {
            assertThat(item.get("pinned").asBoolean()).isTrue();
        }

        assertThat(patchPost(boardId, plainPost, "{\"pinned\":false}", asAdmin()).getResponse().getStatus()).isEqualTo(200);
        assertThat(pinnedInDb(plainPost)).isFalse();
    }

    @Test
    @DisplayName("고정을 끈 게시판(상한 0)에서 새로 고정하면 생성·수정 모두 400이고 글은 바뀌지 않는다")
    void pinDisabledBoardRejectsNewPin() throws Exception {
        long boardId = createBoard(Map.of());
        MvcResult create = createPost(boardId, "\"pinned\":true", asAdmin());
        assertThat(create.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post WHERE board_id = ?", Integer.class, boardId)).isZero();

        long postId = createPostOk(boardId, null);
        assertThat(patchPost(boardId, postId, "{\"pinned\":true}", asAdmin()).getResponse().getStatus()).isEqualTo(400);
        assertThat(pinnedInDb(postId)).isFalse();
        assertThat(patchPost(boardId, postId, "{\"pinned\":false}", asAdmin()).getResponse().getStatus()).as("해제는 언제나 허용").isEqualTo(200);
    }

    @Test
    @DisplayName("상한에 이르면 새 고정은 409이고(생성·수정), 해제한 뒤에는 다시 고정할 수 있다")
    void pinLimitReachedIsConflict() throws Exception {
        long boardId = createBoard(Map.of("pinMaxCount", 2));
        long first = createPostOk(boardId, "\"pinned\":true");
        createPostOk(boardId, "\"pinned\":true");
        long third = createPostOk(boardId, null);

        assertThat(createPost(boardId, "\"pinned\":true", asAdmin()).getResponse().getStatus()).as("생성").isEqualTo(409);
        assertThat(patchPost(boardId, third, "{\"pinned\":true}", asAdmin()).getResponse().getStatus()).as("수정").isEqualTo(409);
        assertThat(pinnedCount(boardId)).isEqualTo(2);

        assertThat(patchPost(boardId, first, "{\"pinned\":false}", asAdmin()).getResponse().getStatus()).isEqualTo(200);
        assertThat(patchPost(boardId, third, "{\"pinned\":true}", asAdmin()).getResponse().getStatus()).isEqualTo(200);
        assertThat(pinnedCount(boardId)).isEqualTo(2);
    }

    @Test
    @DisplayName("고정 개수는 노출 여부·기간과 무관하게 센다 — 비노출·예약 고정 글도 상한을 차지하고, 삭제된 고정 글은 세지 않는다")
    void pinCountIgnoresVisibilityButNotDeletion() throws Exception {
        long boardId = createBoard(Map.of("pinMaxCount", 2));
        long hidden = createPostOk(boardId, "\"pinned\":true");
        assertThat(patchPost(boardId, hidden, "{\"useYn\":false}", asAdmin()).getResponse().getStatus()).isEqualTo(200);
        long scheduled = createPostOk(boardId, "\"pinned\":true,\"publishPeriod\":{\"start\":\"2090-01-01T00:00\"}");
        assertThat(pinnedCount(boardId)).isEqualTo(2);
        assertThat(createPost(boardId, "\"pinned\":true", asAdmin()).getResponse().getStatus()).as("비노출·예약 고정 글이 상한을 차지").isEqualTo(409);

        MvcResult delete = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/admin/api/boards/{b}/posts/{p}", boardId, scheduled).with(asAdmin()).with(csrf())).andReturn();
        assertThat(delete.getResponse().getStatus()).isEqualTo(204);
        assertThat(createPost(boardId, "\"pinned\":true", asAdmin()).getResponse().getStatus()).as("삭제된 고정 글은 세지 않음").isEqualTo(201);
    }

    @Test
    @DisplayName("상한을 낮추거나 꺼도 이미 고정된 글은 그대로이고, 새 고정만 막히며 해제는 가능하다")
    void loweringLimitKeepsExistingPins() throws Exception {
        long boardId = createBoard(Map.of("pinMaxCount", 2));
        long a = createPostOk(boardId, "\"pinned\":true");
        long b = createPostOk(boardId, "\"pinned\":true");
        long c = createPostOk(boardId, null);

        assertThat(patchBoard(boardId, "\"pinMaxCount\":1").getResponse().getStatus()).isEqualTo(200);
        assertThat(pinnedCount(boardId)).as("데이터를 건드리지 않음").isEqualTo(2);
        assertThat(patchPost(boardId, c, "{\"pinned\":true}", asAdmin()).getResponse().getStatus()).isEqualTo(409);
        assertThat(patchPost(boardId, a, "{\"pinned\":true}", asAdmin()).getResponse().getStatus()).as("이미 고정된 글에 true는 통과").isEqualTo(200);

        assertThat(patchBoard(boardId, "\"pinMaxCount\":0").getResponse().getStatus()).isEqualTo(200);
        assertThat(patchPost(boardId, c, "{\"pinned\":true}", asAdmin()).getResponse().getStatus()).isEqualTo(400);
        assertThat(patchPost(boardId, b, "{\"pinned\":false}", asAdmin()).getResponse().getStatus()).as("꺼진 뒤에도 해제 가능").isEqualTo(200);
        assertThat(pinnedCount(boardId)).isEqualTo(1);
    }

    @Test
    @DisplayName("쓰기 중지 게시판의 고정은 고정 메시지가 아니라 쓰기 중지 400이다(판정 순서)")
    void writeStoppedComesFirst() throws Exception {
        long boardId = createBoard(Map.of("pinMaxCount", 1));
        long postId = createPostOk(boardId, null);
        assertThat(patchBoard(boardId, "\"writableYn\":false").getResponse().getStatus()).isEqualTo(200);
        MvcResult result = patchPost(boardId, postId, "{\"pinned\":true}", asAdmin());
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("쓰기가 중지");
    }

    @Test
    @DisplayName("고정만 바꾸는 수정도 유효한 수정이다 — 최종 수정자·수정 시각이 갱신된다")
    void pinOnlyUpdateTouchesPost() throws Exception {
        long boardId = createBoard(Map.of("pinMaxCount", 1));
        long postId = createPostOk(boardId, null);
        jdbc.update("UPDATE post SET updater_id = NULL WHERE id = ?", postId);
        assertThat(patchPost(boardId, postId, "{}", asAdmin()).getResponse().getStatus()).as("변경 필드 없음").isEqualTo(400);
        assertThat(patchPost(boardId, postId, "{\"pinned\":true}", asAdmin()).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT updater_id FROM post WHERE id = ?", String.class, postId)).isEqualTo(admin.getUserId());
    }

    @Test
    @DisplayName("MANAGER: 게시판 UPDATE 권한이 있으면 고정할 수 있고, READ만 있으면 403이다")
    void managerPermissions() throws Exception {
        long boardId = createBoard(Map.of("pinMaxCount", 1));
        long postId = createPostOk(boardId, null);

        grantBoard(boardId, "READ");
        assertThat(patchPost(boardId, postId, "{\"pinned\":true}", asManager()).getResponse().getStatus()).isEqualTo(403);
        assertThat(pinnedInDb(postId)).isFalse();

        grantBoard(boardId, "UPDATE");
        assertThat(patchPost(boardId, postId, "{\"pinned\":true}", asManager()).getResponse().getStatus()).isEqualTo(200);
        assertThat(pinnedInDb(postId)).isTrue();
    }

    @Test
    @DisplayName("내 게시판 응답에 pinMaxCount가 있어 화면이 고정 체크박스를 보일지 정할 수 있다")
    void myBoardsExposePinMaxCount() throws Exception {
        long boardId = createBoard(Map.of("pinMaxCount", 4));
        grantBoard(boardId, "READ");
        for (JsonNode node : body(mockMvc.perform(get("/admin/api/members/me/boards").with(asManager())).andReturn())) {
            if (node.get("boardId").asLong() == boardId) {
                assertThat(node.get("pinMaxCount").asInt()).isEqualTo(4);
                return;
            }
        }
        throw new AssertionError("내 게시판 목록에 게시판이 없다");
    }
}
