package com.cms.admin.site;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.common.storage.FileStorage;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.MediaTestSupport;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 사이트별 콘텐츠 관리 API 계약(PLAN-site-menu.md PR ③ S2·S6·S6b): 수정(PUT)에서 {@code siteScope}·버전이 필수이고 누락·잘못된 값은 400, 낡은 버전은 409,
 * 등록에서는 누락이 공통, 존재하지 않는·관리자 사이트 지정은 400, 소속 콘텐츠가 있는 사이트는 삭제 409. 요청 JSON은 실제 MockMvc 바인딩으로 보낸다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class ContentSiteScopeApiIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired FileStorage fileStorage;
    @Autowired ObjectMapper objectMapper;

    private Member admin;
    private long alpha;
    private long beta;
    private long auditBaseline;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "scope-admin", Role.ROLE_ADMIN);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
        cleanContent();
        alpha = site("scope-alpha");
        beta = site("scope-beta");
    }

    @AfterEach
    void cleanUp() {
        cleanContent();
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE id > ?", auditBaseline);
    }

    private void cleanContent() {
        jdbc.update("DELETE FROM faq");
        jdbc.update("DELETE FROM faq_category");
        jdbc.update("DELETE FROM site_page_revision");
        jdbc.update("DELETE FROM site_page");
        jdbc.update("DELETE FROM popup");
        jdbc.update("DELETE FROM banner");
        MediaTestSupport.cleanLibrary(jdbc, fileStorage);
        jdbc.update("DELETE FROM site WHERE id > 2");
    }

    private long site(String key) {
        jdbc.update("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, default_yn) VALUES (?, ?, 'PUBLIC', 0, 1, 0)", key, key);
        return jdbc.queryForObject("SELECT id FROM site WHERE site_key = ?", Long.class, key);
    }

    private RequestPostProcessor asAdmin() {
        return TestMembers.asMember(admin);
    }

    private MvcResult send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String json) throws Exception {
        return mockMvc.perform(builder.with(asAdmin()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long banner() {
        jdbc.update("INSERT INTO banner (title, use_yn, ord, create_date, update_date) VALUES ('배너', 1, 0, NOW(), NOW())");
        long id = jdbc.queryForObject("SELECT MAX(id) FROM banner", Long.class);
        MediaTestSupport.attachImage(jdbc, "BANNER", id, "scope-api-" + id + ".png", 10);
        return id;
    }

    private long popup() {
        jdbc.update("INSERT INTO popup (title, popup_type, content_html, pos_x, pos_y, width, use_yn, revision) VALUES ('팝업', 'HTML', '<p>x</p>', 10, 10, 300, 1, 0)");
        return jdbc.queryForObject("SELECT MAX(id) FROM popup", Long.class);
    }

    private String bannerBody(String scopeField, Integer version) {
        return "{\"title\":\"배너\",\"useYn\":true" + (scopeField == null ? "" : "," + scopeField) + (version == null ? "" : ",\"version\":" + version) + "}";
    }

    // ===== 배너 =====

    @Test
    @DisplayName("배너 수정: siteScope·version 누락과 잘못된 값은 400이고 DB가 그대로다 — 누락이 조용히 공통 공개가 되지 않는다")
    void bannerUpdateRequiresScopeAndVersion() throws Exception {
        long id = banner();
        jdbc.update("UPDATE banner SET site_id = ? WHERE id = ?", alpha, id);

        String[] invalid = {null, "\"siteScope\":null", "\"siteScope\":\"\"", "\"siteScope\":\"abc\"", "\"siteScope\":\"0\"", "\"siteScope\":\"-1\"",
                "\"siteScope\":\"9223372036854775808\"", "\"siteScope\":\"9999999999999999999\"", "\"siteScope\":12"};
        for (String scopeField : invalid) {
            MvcResult result = send(put("/admin/api/banners/{id}", id), bannerBody(scopeField, 1));
            assertThat(result.getResponse().getStatus()).as("siteScope=" + scopeField).isEqualTo(400);
        }
        assertThat(send(put("/admin/api/banners/{id}", id), bannerBody("\"siteScope\":\"COMMON\"", null)).getResponse().getStatus())
                .as("version 누락").isEqualTo(400);
        assertThat(send(put("/admin/api/banners/{id}", id), bannerBody("\"siteScope\":\"COMMON\"", 0)).getResponse().getStatus())
                .as("version 0").isEqualTo(400);

        assertThat(jdbc.queryForObject("SELECT site_id FROM banner WHERE id = ?", Long.class, id)).as("전용이 공통으로 승격되지 않았다").isEqualTo(alpha);
        assertThat(jdbc.queryForObject("SELECT version FROM banner WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("배너 수정: 존재하지 않는 사이트·관리자 사이트 지정은 400, 공개 사이트 지정은 200이고 version이 오른다. 낡은 version은 409로 표시 대상을 되돌리지 못한다")
    void bannerScopeAssignmentAndStaleVersion() throws Exception {
        long id = banner();

        assertThat(send(put("/admin/api/banners/{id}", id), bannerBody("\"siteScope\":\"99999\"", 1)).getResponse().getStatus())
                .as("없는 사이트").isEqualTo(400);
        assertThat(send(put("/admin/api/banners/{id}", id), bannerBody("\"siteScope\":\"" + Site.ADMIN_SITE_ID + "\"", 1)).getResponse().getStatus())
                .as("관리자 사이트").isEqualTo(400);

        MvcResult assigned = send(put("/admin/api/banners/{id}", id), bannerBody("\"siteScope\":\"" + alpha + "\"", 1));
        assertThat(assigned.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(assigned).get("siteId").asLong()).isEqualTo(alpha);
        assertThat(body(assigned).get("version").asInt()).isEqualTo(2);

        // 두 번째 세션이 옛 version(1)과 COMMON으로 저장하려 한다 — 방금 전용으로 바뀐 배너를 공통으로 되돌리면 안 된다
        MvcResult stale = send(put("/admin/api/banners/{id}", id), bannerBody("\"siteScope\":\"COMMON\"", 1));
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT site_id FROM banner WHERE id = ?", Long.class, id)).isEqualTo(alpha);

        MvcResult back = send(put("/admin/api/banners/{id}", id), bannerBody("\"siteScope\":\"COMMON\"", 2));
        assertThat(back.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(back).path("siteId").asLong(0) == 0L).isTrue();
        assertThat(body(back).get("version").asInt()).isEqualTo(3);
    }

    // ===== 팝업 =====

    @Test
    @DisplayName("팝업 수정: revision은 시작값 0을 받아들이고(0 → 1), 이전 값 0으로 다시 저장하면 409이며, 누락·음수는 400")
    void popupRevisionZeroFlow() throws Exception {
        long id = popup();
        String update = "{\"title\":\"팝업\",\"content\":\"<p>x</p>\",\"contentFormat\":\"HTML\",\"posX\":10,\"posY\":10,\"width\":300,\"useYn\":true,"
                + "\"siteScope\":\"%s\",\"revision\":%s}";

        assertThat(send(put("/admin/api/popups/{id}", id), update.formatted(alpha, "null")).getResponse().getStatus()).as("revision null").isEqualTo(400);
        assertThat(send(put("/admin/api/popups/{id}", id), update.formatted(alpha, "-1")).getResponse().getStatus()).as("revision -1").isEqualTo(400);
        assertThat(send(put("/admin/api/popups/{id}", id), update.formatted("COMMON", "0").replace("\"siteScope\":\"COMMON\",", "")).getResponse().getStatus())
                .as("siteScope 누락").isEqualTo(400);

        MvcResult first = send(put("/admin/api/popups/{id}", id), update.formatted(alpha, "0"));
        assertThat(first.getResponse().getStatus()).as(first.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(first).get("revision").asInt()).isEqualTo(1);
        assertThat(body(first).get("siteId").asLong()).isEqualTo(alpha);

        MvcResult stale = send(put("/admin/api/popups/{id}", id), update.formatted("COMMON", "0"));
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT site_id FROM popup WHERE id = ?", Long.class, id)).as("공통으로 되돌려지지 않았다").isEqualTo(alpha);
    }

    // ===== FAQ =====

    @Test
    @DisplayName("FAQ 카테고리: 등록은 siteScope 누락이 공통(201), 수정은 누락이 400이고 같은 입력이 POST에서는 성공한다. 낡은 version은 409")
    void faqCategoryCreateOptionalUpdateRequired() throws Exception {
        MvcResult created = send(post("/admin/api/faq-categories"), "{\"name\":\"공통\",\"useYn\":true}");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        long id = body(created).get("id").asLong();
        assertThat(body(created).path("siteId").asLong(0) == 0L).isTrue();
        assertThat(body(created).get("version").asInt()).isEqualTo(1);

        assertThat(send(put("/admin/api/faq-categories/{id}", id), "{\"name\":\"공통\",\"useYn\":true,\"version\":1}").getResponse().getStatus())
                .as("PUT에서 같은 누락").isEqualTo(400);
        assertThat(send(put("/admin/api/faq-categories/{id}", id), "{\"name\":\"공통\",\"useYn\":true,\"siteScope\":\"COMMON\"}").getResponse().getStatus())
                .as("version 누락").isEqualTo(400);

        MvcResult assigned = send(put("/admin/api/faq-categories/{id}", id), "{\"name\":\"공통\",\"useYn\":true,\"siteScope\":\"" + alpha + "\",\"version\":1}");
        assertThat(assigned.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(assigned).get("siteId").asLong()).isEqualTo(alpha);
        assertThat(body(assigned).get("version").asInt()).isEqualTo(2);

        MvcResult stale = send(put("/admin/api/faq-categories/{id}", id), "{\"name\":\"공통\",\"useYn\":true,\"siteScope\":\"COMMON\",\"version\":1}");
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT site_id FROM faq_category WHERE id = ?", Long.class, id)).isEqualTo(alpha);
    }

    @Test
    @DisplayName("FAQ 항목: 수정에 baseCategoryId가 필수이고, 다른 관리자가 항목을 전용 카테고리로 옮긴 뒤 옛 소속으로 저장하면 409로 재공개를 막는다")
    void faqItemStaleCategoryBlocksRepublish() throws Exception {
        long common = body(send(post("/admin/api/faq-categories"), "{\"name\":\"공통\",\"useYn\":true}")).get("id").asLong();
        long scoped = body(send(post("/admin/api/faq-categories"), "{\"name\":\"전용\",\"useYn\":true,\"siteScope\":\"" + alpha + "\"}")).get("id").asLong();
        MvcResult created = send(post("/admin/api/faqs"),
                "{\"categoryId\":" + common + ",\"question\":\"질문\",\"answer\":\"<p>답</p>\",\"contentFormat\":\"HTML\",\"useYn\":true}");
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        long faq = body(created).get("id").asLong();

        String update = "{\"categoryId\":%d,%s\"question\":\"질문 수정\",\"answer\":\"<p>답</p>\",\"contentFormat\":\"HTML\",\"useYn\":true}";
        assertThat(send(put("/admin/api/faqs/{id}", faq), update.formatted(common, "")).getResponse().getStatus()).as("baseCategoryId 누락").isEqualTo(400);

        // 세션 A가 항목을 전용 카테고리로 옮긴다(편집 시작 소속 = common)
        MvcResult moved = send(put("/admin/api/faqs/{id}", faq), update.formatted(scoped, "\"baseCategoryId\":" + common + ","));
        assertThat(moved.getResponse().getStatus()).isEqualTo(200);

        // 세션 B는 옛 소속(common)에서 편집을 시작했고 질문만 고치려 한다 — 공통 카테고리로 되돌려 재공개하면 안 된다
        MvcResult stale = send(put("/admin/api/faqs/{id}", faq), update.formatted(common, "\"baseCategoryId\":" + common + ","));
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT category_id FROM faq WHERE id = ?", Long.class, faq)).isEqualTo(scoped);

        MvcResult fresh = send(put("/admin/api/faqs/{id}", faq), update.formatted(scoped, "\"baseCategoryId\":" + scoped + ","));
        assertThat(fresh.getResponse().getStatus()).isEqualTo(200);
    }

    // ===== 정적 페이지 =====

    @Test
    @DisplayName("정적 페이지: 등록은 누락이 공통, 수정은 siteScope 필수(400). 대상만 바꾸면 version은 오르고 개정 번호는 그대로이며, 낡은 version은 409")
    void pageScope() throws Exception {
        MvcResult created = send(post("/admin/api/site-pages"),
                "{\"slug\":\"scope-page\",\"title\":\"제목\",\"content\":\"<p>본문</p>\",\"contentFormat\":\"HTML\",\"useYn\":true,\"footerYn\":true}");
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        long id = body(created).get("id").asLong();
        assertThat(body(created).path("siteId").asLong(0) == 0L).isTrue();
        int version = body(created).get("version").asInt();

        String update = "{\"title\":\"제목\",\"content\":\"<p>본문</p>\",\"contentFormat\":\"HTML\",\"useYn\":true,\"footerYn\":true,%s\"version\":%d}";
        assertThat(send(put("/admin/api/site-pages/{id}", id), update.formatted("", version)).getResponse().getStatus()).as("siteScope 누락").isEqualTo(400);

        MvcResult assigned = send(put("/admin/api/site-pages/{id}", id), update.formatted("\"siteScope\":\"" + beta + "\",", version));
        assertThat(assigned.getResponse().getStatus()).as(assigned.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(assigned).get("siteId").asLong()).isEqualTo(beta);
        assertThat(body(assigned).get("version").asInt()).isEqualTo(version + 1);
        assertThat(body(assigned).get("currentRevisionNo").asInt()).as("본문이 같으면 개정은 쌓이지 않는다").isEqualTo(1);

        MvcResult stale = send(put("/admin/api/site-pages/{id}", id), update.formatted("\"siteScope\":\"COMMON\",", version));
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT site_id FROM site_page WHERE id = ?", Long.class, id)).isEqualTo(beta);
    }

    // ===== 사이트 삭제 가드 =====

    @Test
    @DisplayName("소속 콘텐츠(배너·팝업·FAQ 카테고리·정적 페이지)가 있는 사이트는 삭제 409, 콘텐츠를 지우면 삭제된다")
    void siteDeleteGuard() throws Exception {
        long banner = banner();
        long popup = popup();
        jdbc.update("INSERT INTO faq_category (name, ord, use_yn, create_date, update_date) VALUES ('c', 0, 1, NOW(), NOW())");
        long category = jdbc.queryForObject("SELECT MAX(id) FROM faq_category", Long.class);
        jdbc.update("INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn, create_date, update_date) VALUES ('guard-page', 1, 1, 1, 0, NOW(), NOW())");
        long page = jdbc.queryForObject("SELECT id FROM site_page WHERE slug = 'guard-page'", Long.class);
        jdbc.update("INSERT INTO site_page_revision (page_id, revision_no, title, content, create_date) VALUES (?, 1, 't', '<p>x</p>', NOW())", page);

        String[][] owners = {{"banner", String.valueOf(banner)}, {"popup", String.valueOf(popup)},
                {"faq_category", String.valueOf(category)}, {"site_page", String.valueOf(page)}};
        for (String[] owner : owners) {
            long target = site("guard-" + owner[0].replace('_', '-'));
            jdbc.update("UPDATE " + owner[0] + " SET site_id = ? WHERE id = ?", target, Long.parseLong(owner[1]));

            MvcResult blocked = mockMvc.perform(delete("/admin/api/sites/{id}", target).with(asAdmin()).with(csrf())).andReturn();
            assertThat(blocked.getResponse().getStatus()).as(owner[0] + " 전용 콘텐츠가 있으면 삭제 불가").isEqualTo(409);

            jdbc.update("UPDATE " + owner[0] + " SET site_id = NULL WHERE id = ?", Long.parseLong(owner[1]));
            MvcResult deleted = mockMvc.perform(delete("/admin/api/sites/{id}", target).with(asAdmin()).with(csrf())).andReturn();
            assertThat(deleted.getResponse().getStatus()).as(owner[0] + " 콘텐츠를 비우면 삭제 가능").isIn(200, 204);
        }
    }
}
