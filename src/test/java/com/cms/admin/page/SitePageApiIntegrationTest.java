package com.cms.admin.page;

import com.cms.admin.log.constant.AdminActionTypes;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 정적 페이지 관리 API 실제 스택 시험(PLAN-static-page.md): SecurityConfig·판정기·감사·MariaDB.
 * MockMvc 호출이 실제로 커밋하므로 {@link #cleanUp()}이 만든 페이지·개정·감사 행을 지운다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class SitePageApiIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired ObjectMapper objectMapper;

    private Member admin;
    private long auditBaseline;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "sp-admin", Role.ROLE_ADMIN);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
        cleanPages();
    }

    @AfterEach
    void cleanUp() {
        cleanPages();
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE id > ?", auditBaseline);
    }

    private void cleanPages() {
        jdbc.update("DELETE FROM site_page_revision");
        jdbc.update("DELETE FROM site_page");
    }

    private RequestPostProcessor asAdmin() {
        return TestMembers.asMember(admin);
    }

    private static String p(String text) {
        return "<p>" + text + "</p>";
    }

    private MvcResult createRaw(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/admin/api/site-pages").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))).andReturn();
    }

    private Map<String, Object> createBody(String slug, String title, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slug", slug);
        body.put("title", title);
        body.put("content", content);
        body.put("contentFormat", "HTML");
        return body;
    }

    private JsonNode createOk(String slug, String title, String content) throws Exception {
        MvcResult result = createRaw(createBody(slug, title, content));
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return json(result);
    }

    private Map<String, Object> updateBody(String title, String content, boolean useYn, boolean footerYn, Object version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("content", content);
        body.put("contentFormat", "HTML");
        body.put("useYn", useYn);
        body.put("footerYn", footerYn);
        body.put("version", version);
        return body;
    }

    private MvcResult updateRaw(long id, Map<String, Object> body) throws Exception {
        return mockMvc.perform(put("/admin/api/site-pages/{id}", id).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))).andReturn();
    }

    private JsonNode updateOk(long id, Map<String, Object> body) throws Exception {
        MvcResult result = updateRaw(id, body);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json(result);
    }

    private int deleteStatus(long id, Object version) throws Exception {
        var request = delete("/admin/api/site-pages/{id}", id).with(asAdmin()).with(csrf());
        if (version != null) {
            request.param("version", String.valueOf(version));
        }
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int pageCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM site_page", Integer.class);
    }

    private int revisionCount(long pageId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM site_page_revision WHERE page_id = ?", Integer.class, pageId);
    }

    // ===== 생명주기 =====

    @Test
    @DisplayName("등록→목록·상세→본문 수정(개정 2)→메타만 수정(개정 유지·버전만)→변경 없음 저장(아무것도 안 오름)→삭제 왕복, 감사 라벨 rev=N")
    void lifecycle() throws Exception {
        JsonNode created = createOk("privacy-policy", "개인정보처리방침", p("첫 본문"));
        long id = created.get("id").asLong();
        assertThat(created.get("currentRevisionNo").asInt()).isEqualTo(1);
        assertThat(created.get("version").asInt()).isEqualTo(1);
        assertThat(created.get("useYn").asBoolean()).as("누락 시 true").isTrue();
        assertThat(created.get("footerYn").asBoolean()).as("누락 시 false").isFalse();
        assertThat(created.get("content").asText()).isEqualTo(p("첫 본문"));

        JsonNode list = json(mockMvc.perform(get("/admin/api/site-pages").with(asAdmin())).andReturn());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("slug").asText()).isEqualTo("privacy-policy");
        assertThat(list.get(0).has("content")).as("목록은 본문을 싣지 않는다").isFalse();

        // 본문 수정 → 개정 2, 버전 2
        JsonNode second = updateOk(id, updateBody("개인정보처리방침 개정", p("둘째 본문"), true, false, 1));
        assertThat(second.get("currentRevisionNo").asInt()).isEqualTo(2);
        assertThat(second.get("version").asInt()).isEqualTo(2);
        assertThat(second.get("title").asText()).isEqualTo("개인정보처리방침 개정");
        assertThat(revisionCount(id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT content FROM site_page_revision WHERE page_id = ? AND revision_no = 1", String.class, id))
                .as("이전 개정의 본문은 그대로 보존").isEqualTo(p("첫 본문"));

        // 메타만 수정 → 개정 번호 유지, 버전만 +1
        JsonNode third = updateOk(id, updateBody("개인정보처리방침 개정", p("둘째 본문"), false, true, 2));
        assertThat(third.get("currentRevisionNo").asInt()).isEqualTo(2);
        assertThat(third.get("version").asInt()).isEqualTo(3);
        assertThat(third.get("useYn").asBoolean()).isFalse();
        assertThat(third.get("footerYn").asBoolean()).isTrue();
        assertThat(revisionCount(id)).as("메타만 바꾸면 개정은 늘지 않는다").isEqualTo(2);

        // 같은 내용 재저장 → 개정도 버전도 오르지 않는다(200 + 현재 상태)
        JsonNode same = updateOk(id, updateBody("개인정보처리방침 개정", p("둘째 본문"), false, true, 3));
        assertThat(same.get("currentRevisionNo").asInt()).isEqualTo(2);
        assertThat(same.get("version").asInt()).isEqualTo(3);
        assertThat(revisionCount(id)).isEqualTo(2);

        JsonNode detail = json(mockMvc.perform(get("/admin/api/site-pages/{id}", id).with(asAdmin())).andReturn());
        assertThat(detail.get("revisionCount").asInt()).isEqualTo(2);
        assertThat(detail.get("revisions")).hasSize(2);
        assertThat(detail.get("revisions").get(0).get("revisionNo").asInt()).as("최신순").isEqualTo(2);
        assertThat(detail.get("revisions").get(0).has("content")).as("개정 요약은 본문을 싣지 않는다").isFalse();

        assertThat(deleteStatus(id, 3)).isEqualTo(204);
        assertThat(pageCount()).isZero();
        assertThat(revisionCount(id)).as("개정도 함께 삭제").isZero();
        assertThat(deleteStatus(id, 3)).as("이미 삭제됨").isEqualTo(404);

        List<Map<String, Object>> logs = jdbc.queryForList(
                "SELECT action_type, action_result, target_id, target_label FROM admin_action_log WHERE id > ? AND target_type = 'SITE_PAGE' AND action_result = 'SUCCESS' ORDER BY id",
                auditBaseline);
        assertThat(logs).extracting(row -> row.get("action_type")).containsExactly(
                AdminActionTypes.SITE_PAGE_CREATE, AdminActionTypes.SITE_PAGE_UPDATE, AdminActionTypes.SITE_PAGE_UPDATE,
                AdminActionTypes.SITE_PAGE_UPDATE, AdminActionTypes.SITE_PAGE_DELETE);
        assertThat(logs).extracting(row -> row.get("target_id")).containsOnly(id);
        assertThat(logs).extracting(row -> row.get("target_label")).containsExactly(
                "rev=1", "rev=2", "rev=2 유지", "rev=2 변경 없음", "rev=2");
    }

    @Test
    @DisplayName("본문 정리: script·이벤트 속성·javascript: 링크·모든 img(/content-images 포함)는 저장 시 제거된다")
    void bodyIsSanitizedWithoutImages() throws Exception {
        String dirty = "<p onclick=\"x()\">본문<script>alert(1)</script> <a href=\"javascript:alert(1)\">링크</a>"
                + "<img src=\"/content-images/7\"><img src=\"data:image/png;base64,AAAA\"></p>";

        JsonNode created = createOk("sanitize-me", "정리", dirty);

        String stored = jdbc.queryForObject("SELECT content FROM site_page_revision WHERE page_id = ?", String.class, created.get("id").asLong());
        assertThat(stored).contains("본문").doesNotContain("script").doesNotContain("onclick").doesNotContain("javascript:")
                .doesNotContain("<img").doesNotContain("content-images").doesNotContain("data:");
    }

    @Test
    @DisplayName("5만 자 본문(게시글 상한의 5배)도 등록되고 개정 요약·목록에는 본문이 실리지 않는다")
    void largeBodyAllowed() throws Exception {
        JsonNode created = createOk("long-policy", "긴 방침", p("가".repeat(50_000)));

        assertThat(created.get("content").asText().length()).isGreaterThan(50_000);
        assertThat(created.get("revisions").get(0).has("content")).isFalse();
    }

    // ===== 등록 검증 =====

    @Test
    @DisplayName("등록 검증: 슬러그 형식·예약어·빈 제목·빈/이미지뿐인 본문·형식 표식 누락·원문 상한 초과는 모두 400이고 행을 남기지 않는다")
    void createValidation() throws Exception {
        for (String badSlug : List.of("A-b", "a", "-ab", "ab-", "a--b", "a_b", "a b", "한글", "a/b", "x".repeat(51), "revisions", "new", "edit", "index")) {
            assertThat(createRaw(createBody(badSlug, "t", p("본문"))).getResponse().getStatus()).as(badSlug).isEqualTo(400);
        }
        assertThat(createRaw(createBody("ok-slug", "   ", p("본문"))).getResponse().getStatus()).as("빈 제목").isEqualTo(400);
        assertThat(createRaw(createBody("ok-slug", "t", "   ")).getResponse().getStatus()).as("빈 본문").isEqualTo(400);
        assertThat(createRaw(createBody("ok-slug", "t", "<p><img src=\"/content-images/1\"></p>")).getResponse().getStatus())
                .as("이미지만 있는 본문은 이미지 제거 후 공백").isEqualTo(400);
        Map<String, Object> noFormat = createBody("ok-slug", "t", p("본문"));
        noFormat.remove("contentFormat");
        assertThat(createRaw(noFormat).getResponse().getStatus()).as("형식 표식 누락").isEqualTo(400);
        assertThat(createRaw(createBody("ok-slug", "t".repeat(201), p("본문"))).getResponse().getStatus()).as("제목 201자").isEqualTo(400);
        assertThat(createRaw(createBody("ok-slug", "t", p("가".repeat(50_001)))).getResponse().getStatus()).as("보이는 글자 상한").isEqualTo(400);

        // 정리 후에는 작지만 원문이 600,000바이트를 넘는 요청 — sanitizer 이전에 거른다
        String bloated = p("본문") + "<span class=\"" + "x".repeat(610_000) + "\"></span>";
        MvcResult tooRaw = createRaw(createBody("ok-slug", "t", bloated));
        assertThat(tooRaw.getResponse().getStatus()).as("원문 크기 상한").isEqualTo(400);

        assertThat(pageCount()).isZero();
    }

    @Test
    @DisplayName("슬러그 중복은 409이고 기존 페이지는 그대로다")
    void duplicateSlug() throws Exception {
        JsonNode first = createOk("terms", "이용약관", p("본문"));

        MvcResult duplicate = createRaw(createBody("terms", "다른 제목", p("다른 본문")));

        assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
        assertThat(pageCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM site_page_revision WHERE page_id = ?", String.class, first.get("id").asLong()))
                .isEqualTo("이용약관");
    }

    // ===== 수정 검증과 낡은 화면 =====

    @Test
    @DisplayName("수정 검증: version 누락·0·음수·노출/푸터 누락은 400, 빈 제목·형식 표식 누락도 400, 없는 페이지는 404")
    void updateValidation() throws Exception {
        long id = createOk("about", "소개", p("본문")).get("id").asLong();

        assertThat(updateRaw(id, updateBody("t", p("x"), true, false, null)).getResponse().getStatus()).as("version 누락").isEqualTo(400);
        assertThat(updateRaw(id, updateBody("t", p("x"), true, false, 0)).getResponse().getStatus()).as("version 0").isEqualTo(400);
        assertThat(updateRaw(id, updateBody("t", p("x"), true, false, -1)).getResponse().getStatus()).as("version 음수").isEqualTo(400);
        Map<String, Object> noUse = updateBody("t", p("x"), true, false, 1);
        noUse.remove("useYn");
        assertThat(updateRaw(id, noUse).getResponse().getStatus()).as("useYn 누락").isEqualTo(400);
        Map<String, Object> noFooter = updateBody("t", p("x"), true, false, 1);
        noFooter.remove("footerYn");
        assertThat(updateRaw(id, noFooter).getResponse().getStatus()).as("footerYn 누락").isEqualTo(400);
        assertThat(updateRaw(id, updateBody("  ", p("x"), true, false, 1)).getResponse().getStatus()).as("빈 제목").isEqualTo(400);
        Map<String, Object> noFormat = updateBody("t", p("x"), true, false, 1);
        noFormat.remove("contentFormat");
        assertThat(updateRaw(id, noFormat).getResponse().getStatus()).as("형식 표식 누락").isEqualTo(400);
        assertThat(updateRaw(999_999L, updateBody("t", p("x"), true, false, 1)).getResponse().getStatus()).isEqualTo(404);

        assertThat(revisionCount(id)).as("검증 실패는 아무것도 바꾸지 않는다").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM site_page WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("낡은 화면 ① 본문→본문: 같은 version을 든 두 번째 저장은 409이고 첫 저장이 그대로 남는다")
    void staleBodyAfterBody() throws Exception {
        long id = createOk("stale-body", "원본", p("원본")).get("id").asLong();
        updateOk(id, updateBody("A의 제목", p("A의 본문"), true, false, 1));

        MvcResult b = updateRaw(id, updateBody("B의 제목", p("B의 본문"), true, false, 1));

        assertThat(b.getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT title FROM site_page_revision WHERE page_id = ? AND revision_no = 2", String.class, id)).isEqualTo("A의 제목");
        assertThat(revisionCount(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("낡은 화면 ② 메타→본문: A가 노출을 끈 뒤 낡은 화면(노출 켜짐)의 제목 수정은 409 — 비공개 페이지가 되살아나지 않는다")
    void staleBodyAfterMeta() throws Exception {
        long id = createOk("stale-meta", "원본", p("원본")).get("id").asLong();
        updateOk(id, updateBody("원본", p("원본"), false, false, 1));            // A: 노출 끄기 → 개정 번호는 1 그대로, version 2

        MvcResult b = updateRaw(id, updateBody("B의 새 제목", p("원본"), true, false, 1));   // B: 개정 번호만 보면 같은 상태

        assertThat(b.getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT use_yn FROM site_page WHERE id = ?", Boolean.class, id)).as("계속 비노출").isFalse();
        assertThat(revisionCount(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("낡은 화면 ③ 메타→메타: 푸터를 켠 뒤 낡은 화면의 노출 변경은 409")
    void staleMetaAfterMeta() throws Exception {
        long id = createOk("stale-meta2", "원본", p("원본")).get("id").asLong();
        updateOk(id, updateBody("원본", p("원본"), true, true, 1));

        MvcResult b = updateRaw(id, updateBody("원본", p("원본"), false, false, 1));

        assertThat(b.getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT footer_yn FROM site_page WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT use_yn FROM site_page WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("낡은 화면 ④ 수정→삭제: 수정이 먼저 커밋됐으면 낡은 version의 삭제는 409이고 페이지·개정이 모두 남는다")
    void staleDeleteAfterUpdate() throws Exception {
        long id = createOk("stale-delete", "원본", p("원본")).get("id").asLong();
        updateOk(id, updateBody("수정됨", p("수정됨"), true, false, 1));

        assertThat(deleteStatus(id, 1)).isEqualTo(409);

        assertThat(pageCount()).isEqualTo(1);
        assertThat(revisionCount(id)).isEqualTo(2);
        assertThat(deleteStatus(id, 2)).as("최신 version이면 삭제된다").isEqualTo(204);
    }

    @Test
    @DisplayName("삭제→수정: 삭제가 먼저면 뒤따른 수정은 404이고 아무것도 되살아나지 않는다")
    void updateAfterDelete() throws Exception {
        long id = createOk("gone", "원본", p("원본")).get("id").asLong();
        assertThat(deleteStatus(id, 1)).isEqualTo(204);

        assertThat(updateRaw(id, updateBody("t", p("x"), true, false, 1)).getResponse().getStatus()).isEqualTo(404);
        assertThat(pageCount()).isZero();
    }

    @Test
    @DisplayName("삭제 검증: version 누락은 400, 비숫자는 400, 없는 페이지는 404 — 모두 아무것도 지우지 않는다")
    void deleteValidation() throws Exception {
        long id = createOk("del-validate", "원본", p("원본")).get("id").asLong();

        assertThat(deleteStatus(id, null)).isEqualTo(400);
        assertThat(mockMvc.perform(delete("/admin/api/site-pages/{id}", id).param("version", "abc").with(asAdmin()).with(csrf()))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(deleteStatus(id, 0)).isEqualTo(400);
        assertThat(deleteStatus(999_999L, 1)).isEqualTo(404);
        assertThat(pageCount()).isEqualTo(1);
    }

    // ===== 개정 목록 상한과 한계 =====

    @Test
    @DisplayName("개정 요약은 최근 20건만(최신순)이고 revisionCount는 전체 개수다")
    void revisionSummaryIsCapped() throws Exception {
        JsonNode current = createOk("many-revisions", "제목 1", p("본문 1"));
        long id = current.get("id").asLong();
        int version = current.get("version").asInt();
        for (int i = 2; i <= 26; i++) {
            version = updateOk(id, updateBody("제목 " + i, p("본문 " + i), true, false, version)).get("version").asInt();
        }

        JsonNode detail = json(mockMvc.perform(get("/admin/api/site-pages/{id}", id).with(asAdmin())).andReturn());

        assertThat(detail.get("revisionCount").asInt()).isEqualTo(26);
        assertThat(detail.get("revisions")).hasSize(20);
        assertThat(detail.get("revisions").get(0).get("revisionNo").asInt()).isEqualTo(26);
        assertThat(detail.get("revisions").get(19).get("revisionNo").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("개정 번호가 int 한계면 본문 수정은 쓰기 전에 실패하고 페이지·개정·버전이 그대로다(음수 개정이 저장되지 않는다)")
    void revisionNumberOverflowFailsClosed() throws Exception {
        JsonNode created = createOk("overflow", "원본", p("원본"));
        long id = created.get("id").asLong();
        jdbc.update("UPDATE site_page_revision SET revision_no = ? WHERE page_id = ?", Integer.MAX_VALUE, id);
        jdbc.update("UPDATE site_page SET current_revision_no = ? WHERE id = ?", Integer.MAX_VALUE, id);

        MvcResult result = updateRaw(id, updateBody("새 제목", p("새 본문"), true, false, 1));

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(revisionCount(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_revision_no FROM site_page WHERE id = ?", Integer.class, id)).isEqualTo(Integer.MAX_VALUE);
        assertThat(jdbc.queryForObject("SELECT version FROM site_page WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }

    // ===== 감사 =====

    @Test
    @DisplayName("실패 감사 행의 errorMessage는 고정 문구다 — 제목·본문 표식이 감사 테이블에 남지 않는다")
    void failureAuditUsesFixedMessage() throws Exception {
        long id = createOk("audit-fail", "원본", p("원본")).get("id").asLong();
        String marker = "MARKER-제목-9f3a";

        updateRaw(id, updateBody(marker, p("MARKER-본문-9f3a"), true, false, 99));            // 409
        createRaw(createBody("audit-fail", marker, p("MARKER-본문-9f3a")));                  // 409 슬러그 중복
        deleteStatus(id, 99);                                                                 // 409

        List<Map<String, Object>> failures = jdbc.queryForList(
                "SELECT action_type, error_message FROM admin_action_log WHERE id > ? AND target_type = 'SITE_PAGE' AND action_result = 'FAIL' ORDER BY id",
                auditBaseline);
        assertThat(failures).hasSize(3);
        assertThat(failures).extracting(row -> row.get("error_message")).containsExactly(
                "정적 페이지 수정에 실패했습니다.", "정적 페이지 등록에 실패했습니다.", "정적 페이지 삭제에 실패했습니다.");
        assertThat(failures.toString()).doesNotContain("MARKER");
    }

    @Test
    @DisplayName("관리 화면 JSON에 감사 라벨(auditLabel)은 노출되지 않는다")
    void auditLabelIsNotSerialized() throws Exception {
        JsonNode created = createOk("label-hidden", "t", p("x"));

        assertThat(created.has("auditLabel")).isFalse();
    }
}
