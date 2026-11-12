package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 상태 유지(remember-me) 제거(PLAN-remember-me-removal.md 실패 모드 1·2) — 실제 SecurityConfig·폼 로그인·MariaDB.
 * 쿠키 자동 로그인 경로가 남지 않았는지를 필터 체인 구조로 고정하고, 로그인 폼이 상태 유지를 요청해도 쿠키가 발급되지 않음을 본다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class RememberMeRemovalIntegrationTest extends MariaDbContainerSupport {

    private static final String PASSWORD = "Remember-Removal-Pass-2026!";

    @Autowired MockMvc mvc;
    @Autowired List<SecurityFilterChain> filterChains;
    @Autowired MemberRepository memberRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        TestMembers.delete(jdbc, created);
    }

    private Member member() {
        Member saved = TestMembers.save(memberRepository, "rmr", Role.ROLE_ADMIN);
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), saved.getId());
        created.add(saved.getId());
        return saved;
    }

    @Test
    @DisplayName("보안 필터 체인에 RememberMeAuthenticationFilter가 없다 — 쿠키 자동 로그인 경로가 남지 않았다(주 고정)")
    void filterChainHasNoRememberMeFilter() {
        assertThat(filterChains).isNotEmpty();
        for (SecurityFilterChain chain : filterChains) {
            List<Filter> filters = chain.getFilters();
            assertThat(filters).noneMatch(RememberMeAuthenticationFilter.class::isInstance);
        }
    }

    @Test
    @DisplayName("폼 로그인에 remember-me=on을 보내도 /admin으로 로그인될 뿐 remember-me 쿠키는 발급되지 않는다")
    void loginWithRememberMeParam_issuesNoCookie() throws Exception {
        Member m = member();

        MvcResult result = mvc.perform(post("/admin/login")
                        .param("username", m.getUserId()).param("password", PASSWORD).param("remember-me", "on")
                        .with(csrf()))
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/admin");
        assertThat(Arrays.stream(result.getResponse().getCookies()).map(Cookie::getName)).doesNotContain("remember-me");
    }

    @Test
    @DisplayName("로그인 화면에 로그인 상태 유지 입력(name=remember-me)이 없다")
    void loginPage_hasNoRememberMeInput() throws Exception {
        String html = mvc.perform(get("/admin/login")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("name=\"remember-me\"").doesNotContain("로그인 상태 유지");
    }

    @Test
    @DisplayName("보조 회귀: 세션 없이 remember-me 쿠키만 보낸 요청은 미인증이다(API 401 JSON·페이지 로그인 302) — 임의 쿠키라 제거 전에도 통과할 수 있다")
    void cookieOnlyRequest_isUnauthenticated() throws Exception {
        Cookie stale = new Cookie("remember-me", "c2VyaWVzOnRva2Vu");

        mvc.perform(get("/admin/api/members/me").cookie(stale))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        MvcResult page = mvc.perform(get("/admin").cookie(stale)).andExpect(status().isFound()).andReturn();
        assertThat(page.getResponse().getHeader("Location")).endsWith("/admin/login");
    }
}
