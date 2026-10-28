package com.cms.admin.site;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 사이트별 게시판·공지 관리 API 계약(PLAN-site-menu.md PR ④ T3~T5): 생성의 표시 대상(누락 = 공통)·사이트 공지 게시판 규칙, 수정 PATCH의 {@code siteScope}
 * (명시한 경우만 변경, 공지 게시판은 불가), 삭제(공통 공지만 409, 전용 공지는 글이 없으면 가능하고 소속·키를 비운다), 사이트 삭제 가드. 요청 JSON은 실제
 * MockMvc 바인딩으로 보낸다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class BoardSiteScopeApiIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired ObjectMapper objectMapper;

    private Member admin;
    private long alpha;
    private long beta;
    private long auditBaseline;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "board-scope-admin", Role.ROLE_ADMIN);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
        cleanBoards();
        alpha = site("bscope-alpha");
        beta = site("bscope-beta");
    }

    @AfterEach
    void cleanUp() {
        cleanBoards();
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE id > ?", auditBaseline);
    }

    private void cleanBoards() {
        jdbc.update("DELETE FROM post WHERE board_id IN (SELECT id FROM board WHERE name LIKE 'bs-%')");
        jdbc.update("DELETE FROM member_board_permission WHERE board_id IN (SELECT id FROM board WHERE name LIKE 'bs-%')");
        jdbc.update("DELETE FROM board WHERE name LIKE 'bs-%'");
        jdbc.update("DELETE FROM site WHERE id > 2");
    }

    private long site(String key) {
        jdbc.update("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, default_yn) VALUES (?, ?, 'PUBLIC', 0, 1, 0)", key, key);
        return jdbc.queryForObject("SELECT id FROM site WHERE site_key = ?", Long.class, key);
    }

    private RequestPostProcessor asAdmin() {
        return TestMembers.asMember(admin);
    }

    private MvcResult send(MockHttpServletRequestBuilder builder, String json) throws Exception {
        return mockMvc.perform(builder.with(asAdmin()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    /** 게시판 PATCH — 낡은 화면 검사(version)를 통과하도록 DB의 현재 버전을 본문에 붙인다. {@code json}은 객체 리터럴이어야 한다. */
    private MvcResult patchBoard(long id, String json) throws Exception {
        long version = jdbc.queryForObject("SELECT version FROM board WHERE id = ?", Long.class, id);
        String body = json.substring(0, json.lastIndexOf('}')) + (json.trim().equals("{}") ? "" : ",") + "\"version\":" + version + "}";
        return send(patch("/admin/api/boards/{id}", id), body);
    }

    /** 게시판 DELETE — DB의 현재 버전을 쿼리로 붙인다. */
    private MvcResult deleteBoard(long id) throws Exception {
        long version = jdbc.queryForObject("SELECT version FROM board WHERE id = ?", Long.class, id);
        return sendNoBody(delete("/admin/api/boards/{id}?version=" + version, id));
    }

    private MvcResult sendNoBody(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.with(asAdmin()).with(csrf())).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String createBody(String name, boolean publicYn, String extra) {
        return "{\"name\":\"" + name + "\",\"publicYn\":" + publicYn + ",\"attachmentYn\":true" + (extra == null ? "" : "," + extra) + "}";
    }

    private long create(String name, String extra) throws Exception {
        MvcResult result = send(post("/admin/api/boards"), createBody(name, true, extra));
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asLong();
    }

    private Long siteIdOf(long boardId) {
        return jdbc.queryForObject("SELECT site_id FROM board WHERE id = ?", Long.class, boardId);
    }

    private String keyOf(long boardId) {
        return jdbc.queryForObject("SELECT board_key FROM board WHERE id = ?", String.class, boardId);
    }

    // ===== 생성 =====

    @Test
    @DisplayName("생성: siteScope 누락 = 공통, COMMON = 공통, 사이트 ID = 전용. 응답에 siteId·noticeBoard가 실린다")
    void createScope() throws Exception {
        long missing = create("bs-누락", null);
        long common = create("bs-공통", "\"siteScope\":\"COMMON\"");
        MvcResult scoped = send(post("/admin/api/boards"), createBody("bs-전용", true, "\"siteScope\":\"" + alpha + "\""));

        assertThat(siteIdOf(missing)).isNull();
        assertThat(siteIdOf(common)).isNull();
        assertThat(scoped.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(scoped).get("siteId").asLong()).isEqualTo(alpha);
        assertThat(body(scoped).get("noticeBoard").asBoolean()).isFalse();
        assertThat(siteIdOf(body(scoped).get("id").asLong())).isEqualTo(alpha);
    }

    @Test
    @DisplayName("생성: 형식 위반·범위 초과·없는 사이트·관리자 사이트(id=1)는 400이고 게시판이 만들어지지 않는다")
    void createInvalidScope() throws Exception {
        String[] invalid = {"\"siteScope\":\"\"", "\"siteScope\":\"abc\"", "\"siteScope\":\"0\"", "\"siteScope\":\"-1\"",
                "\"siteScope\":\"9223372036854775808\"", "\"siteScope\":12", "\"siteScope\":\"99999\"", "\"siteScope\":\"1\""};
        for (String scopeField : invalid) {
            assertThat(send(post("/admin/api/boards"), createBody("bs-잘못", true, scopeField)).getResponse().getStatus())
                    .as(scopeField).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM board WHERE name = 'bs-잘못'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("사이트 공지 게시판: 키가 NOTICE:{siteId}로 만들어지고 시스템 게시판이 된다. 공통·비공개는 400, 같은 사이트의 두 번째는 409, 다른 사이트는 가능")
    void createNoticeBoard() throws Exception {
        MvcResult created = send(post("/admin/api/boards"), createBody("bs-알파공지", true, "\"siteScope\":\"" + alpha + "\",\"noticeBoard\":true"));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        long id = body(created).get("id").asLong();
        assertThat(keyOf(id)).isEqualTo("NOTICE:" + alpha);
        assertThat(siteIdOf(id)).isEqualTo(alpha);
        assertThat(body(created).get("noticeBoard").asBoolean()).isTrue();

        assertThat(send(post("/admin/api/boards"), createBody("bs-공통공지", true, "\"noticeBoard\":true")).getResponse().getStatus())
                .as("공통 공지 게시판 추가").isEqualTo(400);
        assertThat(send(post("/admin/api/boards"), createBody("bs-비공개공지", false, "\"siteScope\":\"" + beta + "\",\"noticeBoard\":true")).getResponse().getStatus())
                .as("비공개 공지 게시판").isEqualTo(400);
        assertThat(send(post("/admin/api/boards"), createBody("bs-알파공지2", true, "\"siteScope\":\"" + alpha + "\",\"noticeBoard\":true")).getResponse().getStatus())
                .as("같은 사이트 중복").isEqualTo(409);
        assertThat(send(post("/admin/api/boards"), createBody("bs-베타공지", true, "\"siteScope\":\"" + beta + "\",\"noticeBoard\":true")).getResponse().getStatus())
                .as("다른 사이트").isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM board WHERE board_key = 'NOTICE'", Integer.class)).as("공통 공지는 하나뿐").isEqualTo(1);
    }

    // ===== 수정 =====

    @Test
    @DisplayName("수정: siteScope를 명시하면 바뀌고(공통↔전용↔다른 사이트), 누락하면 대상이 유지된다. siteScope만 보내도 유효하다")
    void updateScope() throws Exception {
        long id = create("bs-수정", "\"siteScope\":\"" + alpha + "\"");

        assertThat(patchBoard(id, "{\"name\":\"bs-수정2\"}").getResponse().getStatus()).isEqualTo(200);
        assertThat(siteIdOf(id)).as("누락 = 유지").isEqualTo(alpha);

        assertThat(patchBoard(id, "{\"siteScope\":\"" + beta + "\"}").getResponse().getStatus()).isEqualTo(200);
        assertThat(siteIdOf(id)).isEqualTo(beta);

        MvcResult common = patchBoard(id, "{\"siteScope\":\"COMMON\"}");
        assertThat(common.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(common).path("siteId").isNull() || body(common).path("siteId").isMissingNode()).isTrue();
        assertThat(siteIdOf(id)).isNull();
    }

    @Test
    @DisplayName("수정: 잘못된 siteScope(형식·범위·없는 사이트·관리자 사이트)는 400이고 대상이 그대로다")
    void updateInvalidScope() throws Exception {
        long id = create("bs-잘못수정", "\"siteScope\":\"" + alpha + "\"");
        String[] invalid = {"\"\"", "\"abc\"", "\"0\"", "\"9223372036854775808\"", "\"99999\"", "\"1\""};
        for (String value : invalid) {
            assertThat(patchBoard(id, "{\"siteScope\":" + value + "}").getResponse().getStatus())
                    .as(value).isEqualTo(400);
        }
        assertThat(siteIdOf(id)).isEqualTo(alpha);
    }

    @Test
    @DisplayName("수정: 공지 게시판(공통·전용)의 표시 대상 변경은 400, 같은 값을 보내는 것과 이름 변경은 가능, 비공개 전환은 계속 400")
    void noticeBoardScopeLocked() throws Exception {
        long commonNotice = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
        long siteNotice = create("bs-알파공지", "\"siteScope\":\"" + alpha + "\",\"noticeBoard\":true");

        assertThat(patchBoard(commonNotice, "{\"siteScope\":\"" + alpha + "\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(patchBoard(siteNotice, "{\"siteScope\":\"" + beta + "\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(patchBoard(siteNotice, "{\"siteScope\":\"COMMON\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(siteIdOf(siteNotice)).isEqualTo(alpha);
        assertThat(keyOf(siteNotice)).isEqualTo("NOTICE:" + alpha);

        assertThat(patchBoard(siteNotice, "{\"siteScope\":\"" + alpha + "\",\"name\":\"bs-알파공지-개명\"}").getResponse().getStatus())
                .as("같은 값").isEqualTo(200);
        assertThat(patchBoard(siteNotice, "{\"publicYn\":false}").getResponse().getStatus()).isEqualTo(400);
        assertThat(patchBoard(commonNotice, "{\"siteScope\":\"COMMON\"}").getResponse().getStatus())
                .as("공통 공지에 COMMON(변화 없음)").isEqualTo(200);
    }

    // ===== 삭제 =====

    @Test
    @DisplayName("삭제: 공통 공지 게시판은 409. 전용 공지 게시판은 글이 있으면 409, 없으면 삭제되며 소속·키가 비워지고 같은 사이트에 다시 만들 수 있다")
    void deleteNoticeBoards() throws Exception {
        long commonNotice = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
        assertThat(deleteBoard(commonNotice).getResponse().getStatus()).isEqualTo(409);

        long siteNotice = create("bs-알파공지", "\"siteScope\":\"" + alpha + "\",\"noticeBoard\":true");
        jdbc.update("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id) VALUES (?, 'bs-글', '<p>x</p>', 1, 0, 'admin')", siteNotice);
        assertThat(deleteBoard(siteNotice).getResponse().getStatus()).as("글이 남음").isEqualTo(409);

        jdbc.update("DELETE FROM post WHERE board_id = ?", siteNotice);
        assertThat(deleteBoard(siteNotice).getResponse().getStatus()).isIn(200, 204);
        assertThat(siteIdOf(siteNotice)).as("소속 해제").isNull();
        assertThat(keyOf(siteNotice)).as("키 해제").isNull();
        assertThat(jdbc.queryForObject("SELECT deleted FROM board WHERE id = ?", Boolean.class, siteNotice)).isTrue();

        assertThat(send(post("/admin/api/boards"), createBody("bs-알파공지-재생성", true, "\"siteScope\":\"" + alpha + "\",\"noticeBoard\":true"))
                .getResponse().getStatus()).as("삭제 뒤 재생성").isEqualTo(201);
    }

    // ===== 사이트 삭제 가드 =====

    @Test
    @DisplayName("사이트 삭제: 전용 게시판이 있으면 409(공지 게시판 포함), 게시판을 지운 뒤에는 삭제된다. 공통 게시판은 사이트 삭제를 막지 않는다")
    void siteDeleteGuard() throws Exception {
        long common = create("bs-공통", null);
        long scoped = create("bs-전용", "\"siteScope\":\"" + alpha + "\"");
        long notice = create("bs-알파공지", "\"siteScope\":\"" + alpha + "\",\"noticeBoard\":true");

        assertThat(sendNoBody(delete("/admin/api/sites/{id}", alpha)).getResponse().getStatus()).isEqualTo(409);
        assertThat(deleteBoard(scoped).getResponse().getStatus()).isIn(200, 204);
        assertThat(sendNoBody(delete("/admin/api/sites/{id}", alpha)).getResponse().getStatus()).as("공지 게시판이 남음").isEqualTo(409);
        assertThat(deleteBoard(notice).getResponse().getStatus()).isIn(200, 204);

        assertThat(sendNoBody(delete("/admin/api/sites/{id}", alpha)).getResponse().getStatus()).isIn(200, 204);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site WHERE id = ?", Integer.class, alpha)).isZero();
        assertThat(jdbc.queryForObject("SELECT deleted FROM board WHERE id = ?", Boolean.class, common)).isFalse();
    }

    @Test
    @DisplayName("삭제된 게시판 행이 소속을 유지하고 있으면(이전 앱이 지운 경우) 사이트 삭제는 FK 500이 아니라 409다")
    void siteDeleteGuardCountsDeletedRows() throws Exception {
        long scoped = create("bs-이전앱삭제", "\"siteScope\":\"" + beta + "\"");
        jdbc.update("UPDATE board SET deleted = 1 WHERE id = ?", scoped);   // 이전 앱의 소프트 삭제 — 소속을 비우지 않는다

        assertThat(sendNoBody(delete("/admin/api/sites/{id}", beta)).getResponse().getStatus()).isEqualTo(409);
    }
}
