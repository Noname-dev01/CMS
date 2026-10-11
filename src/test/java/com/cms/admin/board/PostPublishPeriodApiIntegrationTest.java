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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 게시글 노출 기간(예약 게시)의 관리 API 계약 시험(PLAN-post-publish-period.md 쟁점 1·6·7). 실제 MVC 바인딩(Jackson 3.1)·서비스·MariaDB를 거친다 —
 * Java DTO를 직접 만드는 시험으로는 Jackson의 관대한 역직렬화(오프셋 절단·빈 문자열→null·날짜 보정·중복 키 덮어쓰기)를 잡지 못한다.
 * 이 입력들은 전부 기존 기간을 지워 글을 즉시 공개시킬 수 있어 "400이고 DB가 그대로"를 단언한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PostPublishPeriodApiIntegrationTest extends MariaDbContainerSupport {

    private static final LocalDateTime START = LocalDateTime.of(2090, 1, 1, 9, 0);
    private static final LocalDateTime END = LocalDateTime.of(2090, 2, 1, 0, 0);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired PermissionCache cache;
    @Autowired ObjectMapper objectMapper;

    private Member admin;
    private final List<Long> boardIds = new ArrayList<>();
    private long auditBaseline;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "pp-admin", Role.ROLE_ADMIN);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
    }

    @AfterEach
    void cleanUp() {
        for (Long boardId : boardIds) {
            jdbc.update("DELETE FROM content_image_ref WHERE owner_type = 'POST' AND owner_id IN (SELECT id FROM post WHERE board_id = ?)", boardId);
            jdbc.update("DELETE FROM post WHERE board_id = ?", boardId);
            jdbc.update("DELETE FROM board WHERE id = ?", boardId);
        }
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE id > ?", auditBaseline);
        cache.invalidate();
    }

    private long createBoard() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("name", "pp-board-" + System.nanoTime(), "publicYn", true, "attachmentYn", false));
        MvcResult result = mockMvc.perform(post("/admin/api/boards").with(TestMembers.asMember(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        long id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        boardIds.add(id);
        return id;
    }

    private MvcResult create(long boardId, String rawJson) throws Exception {
        return mockMvc.perform(post("/admin/api/boards/{b}/posts", boardId).with(TestMembers.asMember(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(rawJson)).andReturn();
    }

    private MvcResult patchPost(long boardId, long postId, String rawJson) throws Exception {
        return mockMvc.perform(patch("/admin/api/boards/{b}/posts/{p}", boardId, postId).with(TestMembers.asMember(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(rawJson)).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String createJson(String title, String periodFragment) {
        return "{\"title\":\"" + title + "\",\"content\":\"<p>본문</p>\",\"contentFormat\":\"HTML\"" + (periodFragment == null ? "" : "," + periodFragment) + "}";
    }

    private long createWithPeriod(long boardId) throws Exception {
        MvcResult result = create(boardId, createJson("기간글", "\"publishPeriod\":{\"start\":\"2090-01-01T09:00\",\"end\":\"2090-02-01T00:00\"}"));
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return json(result).get("id").asLong();
    }

    private LocalDateTime dbStart(long postId) {
        Timestamp ts = jdbc.queryForObject("SELECT publish_start FROM post WHERE id = ?", Timestamp.class, postId);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private LocalDateTime dbEnd(long postId) {
        Timestamp ts = jdbc.queryForObject("SELECT publish_end FROM post WHERE id = ?", Timestamp.class, postId);
        return ts == null ? null : ts.toLocalDateTime();
    }

    // ── 생성 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("생성: publishPeriod를 주면 분 단위로 절단해 저장하고 응답에 기간·status(SCHEDULED)가 나온다")
    void create_withPeriod_truncatedAndScheduled() throws Exception {
        long board = createBoard();

        MvcResult result = create(board, createJson("예약글", "\"publishPeriod\":{\"start\":\"2090-01-01T09:00:59\",\"end\":\"2090-02-01T00:00:30\"}"));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(result);
        assertThat(body.get("publishStart").asText()).isEqualTo("2090-01-01T09:00:00");
        assertThat(body.get("publishEnd").asText()).isEqualTo("2090-02-01T00:00:00");
        assertThat(body.get("status").asText()).isEqualTo("SCHEDULED");
        long id = body.get("id").asLong();
        assertThat(dbStart(id)).isEqualTo(START);
        assertThat(dbEnd(id)).isEqualTo(END);
    }

    @Test
    @DisplayName("생성: 기간이 없으면 둘 다 NULL이고 status는 ACTIVE, useYn=false면 HIDDEN — 기존 동작 불변")
    void create_withoutPeriod_isUnrestricted() throws Exception {
        long board = createBoard();

        JsonNode active = json(create(board, createJson("기간없음", null)));
        JsonNode hidden = json(create(board, createJson("비노출", "\"useYn\":false")));
        JsonNode explicitNull = json(create(board, createJson("명시null", "\"publishPeriod\":null")));

        assertThat(active.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(active.has("publishStart")).as("null은 응답에서 생략(non_null)").isFalse();
        assertThat(dbStart(active.get("id").asLong())).isNull();
        assertThat(dbEnd(active.get("id").asLong())).isNull();
        assertThat(hidden.get("status").asText()).isEqualTo("HIDDEN");
        assertThat(explicitNull.get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("생성: 과거 종료 시각은 허용되고 EXPIRED, 시작만·종료만 있는 기간도 허용된다")
    void create_pastEndAndOpenEnded() throws Exception {
        long board = createBoard();

        JsonNode expired = json(create(board, createJson("만료", "\"publishPeriod\":{\"end\":\"2001-01-01T00:00\"}")));
        JsonNode startOnly = json(create(board, createJson("시작만", "\"publishPeriod\":{\"start\":\"2090-01-01T00:00\"}")));
        JsonNode started = json(create(board, createJson("이미시작", "\"publishPeriod\":{\"start\":\"2001-01-01T00:00\"}")));

        assertThat(expired.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(startOnly.get("status").asText()).isEqualTo("SCHEDULED");
        assertThat(started.get("status").asText()).isEqualTo("ACTIVE");
    }

    // ── 수정(PATCH) 의미: 없으면 유지, 있으면 통째 교체 ─────────────────────────

    @Test
    @DisplayName("수정: publishPeriod 키가 없으면 기간 유지 — 제목만 고쳐도 기간이 지워지지 않는다")
    void patch_withoutPeriodKey_keepsPeriod() throws Exception {
        long board = createBoard();
        long id = createWithPeriod(board);

        MvcResult result = patchPost(board, id, "{\"title\":\"제목만 수정\"}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(dbStart(id)).isEqualTo(START);
        assertThat(dbEnd(id)).isEqualTo(END);
        assertThat(json(result).get("status").asText()).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("수정: 기간만 바꾸는 요청도 유효하다 — 통째 교체(한쪽만 주면 나머지는 열린다)")
    void patch_periodOnly_replacesWhole() throws Exception {
        long board = createBoard();
        long id = createWithPeriod(board);

        MvcResult result = patchPost(board, id, "{\"publishPeriod\":{\"start\":\"2090-03-01T10:00\"}}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(dbStart(id)).isEqualTo(LocalDateTime.of(2090, 3, 1, 10, 0));
        assertThat(dbEnd(id)).as("종료는 병합되지 않고 열린다").isNull();
    }

    @Test
    @DisplayName("수정: publishPeriod {} 와 {start:null,end:null} 은 기간을 비운다 (제한 없음)")
    void patch_emptyObject_clearsPeriod() throws Exception {
        long board = createBoard();
        long first = createWithPeriod(board);
        long second = createWithPeriod(board);

        assertThat(patchPost(board, first, "{\"publishPeriod\":{}}").getResponse().getStatus()).isEqualTo(200);
        assertThat(patchPost(board, second, "{\"publishPeriod\":{\"start\":null,\"end\":null}}").getResponse().getStatus()).isEqualTo(200);

        assertThat(dbStart(first)).isNull();
        assertThat(dbEnd(first)).isNull();
        assertThat(dbStart(second)).isNull();
        assertThat(dbEnd(second)).isNull();
    }

    @Test
    @DisplayName("수정: 변경 필드가 publishPeriod:null 뿐이면 '변경 필드 없음' 400 — 명시 null은 유지이지 비움이 아니다")
    void patch_explicitNullOnly_isNoChange400() throws Exception {
        long board = createBoard();
        long id = createWithPeriod(board);

        MvcResult result = patchPost(board, id, "{\"publishPeriod\":null}");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(dbStart(id)).isEqualTo(START);
        assertThat(dbEnd(id)).isEqualTo(END);
    }

    @Test
    @DisplayName("상세·목록 응답에 기간과 status가 있고 useYn=false는 기간과 무관하게 HIDDEN이다")
    void detailAndListExposeStatus() throws Exception {
        long board = createBoard();
        long id = createWithPeriod(board);
        assertThat(patchPost(board, id, "{\"useYn\":false}").getResponse().getStatus()).isEqualTo(200);

        JsonNode detail = json(mockMvc.perform(get("/admin/api/boards/{b}/posts/{p}", board, id).with(TestMembers.asMember(admin))).andReturn());
        JsonNode list = json(mockMvc.perform(get("/admin/api/boards/{b}/posts", board).with(TestMembers.asMember(admin))).andReturn());

        assertThat(detail.get("status").asText()).isEqualTo("HIDDEN");
        assertThat(detail.get("publishStart").asText()).isEqualTo("2090-01-01T09:00:00");
        JsonNode row = list.get("content").get(0);
        assertThat(row.get("status").asText()).isEqualTo("HIDDEN");
        assertThat(row.get("publishEnd").asText()).isEqualTo("2090-02-01T00:00:00");
    }

    // ── 입력 계약: 모두 400이고 기존 기간·본문이 그대로여야 한다 ───────────────────

    /**
     * 각 값은 기존 기간을 지우거나(→ 즉시 공개) 다른 시각으로 보정될 수 있는 입력이다. {@code DisplayPeriod}의 범위·순서 검사까지 포함한다.
     * 중첩 오타·중복 키는 Jackson이 조용히 처리하는 입력이라 세터 기반 거부(PostPublishPeriodRequest)를 잡는다.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            // 오프셋 — Jackson은 끝의 Z를 떼어 KST 벽시계로 읽을 수 있다
            "{\"publishPeriod\":{\"start\":\"2090-01-01T01:00:00Z\"}}",
            "{\"publishPeriod\":{\"start\":\"2090-01-01T10:00:00+09:00\"}}",
            // 빈 문자열·공백 — Jackson은 빈 문자열을 null(= 열림)로 읽는다
            "{\"publishPeriod\":{\"start\":\"\"}}",
            "{\"publishPeriod\":{\"end\":\"   \"}}",
            // 존재하지 않는 날짜·24시 — 보정 금지
            "{\"publishPeriod\":{\"start\":\"2090-02-30T10:00\"}}",
            "{\"publishPeriod\":{\"start\":\"2091-02-29T10:00\"}}",
            "{\"publishPeriod\":{\"start\":\"2090-01-01T24:00\"}}",
            // 형식 불일치·소수 초
            "{\"publishPeriod\":{\"start\":\"2090-01-01\"}}",
            "{\"publishPeriod\":{\"start\":\"2090-01-01T10:00:00.123\"}}",
            "{\"publishPeriod\":{\"end\":\"abc\"}}",
            // 값 타입 오류
            "{\"publishPeriod\":{\"start\":[]}}",
            "{\"publishPeriod\":{\"start\":{}}}",
            "{\"publishPeriod\":[]}",
            "{\"publishPeriod\":\"2090-01-01T10:00\"}",
            // 중첩 오타(알 수 없는 키)·키 대소문자 오류 — 빈 객체(= 비움)로 읽히면 안 된다
            "{\"publishPeriod\":{\"publishStart\":\"2090-12-01T10:00\"}}",
            "{\"publishPeriod\":{\"startDate\":\"2090-12-01T10:00\"}}",
            "{\"publishPeriod\":{\"Start\":\"2090-12-01T10:00\"}}",
            // 중복 키 — 뒤 값이 앞 값을 덮어써 나쁜 값이 null로 바뀐다 (안쪽·바깥쪽)
            "{\"publishPeriod\":{\"start\":\"2090-02-30T10:00\",\"start\":null}}",
            "{\"publishPeriod\":{\"end\":\"2090-02-30T10:00\",\"end\":null}}",
            "{\"publishPeriod\":{\"start\":\"2090-02-30T10:00\"},\"publishPeriod\":{}}",
            "{\"publishPeriod\":{\"start\":\"2090-12-01T10:00\"},\"publishPeriod\":null}",
            // 내부 중복 검사 플래그를 JSON으로 되돌려 검사를 우회하는 시도 — 플래그는 JSON 속성이 아니다(코드 리뷰 R1)
            "{\"publishPeriod\":{\"start\":\"2090-02-30T10:00\",\"startSet\":false,\"start\":null}}",
            "{\"publishPeriod\":{\"end\":\"2090-02-30T10:00\",\"endSet\":false,\"end\":null}}",
            "{\"publishPeriod\":{\"start\":\"2090-02-30T10:00\"},\"publishPeriodSet\":false,\"publishPeriod\":{}}",
            // 범위 밖 연도 (DB 저장 범위 밖 +10000년 포함)
            "{\"publishPeriod\":{\"start\":\"1999-12-31T23:59\"}}",
            "{\"publishPeriod\":{\"end\":\"2100-01-01T00:00\"}}",
            "{\"publishPeriod\":{\"start\":\"+10000-01-01T00:00\"}}",
            // 순서 — 같거나 역전, 초 절단 뒤 같아짐
            "{\"publishPeriod\":{\"start\":\"2090-03-01T10:00\",\"end\":\"2090-03-01T10:00\"}}",
            "{\"publishPeriod\":{\"start\":\"2090-03-02T10:00\",\"end\":\"2090-03-01T10:00\"}}",
            "{\"publishPeriod\":{\"start\":\"2090-03-01T10:00:10\",\"end\":\"2090-03-01T10:00:50\"}}"
    })
    @DisplayName("수정 입력 계약: 위반은 400이고 기존 기간·제목이 그대로다 (잘못된 입력이 기간을 지우지 않는다)")
    void patch_invalidInput_is400AndDataUnchanged(String rawJson) throws Exception {
        long board = createBoard();
        long id = createWithPeriod(board);

        // 본문 필드를 함께 보내 "기간만 거부되고 나머지는 적용"되는 부분 적용도 막는다
        String withTitle = rawJson.replaceFirst("^\\{", "{\"title\":\"바뀌면 안 됨\",");
        MvcResult result = patchPost(board, id, withTitle);

        assertThat(result.getResponse().getStatus()).as(rawJson + " → " + result.getResponse().getContentAsString()).isEqualTo(400);
        assertThat(dbStart(id)).as("시작 불변").isEqualTo(START);
        assertThat(dbEnd(id)).as("종료 불변").isEqualTo(END);
        assertThat(jdbc.queryForObject("SELECT title FROM post WHERE id = ?", String.class, id)).isEqualTo("기간글");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "\"publishPeriod\":{\"start\":\"2090-01-01T01:00:00Z\"}",
            "\"publishPeriod\":{\"start\":\"\"}",
            "\"publishPeriod\":{\"start\":\"2090-02-30T10:00\"}",
            "\"publishPeriod\":{\"publishStart\":\"2090-12-01T10:00\"}",
            "\"publishPeriod\":{\"start\":\"2090-02-30T10:00\",\"start\":null}",
            "\"publishPeriod\":{\"start\":\"2090-12-01T10:00\"},\"publishPeriod\":{}",
            "\"publishPeriod\":{\"start\":\"2090-02-30T10:00\",\"startSet\":false,\"start\":null}",
            "\"publishPeriod\":{\"start\":\"2090-02-30T10:00\"},\"publishPeriodSet\":false,\"publishPeriod\":{}",
            "\"publishPeriod\":{\"start\":\"+10000-01-01T00:00\"}",
            "\"publishPeriod\":{\"start\":\"2090-03-01T10:00\",\"end\":\"2090-03-01T10:00\"}"
    })
    @DisplayName("생성 입력 계약: 위반은 400이고 게시글이 만들어지지 않는다")
    void create_invalidInput_is400AndNoRow(String periodFragment) throws Exception {
        long board = createBoard();

        MvcResult result = create(board, createJson("만들어지면 안 됨", periodFragment));

        assertThat(result.getResponse().getStatus()).as(periodFragment + " → " + result.getResponse().getContentAsString()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post WHERE board_id = ?", Long.class, board)).isZero();
    }

    @Test
    @DisplayName("범위 경계: 2000-01-01T00:00과 2099-12-31T23:59는 저장·왕복되고 2100-01-01T00:00은 400이다 (실제 MariaDB)")
    void rangeBoundaries_roundTrip() throws Exception {
        long board = createBoard();

        MvcResult low = create(board, createJson("하한", "\"publishPeriod\":{\"start\":\"2000-01-01T00:00\"}"));
        MvcResult high = create(board, createJson("상한", "\"publishPeriod\":{\"end\":\"2099-12-31T23:59\"}"));
        MvcResult over = create(board, createJson("초과", "\"publishPeriod\":{\"end\":\"2100-01-01T00:00\"}"));

        assertThat(low.getResponse().getStatus()).isEqualTo(201);
        assertThat(high.getResponse().getStatus()).isEqualTo(201);
        assertThat(over.getResponse().getStatus()).isEqualTo(400);
        assertThat(dbStart(json(low).get("id").asLong())).isEqualTo(LocalDateTime.of(2000, 1, 1, 0, 0));
        assertThat(dbEnd(json(high).get("id").asLong())).isEqualTo(LocalDateTime.of(2099, 12, 31, 23, 59));
    }
}
