package com.cms.admin.faq;

import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.faq.service.FaqService;
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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * FAQ 관리 API의 실제 스택 시험(PLAN-faq.md): SecurityConfig·판정기·감사·MariaDB. MockMvc 호출이 실제로 커밋하므로 {@link #cleanUp()}이 만든 행을
 * 지우고 가드 행의 버전을 되돌린다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class FaqApiIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired ObjectMapper objectMapper;

    private Member admin;
    private long auditBaseline;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "fa-admin", Role.ROLE_ADMIN);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
        cleanFaq();
    }

    @AfterEach
    void cleanUp() {
        cleanFaq();
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE id > ?", auditBaseline);
    }

    private void cleanFaq() {
        jdbc.update("DELETE FROM faq");
        jdbc.update("DELETE FROM faq_category");
        jdbc.update("UPDATE faq_lock SET version = 0");
    }

    private RequestPostProcessor asAdmin() {
        return TestMembers.asMember(admin);
    }

    private MvcResult run(RequestBuilder builder) throws Exception {
        return mockMvc.perform(builder).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return run(post(url).with(asAdmin()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MvcResult putJson(String url, String body) throws Exception {
        return run(put(url).with(asAdmin()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long createCategory(String name, boolean useYn) throws Exception {
        MvcResult result = postJson("/admin/api/faq-categories", "{\"name\":\"" + name + "\",\"useYn\":" + useYn + "}");
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return json(result).get("id").asLong();
    }

    private static String faqBody(long categoryId, String question, String answer, boolean useYn) {
        return "{\"categoryId\":" + categoryId + ",\"question\":\"" + question + "\",\"answer\":\"" + answer.replace("\"", "\\\"")
                + "\",\"contentFormat\":\"HTML\",\"useYn\":" + useYn + "}";
    }

    private long createFaq(long categoryId, String question) throws Exception {
        MvcResult result = postJson("/admin/api/faqs", faqBody(categoryId, question, "<p>답변</p>", true));
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return json(result).get("id").asLong();
    }

    private long version() {
        return jdbc.queryForObject("SELECT version FROM faq_lock WHERE id = 1", Long.class);
    }

    private List<Integer> faqOrds(long categoryId) {
        return jdbc.queryForList("SELECT ord FROM faq WHERE category_id = ? ORDER BY ord, id", Integer.class, categoryId);
    }

    private List<Integer> categoryOrds() {
        return jdbc.queryForList("SELECT ord FROM faq_category ORDER BY ord, id", Integer.class);
    }

    private JsonNode tree() throws Exception {
        MvcResult result = run(get("/admin/api/faqs").with(asAdmin()));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result);
    }

    private String orderBody(long version, String categoriesJson) {
        return "{\"version\":" + version + ",\"categories\":" + categoriesJson + "}";
    }

    // ===== 생명주기 =====

    @Test
    @DisplayName("카테고리·FAQ 등록→트리 조회→상세→수정→삭제 왕복: 생성 ID = Location ID = SUCCESS 감사 targetId, 트리에는 답변 본문이 없고 상세에만 있다")
    void lifecycle() throws Exception {
        MvcResult categoryCreated = postJson("/admin/api/faq-categories", "{\"name\":\"배송\",\"useYn\":true}");
        assertThat(categoryCreated.getResponse().getStatus()).isEqualTo(201);
        long categoryId = json(categoryCreated).get("id").asLong();
        assertThat(categoryCreated.getResponse().getHeader("Location")).endsWith("/admin/api/faq-categories/" + categoryId);
        assertThat(json(categoryCreated).get("ord").asInt()).isZero();

        String dirty = "<p>2~3일 <strong>소요</strong></p><script>alert(1)</script><img src=\"x\" onerror=\"alert(2)\">"
                + "<a href=\"javascript:alert(3)\">나쁜</a> <a href=\"https://example.com\">좋은</a>";
        MvcResult faqCreated = postJson("/admin/api/faqs", faqBody(categoryId, "배송은 얼마나 걸리나요?", dirty, true));
        assertThat(faqCreated.getResponse().getStatus()).isEqualTo(201);
        long faqId = json(faqCreated).get("id").asLong();
        assertThat(faqCreated.getResponse().getHeader("Location")).endsWith("/admin/api/faqs/" + faqId);

        String stored = jdbc.queryForObject("SELECT answer_html FROM faq WHERE id = ?", String.class, faqId);
        assertThat(stored).contains("<strong>소요</strong>").contains("href=\"https://example.com\"");
        assertThat(stored).doesNotContain("<script", "alert(1)", "onerror", "<img", "javascript:");

        JsonNode tree = tree();
        assertThat(tree.get("version").asLong()).as("카테고리 등록 + FAQ 등록").isEqualTo(2);
        JsonNode category = tree.get("categories").get(0);
        assertThat(category.get("name").asText()).isEqualTo("배송");
        JsonNode item = category.get("faqs").get(0);
        assertThat(item.get("question").asText()).isEqualTo("배송은 얼마나 걸리나요?");
        assertThat(item.has("answerHtml")).as("트리에는 답변 본문이 없다").isFalse();
        assertThat(json(run(get("/admin/api/faqs/{id}", faqId).with(asAdmin()))).get("answerHtml").asText()).isEqualTo(stored);

        // 수정은 구조가 아니므로 버전이 오르지 않는다
        long before = version();
        assertThat(putJson("/admin/api/faq-categories/" + categoryId, "{\"name\":\"배송/반품\",\"useYn\":false}").getResponse().getStatus()).isEqualTo(200);
        MvcResult updated = putJson("/admin/api/faqs/" + faqId, faqBody(categoryId, "새 질문", "<p>새 답변</p>", false));
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(updated).get("useYn").asBoolean()).isFalse();
        assertThat(version()).as("이름·질문·답변·노출 수정은 버전을 올리지 않는다").isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT name FROM faq_category WHERE id = ?", String.class, categoryId)).isEqualTo("배송/반품");

        assertThat(run(delete("/admin/api/faqs/{id}", faqId).with(asAdmin()).with(csrf())).getResponse().getStatus()).isEqualTo(204);
        assertThat(run(delete("/admin/api/faqs/{id}", faqId).with(asAdmin()).with(csrf())).getResponse().getStatus()).as("이미 삭제됨").isEqualTo(404);
        assertThat(run(delete("/admin/api/faq-categories/{id}", categoryId).with(asAdmin()).with(csrf())).getResponse().getStatus()).isEqualTo(204);

        // 감사: 생성 ID = Location의 ID = SUCCESS 행의 targetId
        assertThat(jdbc.queryForObject("SELECT target_id FROM admin_action_log WHERE id > ? AND action_type = 'FAQ_CATEGORY_CREATE' AND action_result = 'SUCCESS'",
                Long.class, auditBaseline)).isEqualTo(categoryId);
        assertThat(jdbc.queryForObject("SELECT target_id FROM admin_action_log WHERE id > ? AND action_type = 'FAQ_CREATE' AND action_result = 'SUCCESS'",
                Long.class, auditBaseline)).isEqualTo(faqId);
        assertThat(jdbc.queryForObject("SELECT target_id FROM admin_action_log WHERE id > ? AND action_type = 'FAQ_DELETE' AND action_result = 'SUCCESS'",
                Long.class, auditBaseline)).isEqualTo(faqId);
        assertThat(jdbc.queryForObject("SELECT target_id FROM admin_action_log WHERE id > ? AND action_type = 'FAQ_CATEGORY_DELETE' AND action_result = 'SUCCESS'",
                Long.class, auditBaseline)).isEqualTo(categoryId);
        List<String> actions = jdbc.queryForList("SELECT action_type FROM admin_action_log WHERE id > ? AND target_type IN ('FAQ', 'FAQ_CATEGORY') ORDER BY id",
                String.class, auditBaseline);
        assertThat(actions).contains(AdminActionTypes.FAQ_UPDATE, AdminActionTypes.FAQ_CATEGORY_UPDATE);
    }

    // ===== 검증 =====

    @Test
    @DisplayName("카테고리 검증: 이름 공백·51자·useYn 누락은 400이고 행이 생기지 않는다")
    void categoryValidation() throws Exception {
        assertThat(postJson("/admin/api/faq-categories", "{\"name\":\"  \",\"useYn\":true}").getResponse().getStatus()).isEqualTo(400);
        assertThat(postJson("/admin/api/faq-categories", "{\"name\":\"" + "가".repeat(51) + "\",\"useYn\":true}").getResponse().getStatus()).isEqualTo(400);
        assertThat(postJson("/admin/api/faq-categories", "{\"name\":\"이름\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM faq_category", Integer.class)).isZero();
        assertThat(version()).isZero();
    }

    @Test
    @DisplayName("FAQ 검증: 질문 공백·301자, 답변 공백·보이는 내용 없음(<p></p>), 형식 표식 누락, 없는 카테고리, 5,000자 초과는 400이고 행이 생기지 않는다")
    void faqValidation() throws Exception {
        long categoryId = createCategory("c", true);
        long before = version();

        assertThat(postJson("/admin/api/faqs", faqBody(categoryId, "  ", "<p>a</p>", true)).getResponse().getStatus()).as("질문 공백").isEqualTo(400);
        assertThat(postJson("/admin/api/faqs", faqBody(categoryId, "가".repeat(301), "<p>a</p>", true)).getResponse().getStatus()).as("질문 301자").isEqualTo(400);
        assertThat(postJson("/admin/api/faqs", faqBody(categoryId, "q", "", true)).getResponse().getStatus()).as("답변 공백").isEqualTo(400);
        assertThat(postJson("/admin/api/faqs", faqBody(categoryId, "q", "<p></p>", true)).getResponse().getStatus()).as("보이는 내용 없는 답변").isEqualTo(400);
        assertThat(postJson("/admin/api/faqs", faqBody(categoryId, "q", "<img src=\\\"x\\\">", true)).getResponse().getStatus()).as("이미지뿐인 답변(이미지 불허)").isEqualTo(400);
        assertThat(postJson("/admin/api/faqs", "{\"categoryId\":" + categoryId + ",\"question\":\"q\",\"answer\":\"<p>a</p>\",\"useYn\":true}")
                .getResponse().getStatus()).as("형식 표식 누락").isEqualTo(400);
        assertThat(postJson("/admin/api/faqs", faqBody(999_999L, "q", "<p>a</p>", true)).getResponse().getStatus()).as("없는 카테고리").isEqualTo(400);
        assertThat(postJson("/admin/api/faqs", faqBody(categoryId, "q", "<p>" + "가".repeat(5_001) + "</p>", true)).getResponse().getStatus())
                .as("보이는 글자 5,000자 초과").isEqualTo(400);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM faq", Integer.class)).isZero();
        assertThat(version()).as("실패한 등록은 버전을 올리지 않는다").isEqualTo(before);
    }

    @Test
    @DisplayName("실패 감사 행의 errorMessage는 고정 문구라 입력한 질문이 감사 테이블에 섞이지 않는다")
    void auditFailureMessageIsFixed() throws Exception {
        String marker = "SECRET_QUESTION_MARKER";
        assertThat(postJson("/admin/api/faqs", faqBody(999_999L, marker, "<p>a</p>", true)).getResponse().getStatus()).isEqualTo(400);

        List<String> messages = jdbc.queryForList("SELECT error_message FROM admin_action_log WHERE id > ? AND action_type = 'FAQ_CREATE' AND action_result = 'FAIL'",
                String.class, auditBaseline);
        assertThat(messages).isNotEmpty().allSatisfy(message -> assertThat(message).isEqualTo("FAQ 등록에 실패했습니다.").doesNotContain(marker));
    }

    // ===== 상한 =====

    @Test
    @DisplayName("카테고리는 30개, FAQ는 200개가 정확한 상한이다(초과 409) — 삭제하면 다시 등록할 수 있다")
    void limits() throws Exception {
        long first = createCategory("c0", true);
        for (int i = 1; i < FaqService.MAX_CATEGORIES; i++) {
            jdbc.update("INSERT INTO faq_category (name, ord, use_yn) VALUES (?, ?, 1)", "c" + i, i);
        }
        assertThat(postJson("/admin/api/faq-categories", "{\"name\":\"넘침\",\"useYn\":true}").getResponse().getStatus()).as("31번째 카테고리").isEqualTo(409);

        for (int i = 0; i < FaqService.MAX_FAQS; i++) {
            jdbc.update("INSERT INTO faq (category_id, question, answer_html, ord, use_yn) VALUES (?, ?, '<p>a</p>', ?, 1)", first, "q" + i, i);
        }
        assertThat(postJson("/admin/api/faqs", faqBody(first, "넘침", "<p>a</p>", true)).getResponse().getStatus()).as("201번째 FAQ").isEqualTo(409);

        long victim = jdbc.queryForObject("SELECT MAX(id) FROM faq", Long.class);
        assertThat(run(delete("/admin/api/faqs/{id}", victim).with(asAdmin()).with(csrf())).getResponse().getStatus()).isEqualTo(204);
        assertThat(postJson("/admin/api/faqs", faqBody(first, "다시", "<p>a</p>", true)).getResponse().getStatus()).isEqualTo(201);
    }

    // ===== ord 연속성 · 이동 · 삭제 =====

    @Test
    @DisplayName("등록은 맨 끝, 중간 FAQ를 삭제하면 같은 카테고리가 즉시 0..n-1로 재번호되고(버전 +1), 그대로 재반영해도 버전이 오르지 않는다")
    void deleteCompactsOrds_thenResaveIsNoOp() throws Exception {
        long c = createCategory("c", true);
        long a = createFaq(c, "a");
        long b = createFaq(c, "b");
        long d = createFaq(c, "d");
        assertThat(faqOrds(c)).containsExactly(0, 1, 2);

        long beforeDelete = version();
        assertThat(run(delete("/admin/api/faqs/{id}", b).with(asAdmin()).with(csrf())).getResponse().getStatus()).isEqualTo(204);
        assertThat(faqOrds(c)).as("가운데 삭제 뒤 연속").containsExactly(0, 1);
        assertThat(version()).isEqualTo(beforeDelete + 1);

        // 화면이 읽은 그대로 다시 반영 — 서버가 "고치는" 쓰기가 없어야 한다
        long beforeResave = version();
        MvcResult resave = putJson("/admin/api/faqs/order", orderBody(beforeResave, "[{\"id\":" + c + ",\"faqIds\":[" + a + "," + d + "]}]"));
        assertThat(resave.getResponse().getStatus()).isEqualTo(200);
        assertThat(version()).as("같은 순서 재저장은 버전을 올리지 않는다").isEqualTo(beforeResave);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log WHERE id > ? AND action_type = 'FAQ_ORDER' AND action_result = 'SUCCESS'",
                Integer.class, auditBaseline)).as("감사는 호출 1건").isEqualTo(1);
    }

    @Test
    @DisplayName("FAQ 카테고리 이동: 대상 카테고리의 맨 끝에 들어가고 원래 카테고리는 재번호되며 버전이 오른다 — 같은 카테고리 저장은 버전 불변")
    void moveToOtherCategory() throws Exception {
        long c1 = createCategory("c1", true);
        long c2 = createCategory("c2", true);
        long a = createFaq(c1, "a");
        long b = createFaq(c1, "b");
        long x = createFaq(c2, "x");

        long beforeSame = version();
        assertThat(putJson("/admin/api/faqs/" + a, faqBody(c1, "a2", "<p>a</p>", true)).getResponse().getStatus()).isEqualTo(200);
        assertThat(version()).as("카테고리가 같으면 구조 변경이 아니다").isEqualTo(beforeSame);

        assertThat(putJson("/admin/api/faqs/" + a, faqBody(c2, "a2", "<p>a</p>", true)).getResponse().getStatus()).isEqualTo(200);
        assertThat(version()).isEqualTo(beforeSame + 1);
        assertThat(jdbc.queryForObject("SELECT category_id FROM faq WHERE id = ?", Long.class, a)).isEqualTo(c2);
        assertThat(jdbc.queryForList("SELECT id FROM faq WHERE category_id = ? ORDER BY ord", Long.class, c2)).containsExactly(x, a);
        assertThat(faqOrds(c2)).containsExactly(0, 1);
        assertThat(faqOrds(c1)).as("원래 카테고리 재번호").containsExactly(0);
        assertThat(jdbc.queryForObject("SELECT ord FROM faq WHERE id = ?", Integer.class, b)).isZero();

        assertThat(putJson("/admin/api/faqs/" + a, faqBody(999_999L, "a2", "<p>a</p>", true)).getResponse().getStatus()).as("없는 카테고리로 이동").isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT category_id FROM faq WHERE id = ?", Long.class, a)).as("실패하면 이동하지 않는다").isEqualTo(c2);
    }

    @Test
    @DisplayName("카테고리 삭제: FAQ가 남아 있으면 409(행 유지), 빈 카테고리는 204이고 남은 카테고리가 재번호된다")
    void categoryDelete() throws Exception {
        long c1 = createCategory("c1", true);
        long c2 = createCategory("c2", true);
        long c3 = createCategory("c3", true);
        createFaq(c2, "q");

        long before = version();
        assertThat(run(delete("/admin/api/faq-categories/{id}", c2).with(asAdmin()).with(csrf())).getResponse().getStatus()).as("FAQ가 남음").isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM faq_category WHERE id = ?", Integer.class, c2)).isEqualTo(1);
        assertThat(version()).isEqualTo(before);

        assertThat(run(delete("/admin/api/faq-categories/{id}", c1).with(asAdmin()).with(csrf())).getResponse().getStatus()).isEqualTo(204);
        assertThat(categoryOrds()).as("남은 카테고리 재번호").containsExactly(0, 1);
        assertThat(jdbc.queryForObject("SELECT ord FROM faq_category WHERE id = ?", Integer.class, c3)).isEqualTo(1);
        assertThat(version()).isEqualTo(before + 1);
        assertThat(run(delete("/admin/api/faq-categories/{id}", 999_999L).with(asAdmin()).with(csrf())).getResponse().getStatus()).isEqualTo(404);
    }

    // ===== 순서 반영 =====

    @Test
    @DisplayName("순서 반영: 카테고리 순서와 FAQ 순서가 바뀌고 버전이 +1, 응답은 새 트리(새 버전 포함)이며 FAQ_ORDER 감사는 targetId가 없다")
    void saveOrder_changes() throws Exception {
        long c1 = createCategory("c1", true);
        long c2 = createCategory("c2", true);
        long a = createFaq(c1, "a");
        long b = createFaq(c1, "b");
        long before = version();

        MvcResult result = putJson("/admin/api/faqs/order",
                orderBody(before, "[{\"id\":" + c2 + ",\"faqIds\":[]},{\"id\":" + c1 + ",\"faqIds\":[" + b + "," + a + "]}]"));

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode body = json(result);
        assertThat(body.get("version").asLong()).isEqualTo(before + 1);
        assertThat(body.get("categories").get(0).get("id").asLong()).isEqualTo(c2);
        assertThat(body.get("categories").get(1).get("faqs").get(0).get("id").asLong()).isEqualTo(b);
        assertThat(categoryOrds()).containsExactly(0, 1);
        assertThat(jdbc.queryForObject("SELECT ord FROM faq_category WHERE id = ?", Integer.class, c2)).isZero();
        assertThat(jdbc.queryForList("SELECT id FROM faq WHERE category_id = ? ORDER BY ord", Long.class, c1)).containsExactly(b, a);
        assertThat(version()).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT target_id FROM admin_action_log WHERE id > ? AND action_type = 'FAQ_ORDER' AND action_result = 'SUCCESS'",
                Long.class, auditBaseline)).isNull();
    }

    @Test
    @DisplayName("낡은 version의 순서 반영은 409이고 아무것도 바뀌지 않는다 — 다른 관리자가 같은 집합의 순서만 바꾼 뒤에도 그렇다")
    void saveOrder_staleVersion409() throws Exception {
        long c = createCategory("c", true);
        long a = createFaq(c, "a");
        long b = createFaq(c, "b");
        long staleVersion = version();

        // 다른 관리자가 순서만 바꿨다 → 버전 +1
        assertThat(putJson("/admin/api/faqs/order", orderBody(staleVersion, "[{\"id\":" + c + ",\"faqIds\":[" + b + "," + a + "]}]"))
                .getResponse().getStatus()).isEqualTo(200);

        MvcResult stale = putJson("/admin/api/faqs/order", orderBody(staleVersion, "[{\"id\":" + c + ",\"faqIds\":[" + a + "," + b + "]}]"));

        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForList("SELECT id FROM faq WHERE category_id = ? ORDER BY ord", Long.class, c)).as("낡은 화면이 덮어쓰지 못한다").containsExactly(b, a);
    }

    @Test
    @DisplayName("순서 반영 400: 중복 카테고리·중복 FAQ·빠진 카테고리·빠진 FAQ·다른 카테고리의 FAQ·없는 ID·null — 아무것도 바뀌지 않는다")
    void saveOrder_badShape400() throws Exception {
        long c1 = createCategory("c1", true);
        long c2 = createCategory("c2", true);
        long a = createFaq(c1, "a");
        long b = createFaq(c1, "b");
        long x = createFaq(c2, "x");
        long v = version();

        String[] bad = {
                "[{\"id\":" + c1 + ",\"faqIds\":[" + a + "," + b + "]},{\"id\":" + c1 + ",\"faqIds\":[]},{\"id\":" + c2 + ",\"faqIds\":[" + x + "]}]",   // 중복 카테고리
                "[{\"id\":" + c1 + ",\"faqIds\":[" + a + "," + a + "]},{\"id\":" + c2 + ",\"faqIds\":[" + x + "]}]",                                      // 중복 FAQ
                "[{\"id\":" + c1 + ",\"faqIds\":[" + a + "," + b + "]}]",                                                                                    // 카테고리 빠짐
                "[{\"id\":" + c1 + ",\"faqIds\":[" + a + "]},{\"id\":" + c2 + ",\"faqIds\":[" + x + "]}]",                                                   // FAQ 빠짐
                "[{\"id\":" + c1 + ",\"faqIds\":[" + a + "," + b + "," + x + "]},{\"id\":" + c2 + ",\"faqIds\":[]}]",                                       // 다른 카테고리로 이동 시도
                "[{\"id\":" + c1 + ",\"faqIds\":[" + a + "," + b + "]},{\"id\":999999,\"faqIds\":[]}]",                                                      // 없는 카테고리
                "[{\"id\":" + c1 + ",\"faqIds\":[" + a + "," + b + ",999999]},{\"id\":" + c2 + ",\"faqIds\":[" + x + "]}]",                                  // 없는 FAQ
                "[{\"id\":null,\"faqIds\":[]}]",
                "[{\"id\":" + c1 + ",\"faqIds\":null}]"
        };
        for (String categories : bad) {
            assertThat(putJson("/admin/api/faqs/order", orderBody(v, categories)).getResponse().getStatus()).as(categories).isEqualTo(400);
        }
        assertThat(putJson("/admin/api/faqs/order", "{\"categories\":[]}").getResponse().getStatus()).as("version 누락").isEqualTo(400);

        assertThat(version()).isEqualTo(v);
        assertThat(jdbc.queryForObject("SELECT category_id FROM faq WHERE id = ?", Long.class, x)).isEqualTo(c2);
        assertThat(faqOrds(c1)).containsExactly(0, 1);
    }

    @Test
    @DisplayName("PUT /faqs/order는 순서 반영으로, PUT /faqs/{id}는 수정으로 라우팅된다(리터럴 경로 우선)")
    void routing_orderVersusId() throws Exception {
        long c = createCategory("c", true);
        long a = createFaq(c, "a");

        assertThat(putJson("/admin/api/faqs/order", orderBody(version(), "[{\"id\":" + c + ",\"faqIds\":[" + a + "]}]")).getResponse().getStatus()).isEqualTo(200);
        assertThat(putJson("/admin/api/faqs/" + a, faqBody(c, "수정", "<p>a</p>", true)).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT question FROM faq WHERE id = ?", String.class, a)).isEqualTo("수정");
        assertThat(run(get("/admin/api/faqs/order").with(asAdmin())).getResponse().getStatus()).as("order는 숫자 ID가 아니다").isEqualTo(400);
    }

    @Test
    @DisplayName("트리는 빈 카테고리도 포함하고 카테고리·FAQ 모두 (ord, id) 순서이며, 비노출도 관리 화면에는 보인다")
    void tree_includesEverything() throws Exception {
        long c1 = createCategory("c1", false);
        long c2 = createCategory("c2", true);
        createFaq(c1, "숨은 카테고리의 FAQ");
        long visible = createFaq(c2, "보이는 FAQ");
        jdbc.update("UPDATE faq SET use_yn = 0 WHERE id = ?", visible);

        JsonNode tree = tree();

        List<String> categoryNames = IntStream.range(0, tree.get("categories").size())
                .mapToObj(i -> tree.get("categories").get(i).get("name").asText()).collect(Collectors.toList());
        assertThat(categoryNames).containsExactly("c1", "c2");
        assertThat(tree.get("categories").get(0).get("useYn").asBoolean()).isFalse();
        assertThat(tree.get("categories").get(1).get("faqs").get(0).get("useYn").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("존재하지 않는 FAQ·카테고리 수정은 404이고 가드 행이 없으면 서버 오류가 난다(조용히 잠금 없이 진행하지 않는다)")
    void notFoundAndMissingGuard() throws Exception {
        assertThat(putJson("/admin/api/faqs/999999", faqBody(1L, "q", "<p>a</p>", true)).getResponse().getStatus()).isEqualTo(404);
        assertThat(putJson("/admin/api/faq-categories/999999", "{\"name\":\"x\",\"useYn\":true}").getResponse().getStatus()).isEqualTo(404);
        assertThat(run(get("/admin/api/faqs/999999").with(asAdmin())).getResponse().getStatus()).isEqualTo(404);

        LocalDateTime unused = LocalDateTime.now();
        assertThat(unused).isNotNull();
        jdbc.update("DELETE FROM faq_lock");
        try {
            assertThat(postJson("/admin/api/faq-categories", "{\"name\":\"x\",\"useYn\":true}").getResponse().getStatus()).isEqualTo(500);
        } finally {
            jdbc.update("INSERT INTO faq_lock (id, version) VALUES (1, 0)");
        }
    }
}
