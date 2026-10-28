package com.cms.admin.board;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 게시판 슬러그 관리 API 실제 스택 시험(PLAN-board-definition.md "PR 4a 상세 설계" D7) — 생성 지정·생략, 1회 변경, 형식 400, 중복·영구 예약 409,
 * 동시 지정. MockMvc 호출이 실제로 커밋하므로 {@link #cleanUp()}에서 만든 행을 지운다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class BoardSlugApiIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired ObjectMapper objectMapper;

    private Member admin;
    private final List<Long> boardIds = new ArrayList<>();
    private long auditBaseline;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "slug-admin", Role.ROLE_ADMIN);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
    }

    @AfterEach
    void cleanUp() {
        for (Long boardId : boardIds) {
            jdbc.update("DELETE FROM board WHERE id = ?", boardId);
        }
        jdbc.update("UPDATE board SET slug = NULL WHERE board_key = 'NOTICE'");
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE id > ?", auditBaseline);
    }

    private RequestPostProcessor asAdmin() {
        return TestMembers.asMember(admin);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String uniqueSlug() {
        return "sl-" + System.nanoTime();
    }

    private MvcResult create(String slugMember) throws Exception {
        String json = "{\"name\":\"slug-board\",\"publicYn\":true,\"attachmentYn\":true" + (slugMember == null ? "" : "," + slugMember) + "}";
        MvcResult result = mockMvc.perform(post("/admin/api/boards").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
        if (result.getResponse().getStatus() == 201) {
            boardIds.add(body(result).get("id").asLong());
        }
        return result;
    }

    private long createOk(String slugMember) throws Exception {
        MvcResult result = create(slugMember);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asLong();
    }

    private int versionOf(long boardId) {
        return jdbc.queryForObject("SELECT version FROM board WHERE id = ?", Integer.class, boardId);
    }

    private String storedSlug(long boardId) {
        return jdbc.queryForObject("SELECT slug FROM board WHERE id = ?", String.class, boardId);
    }

    private MvcResult patchSlug(long boardId, String slugJson) throws Exception {
        return mockMvc.perform(patch("/admin/api/boards/{id}", boardId).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":" + slugJson + ",\"version\":" + versionOf(boardId) + "}")).andReturn();
    }

    @Test
    @DisplayName("생성에서 슬러그를 생략하면 기본 슬러그 board-{id}(저장 NULL, 변경 가능), 지정하면 그 값(변경 불가)")
    void create_defaultAndAssigned() throws Exception {
        long plain = createOk(null);
        assertThat(storedSlug(plain)).isNull();
        JsonNode plainBody = body(mockMvc.perform(get("/admin/api/boards/{id}", plain).with(asAdmin())).andReturn());
        assertThat(plainBody.get("slug").asText()).isEqualTo("board-" + plain);
        assertThat(plainBody.get("slugChangeable").asBoolean()).isTrue();

        long blank = createOk("\"slug\":\"   \"");
        assertThat(storedSlug(blank)).as("공백만 = 기본 슬러그").isNull();

        String slug = uniqueSlug();
        MvcResult assigned = create("\"slug\":\"" + slug + "\"");
        assertThat(assigned.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(assigned).get("slug").asText()).isEqualTo(slug);
        assertThat(body(assigned).get("slugChangeable").asBoolean()).isFalse();
        assertThat(storedSlug(body(assigned).get("id").asLong())).isEqualTo(slug);
    }

    @Test
    @DisplayName("기본 슬러그 게시판은 한 번만 바꿀 수 있다 — 변경 200·version +1, 같은 값 200(무변화), 다른 값 400, 지금 기본값 그대로 200")
    void patch_onceOnly() throws Exception {
        long board = createOk(null);
        int before = versionOf(board);

        MvcResult sameDefault = patchSlug(board, "\"board-" + board + "\"");
        assertThat(sameDefault.getResponse().getStatus()).as(sameDefault.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(storedSlug(board)).as("지금 값(기본 슬러그) 그대로 = 무변화").isNull();

        String slug = uniqueSlug();
        MvcResult changed = patchSlug(board, "\"" + slug + "\"");
        assertThat(changed.getResponse().getStatus()).as(changed.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(changed).get("slug").asText()).isEqualTo(slug);
        assertThat(body(changed).get("slugChangeable").asBoolean()).isFalse();
        assertThat(storedSlug(board)).isEqualTo(slug);
        assertThat(versionOf(board)).isEqualTo(before + 2);

        assertThat(patchSlug(board, "\"" + slug + "\"").getResponse().getStatus()).as("같은 값은 무변화").isEqualTo(200);
        MvcResult second = patchSlug(board, "\"" + uniqueSlug() + "\"");
        assertThat(second.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(second).get("message").asText()).contains("한 번만");
        MvcResult backToDefault = patchSlug(board, "\"board-" + board + "\"");
        assertThat(backToDefault.getResponse().getStatus()).as("기본 슬러그로 되돌릴 수 없다").isEqualTo(400);
        assertThat(storedSlug(board)).isEqualTo(slug);

        Integer audits = jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log WHERE id > ? AND action_type = 'BOARD_UPDATE' AND action_result = 'SUCCESS' AND target_id = ?",
                Integer.class, auditBaseline, board);
        assertThat(audits).isGreaterThanOrEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"12\"", "\"board-7\"", "\"Press\"", "\"a\"", "\"a--b\"", "\"-ab\"", "\"한글\"", "\"\"", "\"a b\""})
    @DisplayName("형식 위반(숫자만·시스템 이름 공간·대문자·1자·하이픈 위치·비ASCII·빈 값)은 생성·수정 모두 400이고 DB는 그대로")
    void invalidFormat(String slugJson) throws Exception {
        if (!"\"\"".equals(slugJson)) {
            assertThat(create("\"slug\":" + slugJson).getResponse().getStatus()).isEqualTo(400);
        }
        long board = createOk(null);
        int before = versionOf(board);
        assertThat(patchSlug(board, slugJson).getResponse().getStatus()).isEqualTo(400);
        assertThat(storedSlug(board)).isNull();
        assertThat(versionOf(board)).isEqualTo(before);
    }

    @Test
    @DisplayName("다른 게시판의 슬러그는 생성·수정 모두 409 DUPLICATE_RESOURCE")
    void duplicate() throws Exception {
        String slug = uniqueSlug();
        createOk("\"slug\":\"" + slug + "\"");

        MvcResult created = create("\"slug\":\"" + slug + "\"");
        assertThat(created.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(created).get("code").asText()).isEqualTo("DUPLICATE_RESOURCE");

        long other = createOk(null);
        MvcResult patched = patchSlug(other, "\"" + slug + "\"");
        assertThat(patched.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(patched).get("code").asText()).isEqualTo("DUPLICATE_RESOURCE");
        assertThat(storedSlug(other)).isNull();
    }

    @Test
    @DisplayName("삭제된 게시판의 슬러그는 영구 예약 — 소프트 삭제가 비우지 않고 새 게시판의 같은 슬러그는 409")
    void deletedSlugStaysReserved() throws Exception {
        String slug = uniqueSlug();
        long board = createOk("\"slug\":\"" + slug + "\"");
        MvcResult deleted = mockMvc.perform(delete("/admin/api/boards/{id}", board).param("version", String.valueOf(versionOf(board)))
                .with(asAdmin()).with(csrf())).andReturn();
        assertThat(deleted.getResponse().getStatus()).as(deleted.getResponse().getContentAsString()).isIn(200, 204);
        assertThat(storedSlug(board)).isEqualTo(slug);

        assertThat(create("\"slug\":\"" + slug + "\"").getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("공통 공지 게시판(시스템 게시판)도 슬러그를 바꿀 수 있다")
    void noticeBoardSlug() throws Exception {
        long notice = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
        String slug = uniqueSlug();
        assertThat(patchSlug(notice, "\"" + slug + "\"").getResponse().getStatus()).isEqualTo(200);
        assertThat(storedSlug(notice)).isEqualTo(slug);
    }

    @Test
    @DisplayName("두 게시판이 같은 슬러그를 동시에 지정하면 정확히 하나만 200, 나머지는 409(UNIQUE 위반도 409로 번역)")
    void concurrentAssignment() throws Exception {
        long first = createOk(null);
        long second = createOk(null);
        String slug = uniqueSlug();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (long board : List.of(first, second)) {
                int version = versionOf(board);
                results.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(patch("/admin/api/boards/{id}", board).with(asAdmin()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"" + slug + "\",\"version\":" + version + "}"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        Integer holders = jdbc.queryForObject("SELECT COUNT(*) FROM board WHERE slug = ?", Integer.class, slug);
        assertThat(holders).isEqualTo(1);
    }
}
