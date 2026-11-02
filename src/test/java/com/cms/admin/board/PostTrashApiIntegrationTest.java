package com.cms.admin.board;

import com.cms.admin.log.constant.AdminActionTypes;
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

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 게시글 휴지통(삭제 글 목록·복원)의 실제 스택 시험(PLAN-post-trash.md) — SecurityConfig·판정기·캐시·감사·MariaDB. 동시성(OFF·ON 격리) 경합은
 * {@code AbstractBoardDeleteRaceTest}가 맡는다. MockMvc 호출이 실제로 커밋하므로 {@link #cleanUp()}에서 만든 행을 지운다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PostTrashApiIntegrationTest extends MariaDbContainerSupport {

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
        admin = TestMembers.save(memberRepository, "trash-admin", Role.ROLE_ADMIN);
        manager = TestMembers.save(memberRepository, "trash-manager", Role.ROLE_MANAGER);
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

    private long createBoard() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("name", "trash-board-" + System.nanoTime(), "publicYn", true, "attachmentYn", true));
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

    private long createPostOk(long boardId, String title) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("title", title, "content", "<p>본문</p>", "contentFormat", "HTML", "useYn", true));
        MvcResult result = mockMvc.perform(post("/admin/api/boards/{b}/posts", boardId).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void deletePostOk(long boardId, long postId) throws Exception {
        assertThat(mockMvc.perform(delete("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asAdmin()).with(csrf()))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
    }

    private MvcResult restore(long boardId, long postId, RequestPostProcessor who) throws Exception {
        return mockMvc.perform(post("/admin/api/boards/{b}/posts/{p}/restoration", boardId, postId).with(who).with(csrf())).andReturn();
    }

    private MvcResult trash(long boardId, RequestPostProcessor who) throws Exception {
        return mockMvc.perform(get("/admin/api/boards/{b}/posts/trash", boardId).with(who)).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ── 목록·복원 왕복 ───────────────────────────────────────────

    @Test
    @DisplayName("삭제한 글이 휴지통에 삭제 시각·삭제자와 함께 보이고(본문 제외), 복원하면 비노출·고정 해제로 돌아오며 나머지는 보존된다")
    void trashListAndRestoreRoundTrip() throws Exception {
        long boardId = createBoard();
        jdbc.update("UPDATE board SET pin_max_count = 3 WHERE id = ?", boardId);
        long postId = createPostOk(boardId, "휴지통-왕복");
        // 고정·조회수·노출 기간을 채워 두고 복원 뒤 보존 여부를 본다(기간은 비노출이라 공개에 영향이 없고 노출을 켤 때 원래 의도가 남는다)
        jdbc.update("UPDATE post SET pinned = 1, view_count = 7, publish_start = '2090-01-01 09:00:00', publish_end = '2090-02-01 00:00:00' WHERE id = ?", postId);

        assertThat(json(trash(boardId, asAdmin())).get("totalElements").asLong()).as("살아 있는 글은 휴지통에 없다").isZero();

        deletePostOk(boardId, postId);

        JsonNode page = json(trash(boardId, asAdmin()));
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        JsonNode item = page.get("content").get(0);
        assertThat(item.get("id").asLong()).isEqualTo(postId);
        assertThat(item.get("title").asString()).isEqualTo("휴지통-왕복");
        assertThat(item.get("deletedBy").asString()).as("삭제자 = 삭제 요청자").isEqualTo(admin.getUserId());
        assertThat(item.get("deletedAt").asString()).isNotBlank();
        assertThat(item.has("content")).as("목록에는 본문을 싣지 않는다").isFalse();
        Timestamp updateDate = jdbc.queryForObject("SELECT update_date FROM post WHERE id = ?", Timestamp.class, postId);
        assertThat(item.get("deletedAt").asString()).as("삭제 시각 = 삭제된 글의 마지막 수정 시각").startsWith(updateDate.toLocalDateTime().toString().substring(0, 16));

        // 관리 목록에는 없다
        assertThat(json(mockMvc.perform(get("/admin/api/boards/{b}/posts", boardId).with(asAdmin())).andReturn()).get("totalElements").asLong()).isZero();

        MvcResult restored = restore(boardId, postId, asAdmin());
        assertThat(restored.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(restored);
        assertThat(body.get("id").asLong()).isEqualTo(postId);
        assertThat(body.get("useYn").asBoolean()).as("항상 비노출로 복원").isFalse();
        assertThat(body.get("pinned").asBoolean()).as("고정은 해제").isFalse();
        assertThat(body.get("updaterId").asString()).isEqualTo(admin.getUserId());

        assertThat(jdbc.queryForObject("SELECT deleted FROM post WHERE id = ?", Boolean.class, postId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT view_count FROM post WHERE id = ?", Long.class, postId)).as("조회수 보존").isEqualTo(7L);
        assertThat(jdbc.queryForObject("SELECT author_id FROM post WHERE id = ?", String.class, postId)).as("작성자 보존").isEqualTo(admin.getUserId());
        assertThat(jdbc.queryForObject("SELECT publish_start FROM post WHERE id = ?", Timestamp.class, postId)).as("노출 기간 보존").isNotNull();
        assertThat(jdbc.queryForObject("SELECT publish_end FROM post WHERE id = ?", Timestamp.class, postId)).isNotNull();

        assertThat(json(trash(boardId, asAdmin())).get("totalElements").asLong()).as("복원한 글은 휴지통에서 빠진다").isZero();
        JsonNode list = json(mockMvc.perform(get("/admin/api/boards/{b}/posts", boardId).with(asAdmin())).andReturn());
        assertThat(list.get("totalElements").asLong()).as("관리 목록에 돌아온다").isEqualTo(1);

        List<String> audits = jdbc.queryForList("SELECT CONCAT(action_type, ':', COALESCE(target_label, '')) FROM admin_action_log"
                + " WHERE id > ? AND target_type = 'POST' AND target_id = ? AND action_result = 'SUCCESS' ORDER BY id", String.class, auditBaseline, postId);
        assertThat(audits).containsExactly(AdminActionTypes.POST_CREATE + ":", AdminActionTypes.POST_DELETE + ":", AdminActionTypes.POST_RESTORE + ":");
    }

    @Test
    @DisplayName("복원한 글은 비노출이라 공개 상세가 404이고, 관리자가 노출을 켜면 다시 공개된다")
    void restoredPostIsHiddenUntilExposed() throws Exception {
        long boardId = createBoard();
        long postId = createPostOk(boardId, "휴지통-공개");
        String publicUrl = "/main/boards/board-" + boardId + "/posts/" + postId;
        RequestPostProcessor ip = request -> {
            request.setRemoteAddr("10.88.1." + (1 + (int) (boardId % 250)));
            return request;
        };

        assertThat(mockMvc.perform(get(publicUrl).with(ip)).andReturn().getResponse().getStatus()).isEqualTo(200);
        deletePostOk(boardId, postId);
        assertThat(mockMvc.perform(get(publicUrl).with(ip)).andReturn().getResponse().getStatus()).as("삭제 후 404").isEqualTo(404);

        assertThat(restore(boardId, postId, asAdmin()).getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(get(publicUrl).with(ip)).andReturn().getResponse().getStatus()).as("복원 직후에도 비노출이라 404").isEqualTo(404);

        MvcResult exposed = mockMvc.perform(patch("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"useYn\":true}")).andReturn();
        assertThat(exposed.getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(get(publicUrl).with(ip)).andReturn().getResponse().getStatus()).as("노출을 켜면 공개").isEqualTo(200);
    }

    @Test
    @DisplayName("목록은 삭제 시각 내림차순이고 페이지 크기는 100으로 제한되며, 다른 게시판의 삭제 글은 섞이지 않는다")
    void trashOrderPagingAndBoardIsolation() throws Exception {
        long boardId = createBoard();
        long otherBoardId = createBoard();
        long first = createPostOk(boardId, "먼저-삭제");
        long second = createPostOk(boardId, "나중-삭제");
        long other = createPostOk(otherBoardId, "다른-게시판");
        deletePostOk(boardId, first);
        // 삭제 시각이 확실히 다르도록 먼저 삭제한 글의 삭제 시각을 과거로 돌린다(같은 초에 끝나는 빠른 시험의 순서 모호성 제거)
        jdbc.update("UPDATE post SET update_date = DATE_SUB(update_date, INTERVAL 1 HOUR) WHERE id = ?", first);
        deletePostOk(boardId, second);
        deletePostOk(otherBoardId, other);

        JsonNode page = json(trash(boardId, asAdmin()));
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.get("content").get(0).get("id").asLong()).as("나중에 삭제한 글이 먼저").isEqualTo(second);
        assertThat(page.get("content").get(1).get("id").asLong()).isEqualTo(first);

        JsonNode firstPage = json(mockMvc.perform(get("/admin/api/boards/{b}/posts/trash?size=1&page=0", boardId).with(asAdmin())).andReturn());
        assertThat(firstPage.get("content").size()).isEqualTo(1);
        assertThat(firstPage.get("totalPages").asInt()).isEqualTo(2);
        assertThat(firstPage.get("last").asBoolean()).isFalse();

        JsonNode oversized = json(mockMvc.perform(get("/admin/api/boards/{b}/posts/trash?size=1000", boardId).with(asAdmin())).andReturn());
        assertThat(oversized.get("size").asInt()).as("페이지 크기 100 상한").isEqualTo(100);
    }

    // ── 경계 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("복원 대상이 아닌 글(살아 있는 글·없는 id·다른 게시판 경로·이미 복원된 글)은 같은 404이고 상태가 바뀌지 않는다")
    void restoreRejectsNonDeletedTargetsWithSame404() throws Exception {
        long boardId = createBoard();
        long otherBoardId = createBoard();
        long live = createPostOk(boardId, "살아있음");
        long deleted = createPostOk(boardId, "삭제됨");
        deletePostOk(boardId, deleted);

        assertThat(restore(boardId, live, asAdmin()).getResponse().getStatus()).as("살아 있는 글").isEqualTo(404);
        assertThat(restore(boardId, 999_999_999L, asAdmin()).getResponse().getStatus()).as("없는 id").isEqualTo(404);
        assertThat(restore(otherBoardId, deleted, asAdmin()).getResponse().getStatus()).as("다른 게시판 경로(IDOR)").isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT deleted FROM post WHERE id = ?", Boolean.class, deleted)).as("IDOR 시도로 복원되지 않는다").isTrue();

        assertThat(restore(boardId, deleted, asAdmin()).getResponse().getStatus()).isEqualTo(200);
        assertThat(restore(boardId, deleted, asAdmin()).getResponse().getStatus()).as("이미 복원된 글").isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT deleted FROM post WHERE id = ?", Boolean.class, live)).isFalse();
    }

    @Test
    @DisplayName("삭제된 게시판의 글은 복원할 수 없고(게시판 복원 API가 없다) 휴지통 목록도 404다")
    void deletedBoardIsNotFound() throws Exception {
        long boardId = createBoard();
        long postId = createPostOk(boardId, "게시판삭제");
        deletePostOk(boardId, postId);
        assertThat(mockMvc.perform(delete("/admin/api/boards/{id}?version=1", boardId).with(asAdmin()).with(csrf()))
                .andReturn().getResponse().getStatus()).isEqualTo(204);

        assertThat(restore(boardId, postId, asAdmin()).getResponse().getStatus()).isEqualTo(404);
        assertThat(trash(boardId, asAdmin()).getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT deleted FROM post WHERE id = ?", Boolean.class, postId)).as("복원되지 않는다").isTrue();
    }

    @Test
    @DisplayName("쓰기 중지 게시판의 복원은 400이고 상태가 바뀌지 않으며, 쓰기를 다시 켜면 복원된다(휴지통 조회는 중지 중에도 된다)")
    void writeStoppedBoardRejectsRestore() throws Exception {
        long boardId = createBoard();
        long postId = createPostOk(boardId, "쓰기중지");
        deletePostOk(boardId, postId);
        jdbc.update("UPDATE board SET writable_yn = 0 WHERE id = ?", boardId);

        assertThat(trash(boardId, asAdmin()).getResponse().getStatus()).as("조회는 중지 중에도 가능").isEqualTo(200);
        MvcResult rejected = restore(boardId, postId, asAdmin());
        assertThat(rejected.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT deleted FROM post WHERE id = ?", Boolean.class, postId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log WHERE id > ? AND action_type = 'POST_RESTORE' AND action_result = 'SUCCESS'",
                Long.class, auditBaseline)).as("실패한 복원은 성공 감사를 남기지 않는다").isZero();

        jdbc.update("UPDATE board SET writable_yn = 1 WHERE id = ?", boardId);
        assertThat(restore(boardId, postId, asAdmin()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("고정이던 글을 고정을 끈 게시판(상한 0)에서 복원해도 200이다 — 복원이 고정 상한 판정에 걸리지 않는다")
    void restoreDoesNotHitPinLimit() throws Exception {
        long boardId = createBoard();
        jdbc.update("UPDATE board SET pin_max_count = 1 WHERE id = ?", boardId);
        long pinned = createPostOk(boardId, "고정글");
        long other = createPostOk(boardId, "다른고정글");
        jdbc.update("UPDATE post SET pinned = 1 WHERE id IN (?, ?)", pinned, other);
        deletePostOk(boardId, pinned);
        jdbc.update("UPDATE board SET pin_max_count = 0 WHERE id = ?", boardId);

        MvcResult restored = restore(boardId, pinned, asAdmin());
        assertThat(restored.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(restored).get("pinned").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post WHERE board_id = ? AND pinned = 1 AND deleted = 0", Long.class, boardId))
                .as("복원이 고정 개수를 늘리지 않는다").isEqualTo(1L);
    }

    // ── 권한 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("MANAGER: 휴지통 목록·복원은 그 게시판의 삭제(DELETE) 권한이 필요하다 — 조회·수정만 가지면 403, 삭제 권한(+조회)이 있으면 200, 다른 게시판 권한은 소용없다")
    void permissionRequiresDelete() throws Exception {
        long boardId = createBoard();
        long otherBoardId = createBoard();
        long postId = createPostOk(boardId, "권한시험");
        deletePostOk(boardId, postId);

        assertThat(trash(boardId, asManager()).getResponse().getStatus()).as("권한 없음").isEqualTo(403);
        assertThat(restore(boardId, postId, asManager()).getResponse().getStatus()).isEqualTo(403);

        grant(boardId, "READ", "UPDATE");
        assertThat(trash(boardId, asManager()).getResponse().getStatus()).as("조회·수정만으로는 휴지통을 볼 수 없다").isEqualTo(403);
        assertThat(restore(boardId, postId, asManager()).getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT deleted FROM post WHERE id = ?", Boolean.class, postId)).isTrue();

        grant(otherBoardId, "READ", "DELETE");
        assertThat(trash(boardId, asManager()).getResponse().getStatus()).as("다른 게시판의 삭제 권한은 소용없다").isEqualTo(403);

        grant(boardId, "DELETE");
        assertThat(trash(boardId, asManager()).getResponse().getStatus()).isEqualTo(200);
        MvcResult restored = restore(boardId, postId, asManager());
        assertThat(restored.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(restored).get("updaterId").asString()).as("복원자 = MANAGER").isEqualTo(manager.getUserId());
    }
}
