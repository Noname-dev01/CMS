package com.cms.admin.site;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.config.auth.CustomUserDetails;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사이트·메뉴 API를 실제 스택(Security·Validation·advice·DB)으로 따라간다 — PLAN-site-menu.md PR ①의 사이트 분리 편집 계약:
 * 사이트별 트리, 사이드바 격리, 링크 정책, 교차 사이트 부모(404)·구조 반영(409), 정책 강화 가드, 삭제 가드, siteId 누락(500 아닌 400).
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class SiteMenuApiIntegrationTest extends MariaDbContainerSupport {

    private static final String KEY = "smt-";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM menu WHERE menu_name LIKE 'SMT_%'");
        jdbc.update("DELETE FROM site WHERE site_key LIKE ?", KEY + "%");
    }

    private RequestPostProcessor principal(Role role) {
        Member member = Member.builder().id(1L).userId("u01").userName("u01").email("u01@example.com")
                .userType(role).status(MemberStatus.ACTIVE).build();
        CustomUserDetails details = new CustomUserDetails(member);
        return authentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private RequestPostProcessor admin() {
        return principal(Role.ROLE_ADMIN);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long createSite(String keySuffix, boolean allowExternal) throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/api/sites").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteKey\":\"" + KEY + keySuffix + "\",\"siteName\":\"SMT 사이트 " + keySuffix
                                + "\",\"allowExternalLink\":" + allowExternal + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).path("id").asLong();
    }

    private MvcResult createMenu(long siteId, String name, String url, Long upMenuNo) throws Exception {
        String body = "{\"siteId\":" + siteId + ",\"menuName\":\"SMT_" + name + "\""
                + (url == null ? "" : ",\"menuUrl\":\"" + url + "\"")
                + (upMenuNo == null ? "" : ",\"upMenuNo\":" + upMenuNo) + "}";
        return mockMvc.perform(post("/admin/api/menus").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private long createMenuOk(long siteId, String name, String url, Long upMenuNo) throws Exception {
        MvcResult result = createMenu(siteId, name, url, upMenuNo);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return json(result).path("menuNo").asLong();
    }

    private JsonNode tree(long siteId) throws Exception {
        return json(mockMvc.perform(get("/admin/api/menus/tree").param("siteId", String.valueOf(siteId)).param("useYn", "all").with(admin()))
                .andExpect(status().isOk()).andReturn());
    }

    private static boolean containsMenu(JsonNode nodes, String name) {
        for (JsonNode node : nodes) {
            if (name.equals(node.path("text").asText()) || containsMenu(node.path("children"), name)) {
                return true;
            }
        }
        return false;
    }

    // ── 사이트별 트리 · 격리 ──

    @Test
    @DisplayName("공개 사이트 메뉴는 그 사이트 트리에만 보이고, 관리자 트리·관리자 사이드바에는 나오지 않으며, 노출 안내(exposure)가 없다")
    void publicSiteMenus_areIsolatedFromAdminSite() throws Exception {
        long siteId = createSite("iso", true);
        long menuNo = createMenuOk(siteId, "공개메뉴", "https://example.com/docs", null);
        long adminMenuNo = createMenuOk(1L, "관리메뉴", "/admin/member/info", null);

        JsonNode publicTree = tree(siteId);
        assertThat(containsMenu(publicTree, "SMT_공개메뉴")).isTrue();
        assertThat(containsMenu(publicTree, "SMT_관리메뉴")).isFalse();
        assertThat(publicTree.get(0).path("data").has("exposure")).as("공개 사이트는 노출 안내가 없다").isFalse();
        assertThat(publicTree.get(0).path("data").path("menuNo").asLong()).isEqualTo(menuNo);

        JsonNode adminTree = tree(1L);
        assertThat(containsMenu(adminTree, "SMT_관리메뉴")).isTrue();
        assertThat(containsMenu(adminTree, "SMT_공개메뉴")).isFalse();
        JsonNode adminNode = findByMenuNo(adminTree, adminMenuNo);
        assertThat(adminNode.path("data").has("exposure")).as("관리자 사이트는 노출 안내가 있다").isTrue();

        String dashboard = mockMvc.perform(get("/admin").with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(dashboard).contains("SMT_관리메뉴").doesNotContain("SMT_공개메뉴").doesNotContain("example.com/docs");
    }

    private static JsonNode findByMenuNo(JsonNode nodes, long menuNo) {
        for (JsonNode node : nodes) {
            if (node.path("data").path("menuNo").asLong() == menuNo) {
                return node;
            }
            JsonNode inChildren = findByMenuNo(node.path("children"), menuNo);
            if (inChildren != null) {
                return inChildren;
            }
        }
        return null;
    }

    // ── 링크 정책 ──

    @Test
    @DisplayName("링크 정책: 관리자 사이트는 외부 링크 400, 공개 사이트는 외부 허용 시 201·비공개 경로(/admin·/swagger-ui.html) 400, 외부 불가 사이트는 외부 400")
    void linkPolicyPerSite() throws Exception {
        long external = createSite("ext", true);
        long internalOnly = createSite("int", false);

        assertThat(createMenu(1L, "a-ext", "https://example.com/x", null).getResponse().getStatus()).isEqualTo(400);
        MvcResult adminExternal = createMenu(1L, "a-ext2", "https://example.com/x", null);
        assertThat(json(adminExternal).path("code").asText()).isEqualTo("INVALID_REQUEST");

        assertThat(createMenu(external, "p-ext", "https://example.com/x", null).getResponse().getStatus()).isEqualTo(201);
        assertThat(createMenu(external, "p-path", "/faq", null).getResponse().getStatus()).isEqualTo(201);
        for (String bad : new String[]{"/admin/login", "/swagger-ui.html", "/%61dmin", "/a/../admin", "http://localhost:8080/admin/x"}) {
            assertThat(createMenu(external, "p-bad", bad, null).getResponse().getStatus()).as(bad).isEqualTo(400);
        }
        assertThat(createMenu(internalOnly, "i-ext", "https://example.com/x", null).getResponse().getStatus()).isEqualTo(400);
        assertThat(createMenu(internalOnly, "i-path", "/pages/privacy", null).getResponse().getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM menu WHERE menu_name IN ('SMT_a-ext','SMT_a-ext2','SMT_p-bad','SMT_i-ext')",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("메뉴 수정도 소속 사이트의 정책으로 판정한다 — 관리자 메뉴를 외부 URL로 바꿀 수 없고, 공개 메뉴는 허용 사이트에서만 외부로 바꿀 수 있다")
    void updateUsesOwnSitePolicy() throws Exception {
        long siteId = createSite("upd", true);
        long publicMenu = createMenuOk(siteId, "u-pub", "/faq", null);
        long adminMenu = createMenuOk(1L, "u-adm", "/admin/member/info", null);

        mockMvc.perform(patch("/admin/api/menus/" + adminMenu).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuUrl\":\"https://example.com/x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(patch("/admin/api/menus/" + publicMenu).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuUrl\":\"https://example.com/x\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/admin/api/menus/" + publicMenu).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuUrl\":\"/admin\"}"))
                .andExpect(status().isBadRequest());
        // URL을 보내지 않는 수정(이름만)은 정책 판정 없이 통과한다
        mockMvc.perform(patch("/admin/api/menus/" + publicMenu).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuName\":\"SMT_u-pub2\"}"))
                .andExpect(status().isOk());
    }

    // ── 교차 사이트 ──

    @Test
    @DisplayName("교차 사이트 부모는 없는 부모와 같은 404, 구조 반영에 다른 사이트 메뉴 번호가 섞이면 409이고 아무것도 바뀌지 않는다")
    void crossSiteParentAndStructure() throws Exception {
        long siteA = createSite("xa", true);
        long siteB = createSite("xb", true);
        long aRoot = createMenuOk(siteA, "a-root", "/a", null);
        long bRoot = createMenuOk(siteB, "b-root", "/b", null);

        MvcResult cross = createMenu(siteA, "a-child", "/a/1", bRoot);
        MvcResult missing = createMenu(siteA, "a-child2", "/a/2", 99999999L);
        assertThat(cross.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(cross.getResponse().getContentAsString().replaceAll("\"timestamp\":\"[^\"]*\"", ""))
                .as("교차 사이트와 없는 부모는 구별되지 않는다")
                .isEqualTo(missing.getResponse().getContentAsString().replaceAll("\"timestamp\":\"[^\"]*\"", ""));

        String stale = "{\"siteId\":" + siteA + ",\"menus\":[{\"menuNo\":" + aRoot + ",\"baseUpMenuNo\":null,\"baseOrd\":0,\"upMenuNo\":null},"
                + "{\"menuNo\":" + bRoot + ",\"baseUpMenuNo\":null,\"baseOrd\":0,\"upMenuNo\":null}]}";
        mockMvc.perform(put("/admin/api/menus/structure").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(stale))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT site_id FROM menu WHERE menu_no = ?", Long.class, bRoot)).isEqualTo(siteB);
    }

    // ── 정책 강화 · 삭제 가드 ──

    @Test
    @DisplayName("외부 링크 허용 해제는 외부 URL 메뉴가 있으면 409, 내부 경로로 바꾸면 통과하고 이후 외부 URL 생성은 400")
    void tighteningGuard() throws Exception {
        long siteId = createSite("tight", true);
        long menuNo = createMenuOk(siteId, "t-ext", "https://example.com/x", null);

        mockMvc.perform(patch("/admin/api/sites/" + siteId).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowExternalLink\":false}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT allow_external_link FROM site WHERE id = ?", Boolean.class, siteId)).isTrue();

        mockMvc.perform(patch("/admin/api/menus/" + menuNo).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuUrl\":\"/docs\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/admin/api/sites/" + siteId).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowExternalLink\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowExternalLink").value(false));
        assertThat(createMenu(siteId, "t-ext2", "https://example.com/y", null).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("삭제 가드: 메뉴가 있는 사이트·시드 사이트는 409, 메뉴를 지우면 사이트도 지워지고 비활성화·수정은 반영된다")
    void deleteGuards() throws Exception {
        long siteId = createSite("del", true);
        long menuNo = createMenuOk(siteId, "d-menu", "/x", null);

        mockMvc.perform(delete("/admin/api/sites/" + siteId).with(admin()).with(csrf())).andExpect(status().isConflict());
        mockMvc.perform(delete("/admin/api/sites/1").with(admin()).with(csrf())).andExpect(status().isConflict());
        mockMvc.perform(delete("/admin/api/sites/2").with(admin()).with(csrf())).andExpect(status().isConflict());
        mockMvc.perform(patch("/admin/api/sites/2").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"useYn\":false}"))
                .andExpect(status().isConflict());
        mockMvc.perform(patch("/admin/api/sites/1").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowExternalLink\":true}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/admin/api/sites/" + siteId).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteName\":\"SMT 새 이름\",\"useYn\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.useYn").value(false))
                .andExpect(jsonPath("$.menuCount").value(1));

        mockMvc.perform(patch("/admin/api/menus/" + menuNo).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"useYn\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/admin/api/menus/" + menuNo).with(admin()).with(csrf())).andExpect(status().isNoContent());
        mockMvc.perform(delete("/admin/api/sites/" + siteId).with(admin()).with(csrf())).andExpect(status().isNoContent());
        mockMvc.perform(get("/admin/api/sites/" + siteId).with(admin())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site WHERE id IN (1, 2)", Integer.class)).isEqualTo(2);
    }

    // ── 사이트 키 · 입력 ──

    @Test
    @DisplayName("사이트 생성: 예약어 400, 형식 위반 400, 중복 409 — 키는 수정 요청이 와도 바뀌지 않는다")
    void siteKeyRules() throws Exception {
        for (String reserved : new String[]{"faq", "admin", "notices", "swagger-ui", "content-images"}) {
            mockMvc.perform(post("/admin/api/sites").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"siteKey\":\"" + reserved + "\",\"siteName\":\"예약\"}"))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/admin/api/sites").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteKey\":\"Bad_Key\",\"siteName\":\"형식\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/admin/api/sites").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteKey\":\"main\",\"siteName\":\"중복\"}"))
                .andExpect(status().isConflict());

        long siteId = createSite("key", false);
        mockMvc.perform(patch("/admin/api/sites/" + siteId).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteKey\":\"hacked\",\"siteType\":\"ADMIN\",\"siteName\":\"SMT 키 불변\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT site_key FROM site WHERE id = ?", String.class, siteId)).isEqualTo(KEY + "key");
        assertThat(jdbc.queryForObject("SELECT site_type FROM site WHERE id = ?", String.class, siteId)).isEqualTo("PUBLIC");
    }

    @Test
    @DisplayName("siteId 누락·빈 값·숫자 아님은 500이 아니라 400, 없는 사이트는 404 (실제 advice)")
    void siteIdParameterHandling() throws Exception {
        mockMvc.perform(get("/admin/api/menus/tree").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/admin/api/menus/tree").param("siteId", "").with(admin()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/admin/api/menus/tree").param("siteId", "abc").with(admin()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/admin/api/menus/tree").param("siteId", "999999").with(admin()))
                .andExpect(status().isNotFound());
        // 생성·구조 반영 본문의 siteId 누락은 Bean Validation 400
        mockMvc.perform(post("/admin/api/menus").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuName\":\"SMT_nosite\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/admin/api/menus").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteId\":999999,\"menuName\":\"SMT_nosite2\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("MANAGER는 사이트 API·메뉴 트리 모두 403 — 사이트 관리와 모든 사이트 메뉴 편집은 ADMIN 전용")
    void managerIsForbidden() throws Exception {
        mockMvc.perform(get("/admin/api/sites").with(principal(Role.ROLE_MANAGER))).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/api/sites").with(principal(Role.ROLE_MANAGER)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteKey\":\"smt-mgr\",\"siteName\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/api/menus/tree").param("siteId", "2").with(principal(Role.ROLE_MANAGER))).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site WHERE site_key = 'smt-mgr'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("사이트 목록: 시드 2행과 메뉴 수·시드 여부가 응답된다")
    void siteListShowsSeedsAndCounts() throws Exception {
        long siteId = createSite("list", true);
        createMenuOk(siteId, "l-1", "/x", null);

        JsonNode sites = json(mockMvc.perform(get("/admin/api/sites").with(admin())).andExpect(status().isOk()).andReturn());
        assertThat(sites.get(0).path("siteKey").asText()).isEqualTo("admin");
        assertThat(sites.get(0).path("seed").asBoolean()).isTrue();
        assertThat(sites.get(0).path("allowExternalLink").asBoolean()).isFalse();
        assertThat(sites.get(1).path("siteKey").asText()).isEqualTo("main");
        JsonNode created = null;
        for (JsonNode site : sites) {
            if (site.path("id").asLong() == siteId) {
                created = site;
            }
        }
        assertThat(created).isNotNull();
        assertThat(created.path("menuCount").asLong()).isEqualTo(1);
        assertThat(created.path("seed").asBoolean()).isFalse();
    }
}
