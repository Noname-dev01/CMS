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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 게시판 정의(ADMIN 전용)와 게시판별 권한 부여·조회의 실제 스택 시험(PLAN-board.md PR A): SecurityConfig·판정기·캐시·감사·MariaDB.
 * MockMvc 호출이 실제로 커밋하므로 {@link #cleanUp()}에서 만든 행을 지운다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class BoardPermissionIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired PermissionCache cache;
    @Autowired ObjectMapper objectMapper;

    private Member admin;
    private Member manager;
    private Member otherManager;
    private final List<Long> boardIds = new ArrayList<>();
    private long auditBaseline;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "bd-admin", Role.ROLE_ADMIN);
        manager = TestMembers.save(memberRepository, "bd-manager", Role.ROLE_MANAGER);
        otherManager = TestMembers.save(memberRepository, "bd-manager2", Role.ROLE_MANAGER);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
    }

    @AfterEach
    void cleanUp() {
        TestMembers.delete(jdbc, List.of(admin.getId(), manager.getId(), otherManager.getId()));
        for (Long id : boardIds) {
            jdbc.update("DELETE FROM member_board_permission WHERE board_id = ?", id);
            jdbc.update("DELETE FROM board WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM admin_action_log WHERE id > ?", auditBaseline);
        cache.invalidate();
    }

    private RequestPostProcessor asAdmin() {
        return TestMembers.asMember(admin);
    }

    private long createBoard(String name, boolean publicYn) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of("name", name, "publicYn", publicYn, "attachmentYn", true));
        MvcResult result = mockMvc.perform(post("/admin/api/boards").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        long id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        boardIds.add(id);
        return id;
    }

    private long version(Member member) {
        return jdbc.queryForObject("SELECT permission_version FROM member WHERE id = ?", Long.class, member.getId());
    }

    private MvcResult putBoardGrants(Member target, String boardGrantsJson) throws Exception {
        String body = "{\"version\":" + version(target) + ",\"grants\":[],\"boardGrants\":" + boardGrantsJson + "}";
        return mockMvc.perform(put("/admin/api/members/{id}/permissions", target.getId()).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    @Test
    @DisplayName("ADMIN 게시판 생성·수정·조회 — 감사 BOARD_CREATE·BOARD_UPDATE(targetId=게시판 ID, 라벨 없음), 이름 공백·필수값 누락 400, 없는 게시판 404")
    void adminManagesBoards() throws Exception {
        long id = createBoard("  자료실  ", true);

        JsonNode created = objectMapper.readTree(mockMvc.perform(get("/admin/api/boards/{id}", id).with(asAdmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(created.get("name").asString()).as("앞뒤 공백 제거").isEqualTo("자료실");

        mockMvc.perform(patch("/admin/api/boards/{id}", id).with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"publicYn\":false,\"attachmentYn\":false}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT public_yn FROM board WHERE id = ?", Boolean.class, id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT attachment_yn FROM board WHERE id = ?", Boolean.class, id)).isFalse();

        mockMvc.perform(post("/admin/api/boards").with(asAdmin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"   \",\"publicYn\":true,\"attachmentYn\":true}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/admin/api/boards").with(asAdmin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"attachmentYn\":true}")).andExpect(status().isBadRequest());
        mockMvc.perform(patch("/admin/api/boards/{id}", id).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mockMvc.perform(patch("/admin/api/boards/{id}", Long.MAX_VALUE).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")).andExpect(status().isNotFound());

        List<String> audits = jdbc.queryForList("SELECT CONCAT(action_type, ':', action_result, ':', COALESCE(target_id, ''), ':',"
                        + " COALESCE(target_label, '')) FROM admin_action_log WHERE id > ? AND target_type = 'BOARD' AND action_result = 'SUCCESS'"
                        + " ORDER BY id", String.class, auditBaseline);
        assertThat(audits).containsExactly(AdminActionTypes.BOARD_CREATE + ":SUCCESS:" + id + ":",
                AdminActionTypes.BOARD_UPDATE + ":SUCCESS:" + id + ":");
    }

    @Test
    @DisplayName("게시판 정의 API·화면은 ADMIN 전용 — 게시판 권한이 있는 MANAGER도 403(API JSON·페이지 HTML)")
    void boardDefinitionIsAdminOnly() throws Exception {
        long id = createBoard("공지", true);
        jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, 'READ')", manager.getId(), id);
        cache.invalidate();
        RequestPostProcessor asManager = TestMembers.asMember(manager);

        mockMvc.perform(get("/admin/api/boards").with(asManager)).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/api/boards/{id}", id).with(asManager)).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/api/boards").with(asManager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"publicYn\":true,\"attachmentYn\":true}")).andExpect(status().isForbidden());
        mockMvc.perform(patch("/admin/api/boards/{id}", id).with(asManager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\"}")).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/board/manage").with(asManager)).andExpect(status().isForbidden());

        mockMvc.perform(get("/admin/board/manage").with(asAdmin())).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT name FROM board WHERE id = ?", String.class, id)).isEqualTo("공지");
    }

    @Test
    @DisplayName("ADMIN이 게시판별 권한을 부여하면 행·버전·매트릭스·내 게시판 목록에 반영되고, 같은 역할의 다른 MANAGER에게는 적용되지 않는다")
    void grantBoardPermission_reflectsAndIsMemberScoped() throws Exception {
        long boardA = createBoard("자료실", true);
        long boardB = createBoard("보도자료", false);
        long before = version(manager);

        MvcResult result = putBoardGrants(manager,
                "[{\"boardId\":" + boardA + ",\"action\":\"READ\"},{\"boardId\":" + boardA + ",\"action\":\"CREATE\"}]");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(version(manager)).isEqualTo(before + 1);
        assertThat(jdbc.queryForList("SELECT CONCAT(board_id, ':', action) FROM member_board_permission WHERE member_id = ? ORDER BY action",
                String.class, manager.getId())).containsExactlyInAnyOrder(boardA + ":READ", boardA + ":CREATE");
        JsonNode matrix = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode boardsNode = matrix.get("boards");
        List<String> rows = new ArrayList<>();
        boardsNode.forEach(row -> {
            if (row.get("boardId").asLong() == boardA || row.get("boardId").asLong() == boardB) {
                rows.add(row.get("boardId").asLong() + "=" + row.get("grantedActions"));
            }
        });
        assertThat(rows).containsExactly(boardA + "=[\"READ\",\"CREATE\"]", boardB + "=[]");
        String label = jdbc.queryForObject("SELECT target_label FROM admin_action_log WHERE id > ? AND action_type = ? AND target_id = ?",
                String.class, auditBaseline, AdminActionTypes.PERMISSION_UPDATE, manager.getId());
        assertThat(label).isEqualTo("v" + before + "→v" + (before + 1) + ": 추가 2·회수 0 | +게시판#" + boardA + ".조회, +게시판#" + boardA + ".생성")
                .doesNotContain("자료실");

        JsonNode mine = objectMapper.readTree(mockMvc.perform(get("/admin/api/members/me/boards").with(TestMembers.asMember(manager)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> myBoards = new ArrayList<>();
        mine.forEach(row -> myBoards.add(row.get("boardId").asLong() + "=" + row.get("actions")));
        assertThat(myBoards).containsExactly(boardA + "=[\"READ\",\"CREATE\"]");

        JsonNode others = objectMapper.readTree(mockMvc.perform(get("/admin/api/members/me/boards").with(TestMembers.asMember(otherManager)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(others.size()).as("교차 회원 격리").isZero();

        JsonNode adminBoards = objectMapper.readTree(mockMvc.perform(get("/admin/api/members/me/boards").with(asAdmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<Long> adminIds = new ArrayList<>();
        adminBoards.forEach(row -> adminIds.add(row.get("boardId").asLong()));
        assertThat(adminIds).contains(boardA, boardB);
    }

    @Test
    @DisplayName("삭제된 게시판·없는 게시판 부여는 400, boardGrants 누락도 400 — 행·버전 불변")
    void invalidBoardTargets_rejected() throws Exception {
        long board = createBoard("삭제될 게시판", true);
        jdbc.update("UPDATE board SET deleted = 1 WHERE id = ?", board);
        long before = version(manager);

        assertThat(putBoardGrants(manager, "[{\"boardId\":" + board + ",\"action\":\"READ\"}]").getResponse().getStatus()).isEqualTo(400);
        assertThat(putBoardGrants(manager, "[{\"boardId\":" + Long.MAX_VALUE + ",\"action\":\"READ\"}]").getResponse().getStatus())
                .isEqualTo(400);
        mockMvc.perform(put("/admin/api/members/{id}/permissions", manager.getId()).with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + before + ",\"grants\":[]}"))
                .andExpect(status().isBadRequest());

        assertThat(version(manager)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_board_permission WHERE member_id = ?", Long.class, manager.getId()))
                .isZero();
    }

    @Test
    @DisplayName("게시판 권한을 회수하면 행이 지워지고 다음 요청부터 내 게시판 목록에서 사라진다(캐시 무효화)")
    void revokeBoardPermission_takesEffect() throws Exception {
        long board = createBoard("자료실", true);
        assertThat(putBoardGrants(manager, "[{\"boardId\":" + board + ",\"action\":\"READ\"}]").getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(mockMvc.perform(get("/admin/api/members/me/boards").with(TestMembers.asMember(manager)))
                .andReturn().getResponse().getContentAsString()).size()).isEqualTo(1);

        assertThat(putBoardGrants(manager, "[]").getResponse().getStatus()).isEqualTo(200);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_board_permission WHERE member_id = ?", Long.class, manager.getId()))
                .isZero();
        assertThat(objectMapper.readTree(mockMvc.perform(get("/admin/api/members/me/boards").with(TestMembers.asMember(manager)))
                .andReturn().getResponse().getContentAsString()).size()).isZero();
    }
}
