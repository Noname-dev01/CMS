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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 게시판 유형(`board_type`)의 관리 API 실제 스택 시험(PLAN-board-definition.md PR 6 D2·D3). 입력 계약(빈 문자열·소문자·공백)은 Java DTO가 아니라
 * 원문 JSON으로 보내 확인한다. 알 수 없는 저장값은 읽을 때만 LIST로 보이고, 유형을 언급하지 않은 수정은 원본을 그대로 보존해야 한다(리뷰 R1-1).
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class BoardTypeApiIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired PermissionCache cache;
    @Autowired ObjectMapper objectMapper;

    private Member admin;
    private Member manager;
    private final List<Long> boardIds = new ArrayList<>();
    private long noticeBoardId;
    private String noticeTypeBackup;
    private long auditBaseline;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "bt-admin", Role.ROLE_ADMIN);
        manager = TestMembers.save(memberRepository, "bt-manager", Role.ROLE_MANAGER);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
        noticeBoardId = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
        noticeTypeBackup = jdbc.queryForObject("SELECT board_type FROM board WHERE id = ?", String.class, noticeBoardId);
    }

    @AfterEach
    void cleanUp() {
        for (Long boardId : boardIds) {
            jdbc.update("DELETE FROM member_board_permission WHERE board_id = ?", boardId);
            jdbc.update("DELETE FROM board WHERE id = ?", boardId);
        }
        jdbc.update("UPDATE board SET board_type = ? WHERE id = ?", noticeTypeBackup, noticeBoardId);
        TestMembers.delete(jdbc, List.of(admin.getId(), manager.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE id > ?", auditBaseline);
        cache.invalidate();
    }

    private RequestPostProcessor asAdmin() {
        return TestMembers.asMember(admin);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult createBoardRaw(String extraMembers) throws Exception {
        String json = "{\"name\":\"bt-board-" + System.nanoTime() + "\",\"publicYn\":true,\"attachmentYn\":true"
                + (extraMembers == null ? "" : "," + extraMembers) + "}";
        MvcResult result = mockMvc.perform(post("/admin/api/boards").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
        if (result.getResponse().getStatus() == 201) {
            boardIds.add(body(result).get("id").asLong());
        }
        return result;
    }

    private long createBoard(String extraMembers) throws Exception {
        MvcResult result = createBoardRaw(extraMembers);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asLong();
    }

    private int versionOf(long boardId) {
        return jdbc.queryForObject("SELECT version FROM board WHERE id = ?", Integer.class, boardId);
    }

    private String typeInDb(long boardId) {
        return jdbc.queryForObject("SELECT board_type FROM board WHERE id = ?", String.class, boardId);
    }

    private MvcResult patchBoard(long boardId, String jsonMembers, RequestPostProcessor who, int version) throws Exception {
        return mockMvc.perform(patch("/admin/api/boards/{id}", boardId).with(who).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{" + jsonMembers + ",\"version\":" + version + "}")).andReturn();
    }

    private MvcResult patchBoard(long boardId, String jsonMembers) throws Exception {
        return patchBoard(boardId, jsonMembers, asAdmin(), versionOf(boardId));
    }

    private long insertBoardWithRawType(String rawType) {
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted, board_type, create_date, update_date)"
                + " VALUES (?, 1, 1, 0, ?, NOW(6), NOW(6))", "bt-raw-" + System.nanoTime(), rawType);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM board", Long.class);
        boardIds.add(id);
        return id;
    }

    @Test
    @DisplayName("생성: 유형을 생략하면 LIST이고, 지정하면 그대로 저장되며 응답과 DB가 같다")
    void createDefaultsAndRoundTrip() throws Exception {
        long plain = createBoard(null);
        assertThat(typeInDb(plain)).isEqualTo("LIST");

        MvcResult explicit = createBoardRaw("\"boardType\":\"GALLERY\"");
        assertThat(explicit.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(explicit).get("boardType").asText()).isEqualTo("GALLERY");
        assertThat(typeInDb(body(explicit).get("id").asLong())).isEqualTo("GALLERY");

        MvcResult webzine = createBoardRaw("\"boardType\":\"WEBZINE\"");
        assertThat(typeInDb(body(webzine).get("id").asLong())).isEqualTo("WEBZINE");

        MvcResult detail = mockMvc.perform(get("/admin/api/boards/{id}", plain).with(asAdmin())).andReturn();
        assertThat(body(detail).get("boardType").asText()).isEqualTo("LIST");
    }

    @Test
    @DisplayName("생성: 잘못된 유형은 400이고 게시판이 만들어지지 않는다")
    void createRejectsInvalidType() throws Exception {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM board", Long.class);

        for (String bad : List.of("\"boardType\":\"\"", "\"boardType\":\"gallery\"", "\"boardType\":\" LIST\"", "\"boardType\":\"CARD\"")) {
            assertThat(createBoardRaw(bad).getResponse().getStatus()).as(bad).isEqualTo(400);
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM board", Long.class)).isEqualTo(before);
    }

    @Test
    @DisplayName("수정: 유형 키가 없으면 유지, 있으면 바뀌고 version은 요청마다 +1, 유형만 보낸 수정도 유효하다")
    void patchKeepChangeAndOnlyType() throws Exception {
        long id = createBoard("\"boardType\":\"GALLERY\"");
        int v1 = versionOf(id);

        MvcResult rename = patchBoard(id, "\"name\":\"bt-renamed\"");
        assertThat(rename.getResponse().getStatus()).isEqualTo(200);
        assertThat(typeInDb(id)).as("유형 키가 없으면 유지").isEqualTo("GALLERY");
        assertThat(versionOf(id)).isEqualTo(v1 + 1);

        MvcResult onlyType = patchBoard(id, "\"boardType\":\"WEBZINE\"");
        assertThat(onlyType.getResponse().getStatus()).as("유형만 보낸 수정은 '변경할 필드 없음'이 아니다").isEqualTo(200);
        assertThat(body(onlyType).get("boardType").asText()).isEqualTo("WEBZINE");
        assertThat(typeInDb(id)).isEqualTo("WEBZINE");
        assertThat(versionOf(id)).isEqualTo(v1 + 2);

        MvcResult explicitNull = patchBoard(id, "\"boardType\":null,\"name\":\"bt-renamed-2\"");
        assertThat(explicitNull.getResponse().getStatus()).isEqualTo(200);
        assertThat(typeInDb(id)).as("명시 null도 유지").isEqualTo("WEBZINE");
    }

    @Test
    @DisplayName("수정: 잘못된 유형은 400이고 유형·이름·version이 그대로다 (빈 문자열·소문자·공백·숫자·불리언·배열·객체)")
    void patchRejectsInvalidType() throws Exception {
        long id = createBoard("\"boardType\":\"GALLERY\"");
        int version = versionOf(id);
        String name = jdbc.queryForObject("SELECT name FROM board WHERE id = ?", String.class, id);

        List<String> invalid = List.of("\"\"", "\"gallery\"", "\" LIST\"", "\"LIST \"", "\"CARD\"", "1", "true", "[\"LIST\"]", "{\"a\":1}");
        for (String value : invalid) {
            MvcResult result = patchBoard(id, "\"name\":\"bt-must-not-apply\",\"boardType\":" + value);
            assertThat(result.getResponse().getStatus()).as(value).isEqualTo(400);
        }

        assertThat(typeInDb(id)).isEqualTo("GALLERY");
        assertThat(jdbc.queryForObject("SELECT name FROM board WHERE id = ?", String.class, id)).isEqualTo(name);
        assertThat(versionOf(id)).isEqualTo(version);
    }

    @Test
    @DisplayName("수정: 낡은 version은 409이고 유형이 바뀌지 않는다")
    void patchStaleVersionConflicts() throws Exception {
        long id = createBoard(null);
        int stale = versionOf(id);
        assertThat(patchBoard(id, "\"name\":\"bt-fresh\"").getResponse().getStatus()).isEqualTo(200);

        MvcResult result = patchBoard(id, "\"boardType\":\"GALLERY\"", asAdmin(), stale);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(typeInDb(id)).isEqualTo("LIST");
    }

    @Test
    @DisplayName("수정: MANAGER는 403이고 유형이 바뀌지 않는다 (게시판 정의는 ADMIN 전용)")
    void managerCannotChangeType() throws Exception {
        long id = createBoard(null);

        MvcResult result = patchBoard(id, "\"boardType\":\"GALLERY\"", TestMembers.asMember(manager), versionOf(id));

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(typeInDb(id)).isEqualTo("LIST");
    }

    @Test
    @DisplayName("공지 게시판(공통)도 유형을 바꿀 수 있다")
    void noticeBoardTypeIsChangeable() throws Exception {
        MvcResult result = patchBoard(noticeBoardId, "\"boardType\":\"GALLERY\"");

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(typeInDb(noticeBoardId)).isEqualTo("GALLERY");
    }

    @Test
    @DisplayName("쓰기 중지 게시판도 유형을 바꿀 수 있다 — 정의 변경은 게시글 쓰기가 아니다")
    void writeStoppedBoardTypeIsChangeable() throws Exception {
        long id = createBoard("\"writableYn\":false");

        MvcResult result = patchBoard(id, "\"boardType\":\"WEBZINE\"");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(typeInDb(id)).isEqualTo("WEBZINE");
    }

    @Test
    @DisplayName("알 수 없는 저장값: 관리 목록·상세는 200이고 LIST로 보인다")
    void unknownStoredValueReadsAsList() throws Exception {
        long id = insertBoardWithRawType("FUTURE");

        MvcResult detail = mockMvc.perform(get("/admin/api/boards/{id}", id).with(asAdmin())).andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(detail).get("boardType").asText()).isEqualTo("LIST");

        MvcResult list = mockMvc.perform(get("/admin/api/boards").with(asAdmin())).andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        boolean found = false;
        for (JsonNode node : body(list)) {
            if (node.get("id").asLong() == id) {
                assertThat(node.get("boardType").asText()).isEqualTo("LIST");
                found = true;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("알 수 없는 저장값은 유형을 언급하지 않은 수정(이름·다른 표시 설정)에서 원본 그대로 보존되고, 명시했을 때만 바뀐다 (리뷰 R1-1)")
    void unknownStoredValueSurvivesUnrelatedEdits() throws Exception {
        long id = insertBoardWithRawType("FUTURE");

        assertThat(patchBoard(id, "\"name\":\"bt-after-rename\"").getResponse().getStatus()).isEqualTo(200);
        assertThat(typeInDb(id)).as("이름만 수정").isEqualTo("FUTURE");

        assertThat(patchBoard(id, "\"listPageSize\":20,\"showViewCount\":true,\"pinMaxCount\":2,\"description\":\"설명\"")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(typeInDb(id)).as("다른 표시 설정만 수정").isEqualTo("FUTURE");

        assertThat(patchBoard(id, "\"boardType\":null,\"attachMaxCount\":3").getResponse().getStatus()).isEqualTo(200);
        assertThat(typeInDb(id)).as("명시 null도 유지").isEqualTo("FUTURE");

        MvcResult explicit = patchBoard(id, "\"boardType\":\"GALLERY\"");
        assertThat(explicit.getResponse().getStatus()).isEqualTo(200);
        assertThat(typeInDb(id)).as("명시하면 그때만 바뀐다").isEqualTo("GALLERY");
    }
}
