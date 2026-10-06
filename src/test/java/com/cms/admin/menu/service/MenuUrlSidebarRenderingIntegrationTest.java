package com.cms.admin.menu.service;

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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 저장 검증 도입 전에 DB에 들어간 위험한 메뉴 URL이 사이드바에서 어떻게 그려지는지 실제 Thymeleaf 렌더링으로 검증한다.
 * 특히 {@code javascript:}는 {@code @{...}}가 예외를 던져 모든 {@code /admin} 페이지가 500이 되던 문제의 회귀 시험이다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class MenuUrlSidebarRenderingIntegrationTest extends MariaDbContainerSupport {

    private static final String PREFIX = "URLTEST_";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM menu WHERE menu_name LIKE ?", PREFIX + "%");
    }

    private RequestPostProcessor admin() {
        Member member = Member.builder().id(1L).userId("u01").userName("u01").email("u01@example.com")
                .userType(Role.ROLE_ADMIN).status(MemberStatus.ACTIVE).build();
        CustomUserDetails details = new CustomUserDetails(member);
        return authentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private void insertMenu(String name, String url, int ord) {
        jdbc.update("INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, ord, up_menu_no, create_date, update_date) "
                + "VALUES (?, ?, 'fas fa-fw fa-circle', 1, ?, NULL, NOW(), NOW())", PREFIX + name, url, ord);
    }

    @Test
    @DisplayName("DB에 위험한 URL이 있어도 /admin은 200이고 href는 '#'이며, 외부 http(s)는 새 탭·noopener로 그려진다")
    void unsafeStoredUrls_neverReachHref() throws Exception {
        insertMenu("js", "javascript:alert(1)", 9001);
        insertMenu("slashes", "//evil.example/x", 9002);
        insertMenu("data", "data:text/html,x", 9003);
        insertMenu("relative", "admin/rel", 9004);
        insertMenu("external", "https://example.com/docs", 9005);

        String html = mockMvc.perform(get("/admin").with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("javascript:alert").doesNotContain("evil.example")
                .doesNotContain("data:text/html").doesNotContain("href=\"admin/rel\"");
        for (String name : new String[]{"js", "slashes", "data", "relative"}) {
            assertThat(anchorOf(html, PREFIX + name)).contains("href=\"#\"").doesNotContain("target=");
        }
        assertThat(anchorOf(html, PREFIX + "external"))
                .contains("href=\"https://example.com/docs\"")
                .contains("target=\"_blank\"")
                .contains("rel=\"noopener noreferrer\"");
    }

    @Test
    @DisplayName("같은 출처 경로 메뉴는 target·rel 없이 그대로 그려진다")
    void sameOriginPath_hasNoTarget() throws Exception {
        insertMenu("path", "/admin/menu/manage?x=1", 9001);

        String html = mockMvc.perform(get("/admin").with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(anchorOf(html, PREFIX + "path")).contains("href=\"/admin/menu/manage?x=1\"")
                .doesNotContain("target=").doesNotContain("rel=");
    }

    @Test
    @DisplayName("실제 스택에서 위험 URL로 메뉴를 만들 수 없다(400), 외부 http(s)와 경로는 201")
    void createApi_rejectsUnsafeUrl() throws Exception {
        mockMvc.perform(post("/admin/api/menus").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuName\":\"" + PREFIX + "bad\",\"menuUrl\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM menu WHERE menu_name = ?", Integer.class, PREFIX + "bad")).isZero();

        mockMvc.perform(post("/admin/api/menus").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuName\":\"" + PREFIX + "ext\",\"menuUrl\":\"https://example.com/x\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/admin/api/menus").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuName\":\"" + PREFIX + "blank\",\"menuUrl\":\"  \"}"))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT menu_url FROM menu WHERE menu_name = ?", String.class, PREFIX + "blank")).isNull();
    }

    /** 메뉴 이름이 든 {@code <a ...>} 한 덩어리(여는 태그부터 이름까지)를 잘라낸다. */
    private static String anchorOf(String html, String menuName) {
        int nameAt = html.indexOf(">" + menuName + "<");
        assertThat(nameAt).as("사이드바에 %s가 그려져야 한다", menuName).isGreaterThan(0);
        int anchorStart = html.lastIndexOf("<a ", nameAt);
        return html.substring(anchorStart, nameAt);
    }
}
