package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 민감 작업 재인증(PLAN-sensitive-reauthentication.md) — 실제 SecurityConfig·폼 로그인·쿠키 자동 로그인·MariaDB.
 * 쿠키 자동 로그인 세션(remember-me 인증)은 계정·권한·세션 관리와 내 정보·비밀번호 변경을 할 수 없고
 * 403 {@code REAUTHENTICATION_REQUIRED}를 받는다. 비밀번호로 다시 로그인하면 같은 작업이 된다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class SensitiveReauthenticationIntegrationTest extends MariaDbContainerSupport {

    private static final String PASSWORD = "Reauth-Test-Pass-2026!";
    private static final String NEW_PASSWORD = "Reauth-New-Password-2026-Long!";
    private static final String ME = "/admin/api/members/me";
    private static final String REAUTH = "REAUTHENTICATION_REQUIRED";

    @Autowired MockMvc mvc;
    @Autowired MemberRepository memberRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : created) {
            jdbc.update("DELETE FROM persistent_logins WHERE username = (SELECT user_id FROM member WHERE id = ?)", id);
        }
        TestMembers.delete(jdbc, created);
    }

    // ---------------------------------------------------------------- 도우미

    private Member member(Role role) {
        Member saved = TestMembers.save(memberRepository, "ra", role);
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), saved.getId());
        created.add(saved.getId());
        return memberRepository.findById(saved.getId()).orElseThrow();
    }

    /** 폼 로그인(로그인 상태 유지 체크) — 세션과 remember-me 쿠키. */
    private record Login(MockHttpSession session, Cookie cookie) {
    }

    private Login formLogin(Member m, MockHttpSession existingSession, Cookie existingCookie) throws Exception {
        MockHttpServletRequestBuilder req = post("/admin/login")
                .param("username", m.getUserId()).param("password", PASSWORD).param("remember-me", "on").with(csrf());
        if (existingSession != null) {
            req.session(existingSession);
        }
        if (existingCookie != null) {
            req.cookie(existingCookie);
        }
        MvcResult result = mvc.perform(req).andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/admin");
        return new Login((MockHttpSession) result.getRequest().getSession(false), lastRememberMeCookie(result));
    }

    /** 브라우저 재시작 모사: 세션 없이 쿠키만으로 GET → 자동 로그인 세션과 교체된 쿠키. */
    private Login rememberMeSession(Member m) throws Exception {
        Login form = formLogin(m, null, null);
        MvcResult result = mvc.perform(get(ME).cookie(form.cookie())).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        SecurityContext context = (SecurityContext) session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(context.getAuthentication()).isInstanceOf(RememberMeAuthenticationToken.class);
        return new Login(session, lastRememberMeCookie(result));
    }

    private static Cookie lastRememberMeCookie(MvcResult result) {
        Cookie last = null;
        for (Cookie cookie : result.getResponse().getCookies()) {
            if ("remember-me".equals(cookie.getName())) {
                last = cookie;
            }
        }
        return last;
    }

    /** 민감 작업 7종 — 대상 회원(target)·대상 세션(targetSession)을 향한 요청. 본문은 성공했다면 상태를 바꿨을 값이다. */
    private Map<String, MockHttpServletRequestBuilder> sensitiveRequests(Member target, MockHttpSession targetSession,
                                                                         boolean correctCurrentPassword) {
        Map<String, MockHttpServletRequestBuilder> requests = new LinkedHashMap<>();
        requests.put("계정 생성", post("/admin/api/members").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"backdoor" + System.nanoTime() % 100000 + "\",\"pwd\":\"" + NEW_PASSWORD
                        + "\",\"userName\":\"백도어\",\"email\":\"backdoor" + System.nanoTime() + "@reauth.example\",\"userType\":\"ROLE_ADMIN\"}"));
        requests.put("타 계정 수정", patch("/admin/api/members/" + target.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userType\":\"ROLE_ADMIN\",\"status\":\"LOCKED\",\"userName\":\"바뀜\"}"));
        requests.put("권한 교체", put("/admin/api/members/" + target.getId() + "/permissions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"grants\":[{\"feature\":\"BANNER\",\"action\":\"READ\"}],\"boardGrants\":[]}"));
        requests.put("세션 하나 만료", delete("/admin/api/sessions/" + AdminSessionService.handleOf(targetSession.getId())));
        requests.put("회원 세션 전부 만료", delete("/admin/api/sessions").param("memberId", String.valueOf(target.getId())));
        requests.put("내 정보 수정", patch(ME).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userName\":\"바뀐이름\",\"email\":\"changed" + System.nanoTime() + "@reauth.example\"}"));
        requests.put("비밀번호 변경", patch(ME + "/password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + (correctCurrentPassword ? PASSWORD : "wrong-" + PASSWORD)
                        + "\",\"newPassword\":\"" + NEW_PASSWORD + "\",\"confirmPassword\":\"" + NEW_PASSWORD + "\"}"));
        return requests;
    }

    /** 민감 작업이 바꿀 수 있는 대상 상태 — 거부 전후로 같아야 한다. */
    private Map<String, Object> snapshot(Member actor, Member target, MockHttpSession targetSession) throws Exception {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("members", jdbc.queryForObject("SELECT COUNT(*) FROM member", Long.class));
        s.put("actor", jdbc.queryForMap("SELECT user_type, status, email, user_name, pwd FROM member WHERE id = ?", actor.getId()));
        s.put("target", jdbc.queryForMap(
                "SELECT user_type, status, email, user_name, pwd, permission_version FROM member WHERE id = ?", target.getId()));
        s.put("targetGrants", jdbc.queryForObject("SELECT COUNT(*) FROM member_permission WHERE member_id = ?", Long.class, target.getId()));
        s.put("targetTokens", jdbc.queryForObject("SELECT COUNT(*) FROM persistent_logins WHERE username = ?", Long.class, target.getUserId()));
        s.put("actorTokens", jdbc.queryForObject("SELECT COUNT(*) FROM persistent_logins WHERE username = ?", Long.class, actor.getUserId()));
        s.put("actionLogs", jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log", Long.class));
        s.put("targetSessionAlive", mvc.perform(get(ME).session(targetSession)).andReturn().getResponse().getStatus());
        return s;
    }

    // ---------------------------------------------------------------- 성공 기준 1·4-1 (ⓐ 복원 세션 → 403)

    @Test
    @DisplayName("쿠키로 복원한 ADMIN 세션의 민감 작업 7종은 403 REAUTHENTICATION_REQUIRED이고 대상 상태가 바뀌지 않는다")
    void rememberMeSession_sensitiveApis_requireReauthentication() throws Exception {
        Member admin = member(Role.ROLE_ADMIN);
        Member target = member(Role.ROLE_MANAGER);
        Login restored = rememberMeSession(admin);
        Login targetLogin = formLogin(target, null, null);

        for (boolean correct : new boolean[]{true, false}) {
            for (Map.Entry<String, MockHttpServletRequestBuilder> e : sensitiveRequests(target, targetLogin.session(), correct).entrySet()) {
                Map<String, Object> before = snapshot(admin, target, targetLogin.session());
                mvc.perform(e.getValue().session(restored.session()).with(csrf()))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value(REAUTH))
                        .andExpect(jsonPath("$.message").isNotEmpty());
                assertThat(snapshot(admin, target, targetLogin.session())).as(e.getKey()).isEqualTo(before);
            }
        }
        // 거부 뒤에도 같은 세션의 비민감 작업은 그대로다
        mvc.perform(get("/admin/api/members").session(restored.session())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("검증에 실패할 본문이어도 재인증 거부가 먼저다(본문 바인딩·서비스 전) — 400이 아니라 403")
    void rememberMeSession_invalidBody_isStillReauthentication() throws Exception {
        Member admin = member(Role.ROLE_ADMIN);
        Login restored = rememberMeSession(admin);

        mvc.perform(post("/admin/api/members").session(restored.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(REAUTH));
        mvc.perform(delete("/admin/api/sessions/not-a-handle").session(restored.session()).with(csrf()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(REAUTH));
    }

    @Test
    @DisplayName("Accept: text/html이어도 재인증 거부는 JSON 403이다")
    void rememberMeSession_htmlAccept_isJson403() throws Exception {
        Member manager = member(Role.ROLE_MANAGER);
        Login restored = rememberMeSession(manager);

        mvc.perform(patch(ME).session(restored.session()).with(csrf()).accept(MediaType.TEXT_HTML)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userName\":\"x\",\"email\":\"" + manager.getEmail() + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(REAUTH));
    }

    // ---------------------------------------------------------------- 성공 기준 1 ⓑ (쿠키만으로 바로 쓰기 → 기존 401)

    @Test
    @DisplayName("세션 없이 쿠키만으로 바로 쓰면 CSRF가 자동 로그인보다 먼저라 기존 401이고 토큰·대상 상태가 그대로다")
    void cookieOnlyWriteWithoutSession_isUnauthorizedAndKeepsToken() throws Exception {
        Member manager = member(Role.ROLE_MANAGER);
        Login form = formLogin(manager, null, null);
        Map<String, Object> tokenBefore = jdbc.queryForMap(
                "SELECT series, token, last_used FROM persistent_logins WHERE username = ?", manager.getUserId());
        Map<String, Object> rowBefore = jdbc.queryForMap("SELECT email, user_name FROM member WHERE id = ?", manager.getId());

        mvc.perform(patch(ME).cookie(form.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"x\",\"email\":\"cookieonly" + System.nanoTime() + "@reauth.example\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(jdbc.queryForMap("SELECT series, token, last_used FROM persistent_logins WHERE username = ?", manager.getUserId()))
                .isEqualTo(tokenBefore);
        assertThat(jdbc.queryForMap("SELECT email, user_name FROM member WHERE id = ?", manager.getId())).isEqualTo(rowBefore);
    }

    // ---------------------------------------------------------------- 성공 기준 2·3·5

    @Test
    @DisplayName("폼 로그인 세션은 같은 민감 작업을 그대로 할 수 있다")
    void formLoginSession_sensitiveApisSucceed() throws Exception {
        Member admin = member(Role.ROLE_ADMIN);
        Member target = member(Role.ROLE_MANAGER);
        Login form = formLogin(admin, null, null);

        mvc.perform(patch("/admin/api/members/" + target.getId()).session(form.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userName\":\"폼로그인수정\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch(ME).session(form.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"내이름\",\"email\":\"" + admin.getEmail() + "\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT user_name FROM member WHERE id = ?", String.class, target.getId())).isEqualTo("폼로그인수정");
        assertThat(jdbc.queryForObject("SELECT user_name FROM member WHERE id = ?", String.class, admin.getId())).isEqualTo("내이름");
    }

    @Test
    @DisplayName("쿠키로 복원한 세션에서 같은 브라우저로 비밀번호 로그인하면(재인증) 거부됐던 작업이 된다")
    void reLoginFromRememberMeSession_allowsSensitiveApi() throws Exception {
        Member manager = member(Role.ROLE_MANAGER);
        Login restored = rememberMeSession(manager);
        String body = "{\"userName\":\"재인증후\",\"email\":\"" + manager.getEmail() + "\"}";

        mvc.perform(patch(ME).session(restored.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(REAUTH));

        Login again = formLogin(manager, restored.session(), restored.cookie());
        SecurityContext context = (SecurityContext) again.session().getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(context.getAuthentication()).isInstanceOf(UsernamePasswordAuthenticationToken.class);

        mvc.perform(patch(ME).session(again.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT user_name FROM member WHERE id = ?", String.class, manager.getId())).isEqualTo("재인증후");
    }

    @Test
    @DisplayName("역할 부족이 먼저다: 쿠키로 복원한 MANAGER의 ADMIN 전용 민감 작업은 기존 ACCESS_DENIED, 본인 정보 수정은 재인증 필요")
    void rememberMeManager_roleCheckComesFirst() throws Exception {
        Member manager = member(Role.ROLE_MANAGER);
        Login restored = rememberMeSession(manager);

        mvc.perform(post("/admin/api/members").session(restored.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(delete("/admin/api/sessions").param("memberId", "1").session(restored.session()).with(csrf()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(patch(ME).session(restored.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"x\",\"email\":\"" + manager.getEmail() + "\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(REAUTH));
    }
}
