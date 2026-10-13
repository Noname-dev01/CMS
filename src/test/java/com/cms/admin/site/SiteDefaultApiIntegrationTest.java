package com.cms.admin.site;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.config.auth.CustomUserDetails;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기본 공개 사이트 변경 API를 실제 스택으로 따라간다(PLAN-site-menu.md PR ② P2-5): 지정 → 목록의 기본 표시 → {@code /}의 302 목적지 변경 → 원복,
 * 기본 사이트의 비활성화·삭제 거부(409), {@code false}·관리자 타입·비활성 거부(400), 권한(MANAGER 403·익명 401), 감사 로그.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class SiteDefaultApiIntegrationTest extends MariaDbContainerSupport {

    private static final String KEY = "sda-";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        resetDefault();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM site WHERE site_key LIKE ?", KEY + "%");
        resetDefault();
    }

    private void resetDefault() {
        jdbc.update("UPDATE site SET default_yn = CASE WHEN id = 2 THEN 1 ELSE 0 END, use_yn = 1 WHERE id <= 2");
    }

    private RequestPostProcessor principal(Role role) {
        Member member = Member.builder().id(1L).userId("u01").userName("u01").email("u01@example.com")
                .userType(role).status(MemberStatus.ACTIVE).build();
        CustomUserDetails details = new CustomUserDetails(member);
        return authentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long createSite(String suffix) throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/api/sites").with(principal(Role.ROLE_ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteKey\":\"" + KEY + suffix + "\",\"siteName\":\"SDA " + suffix + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json(result).path("id").asLong();
    }

    private MvcResult patchSite(long id, String body) throws Exception {
        return mockMvc.perform(patch("/admin/api/sites/" + id).with(principal(Role.ROLE_ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private String rootLocation() throws Exception {
        return mockMvc.perform(get("/").with(request -> {
            request.setRemoteAddr("10.99.7." + (1 + (int) (System.nanoTime() % 200)));
            return request;
        })).andReturn().getResponse().getHeader("Location");
    }

    @Test
    @DisplayName("골든 패스: 새 사이트를 기본으로 지정 → 응답·목록에 기본 표시 → / 의 이동 대상이 바뀜 → main으로 원복")
    void makeDefault_roundTrip() throws Exception {
        long news = createSite("news");
        assertThat(rootLocation()).isEqualTo("/main");

        MvcResult saved = patchSite(news, "{\"defaultSite\":true}");
        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(saved).path("defaultSite").asBoolean()).isTrue();
        assertThat(rootLocation()).isEqualTo("/" + KEY + "news");

        mockMvc.perform(get("/admin/api/sites").with(principal(Role.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.siteKey=='main')].defaultSite").value(false))
                .andExpect(jsonPath("$[?(@.siteKey=='" + KEY + "news')].defaultSite").value(true));

        assertThat(patchSite(2, "{\"defaultSite\":true}").getResponse().getStatus()).isEqualTo(200);
        assertThat(rootLocation()).isEqualTo("/main");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site WHERE default_yn = 1", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("기본 사이트는 비활성화·삭제할 수 없다 (409) — 시드가 아닌 사이트도 기본이면 막힌다")
    void defaultSite_cannotBeDisabledOrDeleted() throws Exception {
        long news = createSite("keep");
        patchSite(news, "{\"defaultSite\":true}");

        assertThat(patchSite(news, "{\"useYn\":false}").getResponse().getStatus()).isEqualTo(409);
        mockMvc.perform(delete("/admin/api/sites/" + news).with(principal(Role.ROLE_ADMIN)).with(csrf())).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT use_yn FROM site WHERE id = ?", Boolean.class, news)).isTrue();

        // 기본이 아니게 된 뒤에는 비활성화·삭제할 수 있다(시드 보호 대상이 아닌 사이트).
        patchSite(2, "{\"defaultSite\":true}");
        assertThat(patchSite(news, "{\"useYn\":false}").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("거부: defaultSite=false, 관리자 타입, 비활성 사이트, 없는 사이트 — 기존 기본은 그대로")
    void rejections() throws Exception {
        long off = createSite("off");
        patchSite(off, "{\"useYn\":false}");

        assertThat(patchSite(2, "{\"defaultSite\":false}").getResponse().getStatus()).isEqualTo(400);
        assertThat(patchSite(1, "{\"defaultSite\":true}").getResponse().getStatus()).as("관리자 사이트").isEqualTo(400);
        assertThat(patchSite(off, "{\"defaultSite\":true}").getResponse().getStatus()).as("비활성").isEqualTo(400);
        assertThat(patchSite(off, "{\"defaultSite\":true,\"useYn\":false}").getResponse().getStatus()).as("복합 요청").isEqualTo(400);
        assertThat(patchSite(999999, "{\"defaultSite\":true}").getResponse().getStatus()).isEqualTo(404);
        assertThat(rootLocation()).isEqualTo("/main");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site WHERE default_yn = 1 AND id = 2", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("권한: MANAGER는 403, 익명은 401 — 기본 사이트는 ADMIN만 바꾼다")
    void permissions() throws Exception {
        long news = createSite("perm");
        mockMvc.perform(patch("/admin/api/sites/" + news).with(principal(Role.ROLE_MANAGER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"defaultSite\":true}")).andExpect(status().isForbidden());
        mockMvc.perform(patch("/admin/api/sites/" + news).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"defaultSite\":true}")).andExpect(status().isUnauthorized());
        assertThat(rootLocation()).isEqualTo("/main");
    }

    @Test
    @DisplayName("감사: 기본 지정은 SITE_UPDATE로 기록된다 (대상 사이트 ID)")
    void audited() throws Exception {
        long news = createSite("audit");
        patchSite(news, "{\"defaultSite\":true}");
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_action_log WHERE action_type = 'SITE_UPDATE' AND target_id = ?", Long.class, String.valueOf(news));
        assertThat(rows).isGreaterThanOrEqualTo(1L);
    }
}
