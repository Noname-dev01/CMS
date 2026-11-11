package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.AdminMyPasswordChangeRequest;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.service.AdminMemberService;
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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.StringJoiner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "로그인 상태 유지"(remember-me 영속 토큰) end-to-end — 실제 SecurityConfig·폼 로그인·세션 레지스트리·MariaDB(이슈 #222,
 * PLAN-remember-me-login.md F1~F5). 경합은 {@link RememberMeTokenRepository} 스파이로 "원하는 지점"에 다른 작업을 끼워 결정적으로 만든다.
 * DB 단언은 요청이 끝난 뒤 별도 {@link JdbcTemplate} 조회로 한다.
 *
 * <p>쿠키 자동 로그인은 매번 토큰을 교체하므로 다음 자동 로그인에는 <b>응답의 새 쿠키</b>를 써야 한다({@link AutoLogin#cookie()}).
 * 한계: MockMvc 기반이라 브라우저 재시작·실제 세션 파괴는 실기(Playwright)로 확인한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class RememberMeLoginIntegrationTest extends MariaDbContainerSupport {

    private static final String PASSWORD = "Remember-Me-Pass-2026!";
    private static final String ME = "/admin/api/members/me";
    private static final int SEVEN_DAYS = 7 * 24 * 60 * 60;

    @Autowired MockMvc mvc;
    @Autowired MemberRepository memberRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionRegistry sessionRegistry;
    @Autowired AdminMemberService adminMemberService;
    @Autowired com.cms.admin.session.service.AdminSessionManageService adminSessionManageService;
    @MockitoSpyBean RememberMeTokenRepository tokens;
    @MockitoSpyBean LoginVisitRecorder visitRecorder;

    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        reset(tokens, visitRecorder);
        for (Long id : created) {
            jdbc.update("DELETE FROM persistent_logins WHERE username = (SELECT user_id FROM member WHERE id = ?)", id);
        }
        TestMembers.delete(jdbc, created);
    }

    // ---------------------------------------------------------------- 도우미

    private Member member(Role role) {
        Member saved = TestMembers.save(memberRepository, "rm", role);
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), saved.getId());
        created.add(saved.getId());
        return memberRepository.findById(saved.getId()).orElseThrow();
    }

    /** 폼 로그인 결과 — 세션과(있으면) remember-me 쿠키. */
    record Login(MockHttpSession session, Cookie cookie, MvcResult result) {
    }

    /** 쿠키 자동 로그인 결과 — 새 세션과 교체된 쿠키. */
    record AutoLogin(MockHttpSession session, Cookie cookie, MvcResult result) {
    }

    private Login login(Member m, boolean remember) throws Exception {
        return login(m, remember, null, null);
    }

    private Login login(Member m, boolean remember, MockHttpSession existingSession, Cookie existingCookie) throws Exception {
        MockHttpServletRequestBuilder req = post("/admin/login")
                .param("username", m.getUserId()).param("password", PASSWORD).with(csrf());
        if (remember) {
            req.param("remember-me", "on");
        }
        if (existingSession != null) {
            req.session(existingSession);
        }
        if (existingCookie != null) {
            req.cookie(existingCookie);
        }
        MvcResult result = mvc.perform(req).andReturn();
        return new Login((MockHttpSession) result.getRequest().getSession(false), lastRememberMeCookie(result), result);
    }

    /** 세션 없이 쿠키만으로 API를 부른다(브라우저 재시작). */
    private AutoLogin cookieOnly(Cookie cookie) throws Exception {
        MvcResult result = mvc.perform(get(ME).cookie(cookie)).andReturn();
        Cookie rotated = lastRememberMeCookie(result);
        return new AutoLogin((MockHttpSession) result.getRequest().getSession(false), rotated, result);
    }

    /**
     * 응답의 마지막 remember-me 쿠키 — 한 응답에 교체 쿠키 뒤 취소 쿠키가 함께 실릴 수 있고(예: 토큰 교체 후 상태 거부),
     * 브라우저는 Set-Cookie를 순서대로 적용해 마지막 값이 남는다. {@code getCookie(name)}은 첫 번째를 돌려줘 쓰지 않는다.
     */
    private static Cookie lastRememberMeCookie(MvcResult result) {
        Cookie last = null;
        for (Cookie cookie : result.getResponse().getCookies()) {
            if ("remember-me".equals(cookie.getName())) {
                last = cookie;
            }
        }
        return last;
    }

    private int meStatus(MockHttpSession session) throws Exception {
        return mvc.perform(get(ME).session(session)).andReturn().getResponse().getStatus();
    }

    private int rows(Member m) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM persistent_logins WHERE username = ?", Integer.class, m.getUserId());
    }

    private boolean seriesExists(String series) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM persistent_logins WHERE series = ?", Integer.class, series) > 0;
    }

    private int visits(Member m) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM visit_log WHERE visitor_user_id = ?", Integer.class, m.getUserId());
    }

    private static String[] decode(Cookie cookie) {
        String value = cookie.getValue();
        while (value.length() % 4 != 0) {
            value = value + "=";
        }
        String[] parts = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8).split(":");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = URLDecoder.decode(parts[i], StandardCharsets.UTF_8);
        }
        return parts;
    }

    private static Cookie encode(String series, String token) {
        StringJoiner joiner = new StringJoiner(":");
        joiner.add(URLEncoder.encode(series, StandardCharsets.UTF_8)).add(URLEncoder.encode(token, StandardCharsets.UTF_8));
        String value = Base64.getEncoder().encodeToString(joiner.toString().getBytes(StandardCharsets.UTF_8));
        return new Cookie("remember-me", value.replaceAll("=+$", ""));
    }

    private static String series(Cookie cookie) {
        return decode(cookie)[0];
    }

    private String principalSeries(MockHttpSession session) {
        SessionInformation info = sessionRegistry.getSessionInformation(session.getId());
        assertThat(info).as("세션이 레지스트리에 등록돼야 한다").isNotNull();
        return ((CustomUserDetails) info.getPrincipal()).getRememberMeSeries();
    }

    private static boolean cancelled(Cookie cookie) {
        return cookie != null && cookie.getMaxAge() == 0;
    }

    private String handle(MockHttpSession session) {
        return AdminSessionService.handleOf(session.getId());
    }

    // ---------------------------------------------------------------- 기본 동작 (F5④⑤, 성공 기준 1·2·6)

    @Test
    @DisplayName("체크하고 로그인하면 HttpOnly·7일 쿠키와 행 1개, 쿠키만으로 인증되고 자동 로그인 세션이 레지스트리에 시리즈와 함께 등록되며 방문 1건이 된다")
    void rememberedLogin_autoLoginsWithCookieOnly() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        Login login = login(m, true);

        assertThat(login.result().getResponse().getRedirectedUrl()).isEqualTo("/admin");
        assertThat(login.cookie()).isNotNull();
        assertThat(login.cookie().isHttpOnly()).isTrue();
        assertThat(login.cookie().getMaxAge()).isEqualTo(SEVEN_DAYS);
        assertThat(rows(m)).isEqualTo(1);
        assertThat(principalSeries(login.session())).isEqualTo(series(login.cookie()));

        int visitsBefore = visits(m);
        AutoLogin auto = cookieOnly(login.cookie());

        assertThat(auto.result().getResponse().getStatus()).isEqualTo(200);
        assertThat(auto.cookie()).as("자동 로그인마다 토큰이 교체된다").isNotNull();
        assertThat(series(auto.cookie())).isEqualTo(series(login.cookie()));
        assertThat(principalSeries(auto.session())).isEqualTo(series(login.cookie()));
        assertThat(visits(m)).isEqualTo(visitsBefore + 1);
        assertThat(meStatus(auto.session())).isEqualTo(200);
    }

    @Test
    @DisplayName("체크 없이 로그인하면 remember-me 쿠키도 행도 없다(기존 동작)")
    void plainLogin_hasNoRememberMe() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        Login login = login(m, false);

        assertThat(login.result().getResponse().getRedirectedUrl()).isEqualTo("/admin");
        assertThat(login.cookie()).isNull();
        assertThat(rows(m)).isZero();
        assertThat(principalSeries(login.session())).isNull();
    }

    // ---------------------------------------------------------------- F1 자동 폐기(이벤트) — 같은 트랜잭션

    @Test
    @DisplayName("F1: ADMIN이 역할을 바꾸면 대상의 토큰이 모두 지워져 쿠키로 다시 들어오지 못한다")
    void roleChange_revokesAllTokens() throws Exception {
        Member admin = member(Role.ROLE_ADMIN);
        Member target = member(Role.ROLE_MANAGER);
        Login adminLogin = login(admin, false);
        Login device1 = login(target, true);
        Login device2 = login(target, true);
        assertThat(rows(target)).isEqualTo(2);

        mvc.perform(patch("/admin/api/members/" + target.getId()).session(adminLogin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userType\":\"ROLE_ADMIN\"}"))
                .andExpect(status().isOk());

        assertThat(rows(target)).isZero();
        assertThat(cookieOnly(device1.cookie()).result().getResponse().getStatus()).isEqualTo(401);
        assertThat(cookieOnly(device2.cookie()).result().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("F1: 토큰 삭제가 실패하면 역할 변경도 롤백된다(같은 트랜잭션 — fail-closed)")
    void roleChange_rollsBackWhenTokenRevokeFails() throws Exception {
        Member admin = member(Role.ROLE_ADMIN);
        Member target = member(Role.ROLE_MANAGER);
        Login adminLogin = login(admin, false);
        login(target, true);
        doThrow(new IllegalStateException("주입된 삭제 실패")).when(tokens).removeAllForMember(target.getId());

        int status = mvc.perform(patch("/admin/api/members/" + target.getId()).session(adminLogin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userType\":\"ROLE_ADMIN\"}"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isGreaterThanOrEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT user_type FROM member WHERE id = ?", String.class, target.getId())).isEqualTo("ROLE_MANAGER");
        assertThat(rows(target)).isEqualTo(1);
    }

    @Test
    @DisplayName("F1: 본인 비밀번호를 바꾸면 그 회원의 토큰이 모두 지워진다")
    void ownPasswordChange_revokesAllTokens() throws Exception {
        Member m = member(Role.ROLE_MANAGER);
        Login login = login(m, true);
        Login other = login(m, true);

        mvc.perform(patch(ME + "/password").session(login.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"Changed-Password-2026!\",\"confirmPassword\":\"Changed-Password-2026!\"}"))
                .andExpect(status().isOk());

        assertThat(rows(m)).isZero();
        assertThat(cookieOnly(other.cookie()).result().getResponse().getStatus()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- F2 자동 로그인 경합·검증 실패

    @Test
    @DisplayName("F2①: 토큰 갱신 직전에 행이 지워지면(0행 갱신) 익명이고 방문이 늘지 않는다")
    void rowDeletedBeforeUpdate_isAnonymous() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        Login login = login(m, true);
        String series = series(login.cookie());
        int visitsBefore = visits(m);
        doAnswer(inv -> {
            jdbc.update("DELETE FROM persistent_logins WHERE series = ?", series);
            return inv.callRealMethod();
        }).when(tokens).updateToken(anyString(), anyString(), any());

        AutoLogin auto = cookieOnly(login.cookie());

        assertThat(auto.result().getResponse().getStatus()).isEqualTo(401);
        assertThat(visits(m)).isEqualTo(visitsBefore);
    }

    @Test
    @DisplayName("F2②: 토큰 갱신 직후 행이 지워지면 세션 등록 뒤 사후 검증이 거부한다 — 익명, 세션 무효, 방문 미증가")
    void rowDeletedAfterUpdate_isRejectedByVerification() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        Login login = login(m, true);
        String series = series(login.cookie());
        int visitsBefore = visits(m);
        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            jdbc.update("DELETE FROM persistent_logins WHERE series = ?", series);
            return result;
        }).when(tokens).updateToken(anyString(), anyString(), any());

        AutoLogin auto = cookieOnly(login.cookie());

        assertThat(auto.result().getResponse().getStatus()).isEqualTo(401);
        // 인증이 든 세션은 무효화됐다. 401 응답 과정에서 요청 캐시가 새 익명 세션을 만들 수 있으므로 "인증이 없다"를 본다.
        if (auto.session() != null && !auto.session().isInvalid()) {
            assertThat(auto.session().getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
            assertThat(meStatus(auto.session())).isEqualTo(401);
        }
        assertThat(cancelled(auto.cookie())).isTrue();
        assertThat(visits(m)).isEqualTo(visitsBefore);
    }

    @Test
    @DisplayName("F2③: 사후 검증 조회가 실패하면 그 요청은 오류지만 그 요청의 세션은 인증을 남기지 않는다")
    void verificationLookupFailure_leavesNoAuthenticatedSession() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        Login login = login(m, true);
        doThrow(new IllegalStateException("주입된 조회 실패")).when(tokens).existsSeries(anyString());
        MockHttpSession session = new MockHttpSession();

        assertThatThrownBy(() -> mvc.perform(get(ME).session(session).cookie(login.cookie())));

        assertThat(session.isInvalid()).as("검증 실패 시 세션을 무효화해야 다음 요청이 검증을 우회하지 못한다").isTrue();
    }

    // ---------------------------------------------------------------- F3 폼 로그인 경합·기존 쿠키 재로그인

    @Test
    @DisplayName("F3①: 새 토큰 발급 직전에 비밀번호가 바뀌면 로그인이 거부되고 방금 발급한 행·쿠키도 정리된다")
    void passwordChangedDuringLogin_cleansIssuedToken() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        doAnswer(inv -> {
            AdminMyPasswordChangeRequest change = new AdminMyPasswordChangeRequest();
            org.springframework.test.util.ReflectionTestUtils.setField(change, "currentPassword", PASSWORD);
            org.springframework.test.util.ReflectionTestUtils.setField(change, "newPassword", "Changed-Password-2026!");
            org.springframework.test.util.ReflectionTestUtils.setField(change, "confirmPassword", "Changed-Password-2026!");
            adminMemberService.changeMyPassword(m.getId(), change);
            return inv.callRealMethod();
        }).when(tokens).createNewToken(any());

        Login login = login(m, true);

        assertThat(login.result().getResponse().getRedirectedUrl()).isEqualTo("/admin/login-error");
        assertThat(rows(m)).isZero();
        assertThat(cancelled(login.cookie())).isTrue();
    }

    @Test
    @DisplayName("F3①-b: 거부된 로그인의 토큰 삭제마저 실패해도 응답에 쓸 수 있는 remember-me 쿠키가 실리지 않는다(쿠키는 검증 통과 뒤에만 발급)")
    void rejectedLoginWithCleanupFailure_neverSendsUsableCookie() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        doAnswer(inv -> {
            AdminMyPasswordChangeRequest change = new AdminMyPasswordChangeRequest();
            org.springframework.test.util.ReflectionTestUtils.setField(change, "currentPassword", PASSWORD);
            org.springframework.test.util.ReflectionTestUtils.setField(change, "newPassword", "Changed-Password-2026!");
            org.springframework.test.util.ReflectionTestUtils.setField(change, "confirmPassword", "Changed-Password-2026!");
            adminMemberService.changeMyPassword(m.getId(), change);
            return inv.callRealMethod();
        }).when(tokens).createNewToken(any());
        doThrow(new IllegalStateException("주입된 삭제 실패")).when(tokens).removeSeries(anyString());

        Login login = login(m, true);

        assertThat(login.result().getResponse().getRedirectedUrl()).isEqualTo("/admin/login-error");
        for (Cookie cookie : login.result().getResponse().getCookies()) {
            if ("remember-me".equals(cookie.getName())) {
                assertThat(cookie.getMaxAge()).as("발급 쿠키가 실리면 안 된다 — 취소만 허용").isZero();
            }
        }
    }

    @Test
    @DisplayName("F3②: 쿠키가 있는 브라우저로 체크 없이 재로그인하면 옛 시리즈가 지워지고 쿠키가 취소된다")
    void reLoginWithoutCheck_dropsOldSeries() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        Login first = login(m, true);

        Login second = login(m, false, null, first.cookie());

        assertThat(seriesExists(series(first.cookie()))).isFalse();
        assertThat(cancelled(second.cookie())).isTrue();
        assertThat(cookieOnly(first.cookie()).result().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("F3③: 체크하고 다른 계정으로 재로그인하면 이전 계정의 이 기기 시리즈가 지워지고 새 계정 시리즈 1개만 남는다")
    void reLoginAsOtherAccount_dropsPreviousDeviceSeries() throws Exception {
        Member a = member(Role.ROLE_ADMIN);
        Member b = member(Role.ROLE_ADMIN);
        Login first = login(a, true);

        Login second = login(b, true, first.session(), first.cookie());

        assertThat(rows(a)).isZero();
        assertThat(rows(b)).isEqualTo(1);
        assertThat(series(second.cookie())).isNotEqualTo(series(first.cookie()));
    }

    @Test
    @DisplayName("F3④: 세션은 둔 채 remember-me 쿠키만 지우고 체크 없이 재로그인해도 옛 시리즈가 지워진다(세션 속성 출처)")
    void reLoginInSameSessionWithoutCookie_dropsSeriesFromSessionAttribute() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        Login first = login(m, true);
        Cookie kept = first.cookie();

        login(m, false, first.session(), null);

        assertThat(seriesExists(series(kept))).isFalse();
        assertThat(cookieOnly(kept).result().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("F3⑤: 재로그인의 옛 시리즈 삭제가 실패하면 그 요청은 오류이고 그 세션은 인증을 남기지 않는다")
    void reLoginCleanupFailure_invalidatesSession() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        Login first = login(m, true);
        doThrow(new IllegalStateException("주입된 삭제 실패")).when(tokens).removeSeries(anyString());

        MockHttpSession session = first.session();
        assertThatThrownBy(() -> login(m, false, session, first.cookie()));

        assertThat(session.isInvalid()).isTrue();
    }

    // ---------------------------------------------------------------- F4 기기 단위 폐기(U4)

    @Test
    @DisplayName("F4①: 기기 1 세션을 단일 만료하면 기기 1 시리즈만 지워지고 기기 2 쿠키는 계속 인증된다")
    void singleExpire_revokesOnlyThatDevice() throws Exception {
        Member admin = member(Role.ROLE_ADMIN);
        Member target = member(Role.ROLE_MANAGER);
        Login adminLogin = login(admin, false);
        Login device1 = login(target, true);
        Login device2 = login(target, true);

        mvc.perform(delete("/admin/api/sessions/" + handle(device1.session())).session(adminLogin.session()).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(seriesExists(series(device1.cookie()))).isFalse();
        assertThat(seriesExists(series(device2.cookie()))).isTrue();
        assertThat(meStatus(device1.session())).isEqualTo(401);
        assertThat(cookieOnly(device1.cookie()).result().getResponse().getStatus()).isEqualTo(401);
        assertThat(cookieOnly(device2.cookie()).result().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("F4②: 단일 만료의 토큰 삭제가 실패하면 세션도 그대로이고 같은 핸들로 재시도하면 폐기된다")
    void singleExpireDeleteFailure_isRetryable() throws Exception {
        Member admin = member(Role.ROLE_ADMIN);
        Member target = member(Role.ROLE_MANAGER);
        Login adminLogin = login(admin, false);
        Login device = login(target, true);
        doThrow(new IllegalStateException("주입된 삭제 실패")).when(tokens).removeSeries(anyString());

        int failed = mvc.perform(delete("/admin/api/sessions/" + handle(device.session())).session(adminLogin.session()).with(csrf()))
                .andReturn().getResponse().getStatus();

        assertThat(failed).isGreaterThanOrEqualTo(500);
        assertThat(meStatus(device.session())).isEqualTo(200);

        reset(tokens);
        mvc.perform(delete("/admin/api/sessions/" + handle(device.session())).session(adminLogin.session()).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(seriesExists(series(device.cookie()))).isFalse();
    }

    @Test
    @DisplayName("F4⑤: 같은 시리즈의 S1(서버에 남은 세션)·S2(쿠키 복원)에서 S1을 단일 만료하면 S2도 끊긴다")
    void singleExpire_endsAllSessionsOfThatDevice() throws Exception {
        Member admin = member(Role.ROLE_ADMIN);
        Member target = member(Role.ROLE_MANAGER);
        Login adminLogin = login(admin, false);
        Login s1 = login(target, true);
        AutoLogin s2 = cookieOnly(s1.cookie());
        assertThat(principalSeries(s2.session())).isEqualTo(principalSeries(s1.session()));

        mvc.perform(delete("/admin/api/sessions/" + handle(s1.session())).session(adminLogin.session()).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(meStatus(s2.session())).isEqualTo(401);
        assertThat(seriesExists(series(s1.cookie()))).isFalse();
    }

    @Test
    @DisplayName("F4⑥: 쿠키 복원 S2가 사후 검증을 통과한 직후 S1이 단일 만료되면 S2의 다음 요청이 거부된다")
    void singleExpireRightAfterVerification_endsRestoredSession() throws Exception {
        Member target = member(Role.ROLE_MANAGER);
        Login s1 = login(target, true);
        doAnswer(inv -> {
            inv.callRealMethod();
            adminSessionManageService.expireSession(handle(s1.session()), null);
            return null;
        }).when(visitRecorder).record(any(), any());

        AutoLogin s2 = cookieOnly(s1.cookie());

        assertThat(s2.result().getResponse().getStatus()).as("검증을 통과한 그 요청 자체는 처리된다").isEqualTo(200);
        assertThat(meStatus(s2.session())).isEqualTo(401);
        assertThat(seriesExists(series(s1.cookie()))).isFalse();
    }

    @Test
    @DisplayName("F4③⑨: 로그아웃은 그 기기만 폐기한다 — 같은 시리즈의 다른 세션도 끊기고 다른 기기는 유지된다")
    void logout_revokesOnlyThatDeviceIncludingSiblingSessions() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        Login s1 = login(m, true);
        AutoLogin s2 = cookieOnly(s1.cookie());
        Login other = login(m, true);

        mvc.perform(post("/admin/logout").session(s2.session()).cookie(s2.cookie()).with(csrf()));

        assertThat(seriesExists(series(s1.cookie()))).isFalse();
        assertThat(meStatus(s1.session())).isEqualTo(401);
        assertThat(seriesExists(series(other.cookie()))).isTrue();
        assertThat(meStatus(other.session())).isEqualTo(200);
    }

    @Test
    @DisplayName("F4④: remember-me 쿠키 없이 세션만으로 로그아웃해도 그 세션의 시리즈가 지워진다")
    void logoutWithoutCookie_revokesSessionSeries() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        Login login = login(m, true);

        mvc.perform(post("/admin/logout").session(login.session()).with(csrf()));

        assertThat(seriesExists(series(login.cookie()))).isFalse();
        assertThat(cookieOnly(login.cookie()).result().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("F4③⑦: 본인 대상 회원 단위 만료는 현재 기기 시리즈만 남기고, 같은 시리즈의 만료된 세션 후속 요청도 그것을 지우지 않는다")
    void memberExpire_keepsCurrentDevice_evenAfterExpiredSiblingRequest() throws Exception {
        // 회원 단위 만료는 재인증이 필요한 민감 작업이라(PLAN-sensitive-reauthentication.md) 호출 세션은 폼 로그인(체크) 세션이고,
        // 같은 기기(같은 시리즈)의 형제 세션은 그 쿠키의 자동 로그인으로 만든다 — 검증 대상(현재 기기 시리즈 보존·형제 만료)은 그대로다.
        Member m = member(Role.ROLE_ADMIN);
        Login current = login(m, true);
        AutoLogin sibling = cookieOnly(current.cookie());
        Login other = login(m, true);

        mvc.perform(delete("/admin/api/sessions").param("memberId", String.valueOf(m.getId())).session(current.session()).with(csrf()))
                .andExpect(status().isOk());

        assertThat(seriesExists(series(other.cookie()))).isFalse();
        assertThat(seriesExists(series(current.cookie()))).isTrue();
        assertThat(meStatus(sibling.session())).as("같은 기기의 형제 세션은 만료").isEqualTo(401);
        assertThat(seriesExists(series(current.cookie()))).as("만료 세션의 후속 요청은 시리즈를 지우지 않는다").isTrue();
        assertThat(meStatus(current.session())).isEqualTo(200);
        assertThat(cookieOnly(sibling.cookie()).result().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("F4⑧: 시리즈의 대소문자만 바꾼 쿠키는 인증되지 않는다")
    void caseAlteredSeries_isRejected() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        Login login = login(m, true);
        String[] parts = decode(login.cookie());
        String flipped = flipCase(parts[0]);
        assertThat(flipped).isNotEqualTo(parts[0]);

        AutoLogin auto = cookieOnly(encode(flipped, parts[1]));

        assertThat(auto.result().getResponse().getStatus()).isEqualTo(401);
    }

    private static String flipCase(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- F5 상태 거부·탈취·인증 출처·403

    @Test
    @DisplayName("F5①: 잠긴 계정의 쿠키는 500이 아니라 익명(API 401)이고 쿠키가 취소된다")
    void lockedAccountCookie_isAnonymous() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        Login login = login(m, true);
        jdbc.update("UPDATE member SET status = ? WHERE id = ?", MemberStatus.DISABLED.name(), m.getId());

        AutoLogin auto = cookieOnly(login.cookie());

        assertThat(auto.result().getResponse().getStatus()).isEqualTo(401);
        assertThat(cancelled(auto.cookie())).isTrue();
    }

    @Test
    @DisplayName("F5②: 교체된 옛 쿠키가 다시 오면(탈취 의심) 500이 아니고 그 회원의 토큰과 이미 만들어진 세션이 모두 끊긴다")
    void theftDetected_revokesTokensAndSessions() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        Login victim = login(m, true);
        AutoLogin attacker = cookieOnly(victim.cookie());
        assertThat(meStatus(attacker.session())).isEqualTo(200);

        AutoLogin replay = cookieOnly(victim.cookie());

        assertThat(replay.result().getResponse().getStatus()).isEqualTo(401);
        assertThat(rows(m)).isZero();
        assertThat(meStatus(attacker.session())).isEqualTo(401);
        assertThat(meStatus(victim.session())).isEqualTo(401);
    }

    @Test
    @DisplayName("F5②-b: 탈취 처리의 토큰 삭제가 실패해도 이미 만들어진 공격자 세션은 끊기고 응답은 500이 아니다")
    void theftDetectedWithDeleteFailure_stillEndsSessions() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        Login victim = login(m, true);
        AutoLogin attacker = cookieOnly(victim.cookie());
        assertThat(meStatus(attacker.session())).isEqualTo(200);
        doThrow(new org.springframework.dao.QueryTimeoutException("주입된 삭제 실패")).when(tokens).removeUserTokens(anyString());

        AutoLogin replay = cookieOnly(victim.cookie());

        assertThat(replay.result().getResponse().getStatus()).isEqualTo(401);
        assertThat(meStatus(attacker.session())).isEqualTo(401);
        // 삭제가 실패해 행이 남아도 공격자의 최신 쿠키로 다시 들어오지 못하고, 다음 시도에서 삭제가 재시도된다.
        assertThat(rows(m)).as("삭제 실패로 행이 남아 있는 상태").isEqualTo(1);
        assertThat(cookieOnly(attacker.cookie()).result().getResponse().getStatus()).isEqualTo(401);
        reset(tokens);
        assertThat(cookieOnly(attacker.cookie()).result().getResponse().getStatus()).isEqualTo(401);
        assertThat(rows(m)).as("재시도로 삭제됐다").isZero();
    }

    @Test
    @DisplayName("F5②-c: 탈취 처리의 선행 세션 만료 ~ 토큰 삭제 사이에 다른 자동 로그인이 세션을 만들어도 그 세션은 끊긴다")
    void theftDetected_sessionCreatedDuringRevokeIsAlsoEnded() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        Login victim = login(m, true);
        AutoLogin attacker = cookieOnly(victim.cookie());
        Cookie attackerCurrent = attacker.cookie();
        MockHttpSession[] raced = new MockHttpSession[1];
        java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(inv -> {
            if (!fired.compareAndSet(false, true)) {
                return inv.callRealMethod(); // 재진입 방지 — 끼운 요청도 삭제 재시도로 이 메서드를 부른다
            }
            // 다른 스레드에서 현재 유효한 쿠키로 자동 로그인을 시도한 뒤 삭제를 진행한다. 그 요청은 차단 목록에 걸리거나,
            // 세션을 등록했다면 삭제 뒤의 두 번째 만료에 걸려야 한다.
            Thread other = new Thread(() -> {
                try {
                    raced[0] = cookieOnly(attackerCurrent).session();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            other.start();
            other.join();
            return inv.callRealMethod();
        }).when(tokens).removeUserTokens(anyString());

        cookieOnly(victim.cookie());

        assertThat(raced[0]).as("경합 요청이 세션을 만들었어야 시험이 의미 있다").isNotNull();
        assertThat(meStatus(raced[0])).isEqualTo(401);
        assertThat(rows(m)).isZero();
    }

    @Test
    @DisplayName("F5②-d: 차단 목록 검사를 통과한 자동 로그인이 멈춘 사이 탈취 처리(삭제 실패)가 끝나면, 재개 후 사후 검증이 거부한다")
    void theftWithDeleteFailureDuringInFlightAutoLogin_isRejectedByVerification() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        Login victim = login(m, true);
        AutoLogin attacker = cookieOnly(victim.cookie());
        Cookie attackerCurrent = attacker.cookie();
        doThrow(new org.springframework.dao.QueryTimeoutException("주입된 삭제 실패")).when(tokens).removeUserTokens(anyString());
        java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(inv -> {
            if (fired.compareAndSet(false, true)) {
                // 요청 A(공격자 최신 쿠키)가 차단 목록 검사를 지나 토큰을 갱신하려는 순간, 요청 B(피해자 옛 쿠키)가 탈취 처리를 끝낸다.
                Thread other = new Thread(() -> {
                    try {
                        cookieOnly(victim.cookie());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                other.start();
                other.join();
            }
            return inv.callRealMethod();
        }).when(tokens).updateToken(anyString(), anyString(), any());

        AutoLogin inFlight = cookieOnly(attackerCurrent);

        assertThat(fired.get()).isTrue();
        assertThat(inFlight.result().getResponse().getStatus()).isEqualTo(401);
        if (inFlight.session() != null && !inFlight.session().isInvalid()) {
            assertThat(meStatus(inFlight.session())).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("F5③: 자동 로그인 뒤 자기수정(기본 프로필 이미지)으로 인증을 새로 만들어도 remember-me 그대로이고 시리즈가 유지된다 — 완전 인증으로 승격되지 않는다")
    void refreshAuthentication_keepsRememberMeOrigin() throws Exception {
        Member m = member(Role.ROLE_MANAGER);
        Login login = login(m, true);
        AutoLogin auto = cookieOnly(login.cookie());

        // 내 정보 수정은 재인증이 필요한 민감 작업이라(PLAN-sensitive-reauthentication.md) refreshAuthentication을 부르는 비민감 자기수정을 쓴다
        mvc.perform(put(ME + "/profile-image").session(auto.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preset\":\"profile-1\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch(ME).session(auto.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"바뀐이름\",\"email\":\"" + m.getEmail() + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));

        SecurityContext context = (SecurityContext) auto.session().getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(context.getAuthentication()).isInstanceOf(RememberMeAuthenticationToken.class);
        assertThat(((CustomUserDetails) context.getAuthentication().getPrincipal()).getRememberMeSeries())
                .isEqualTo(series(login.cookie()));
    }

    @Test
    @DisplayName("F5⑥: 자동 로그인한 MANAGER의 ADMIN 전용 API·페이지는 폼 로그인과 같은 403이고, 같은 세션은 계속 인증된다(익명은 401)")
    void rememberMeManager_forbiddenIs403() throws Exception {
        Member manager = member(Role.ROLE_MANAGER);
        Login form = login(manager, true);
        AutoLogin auto = cookieOnly(form.cookie());

        mvc.perform(get("/admin/api/sessions").session(form.session()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get("/admin/api/sessions").session(auto.session()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get("/admin/session/manage").session(auto.session()))
                .andExpect(status().isForbidden());
        assertThat(meStatus(auto.session())).isEqualTo(200);
        mvc.perform(get("/admin/api/sessions")).andExpect(status().isUnauthorized());
    }
}
