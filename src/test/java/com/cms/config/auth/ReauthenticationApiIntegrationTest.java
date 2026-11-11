package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.AdminMemberUpdateRequest;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.service.AdminMemberService;
import com.cms.admin.session.service.AdminSessionManageService;
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
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재인증 API {@code POST /admin/api/members/me/reauthentication}(PLAN-sensitive-reauthentication.md 쟁점 6·7, F6~F8·F10·F11) —
 * 실제 SecurityConfig·폼 로그인·쿠키 자동 로그인·MariaDB. 경합 지점은 스파이로 결정적으로 만든다:
 * 비밀번호 확인 직후(= ID 교체 전)는 {@link AuthenticationManager}, ID 교체 뒤 재확인은 {@link RememberMeTokenRepository#existsSeries},
 * 폼 로그인의 레지스트리 등록은 {@link SessionRegistry#registerNewSession}.
 * MockMvc에는 컨테이너 ID 변경 리스너가 없어 레지스트리 이동은 서비스의 직접 이동 경로를 탄다(실제 Tomcat 경로는 실기 확인).
 */
@SpringBootTest(classes = CmsTestApplication.class)
// 결과 출력 끔 — 동시 요청 시험에서 한 요청이 세션을 무효화한 직후 다른 요청의 출력기가 세션 속성을 읽다 예외를 던진다(시험 도구 경합, 제품 동작 아님)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ReauthenticationApiIntegrationTest extends MariaDbContainerSupport {

    private static final String PASSWORD = "Reauth-Api-Pass-2026!";
    private static final String ME = "/admin/api/members/me";
    private static final String REAUTH = ME + "/reauthentication";
    private static final String CONTEXT = "SPRING_SECURITY_CONTEXT";

    @Autowired MockMvc mvc;
    @Autowired MemberRepository memberRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminMemberService adminMemberService;
    @Autowired AdminSessionManageService adminSessionManageService;
    @Autowired AdminSessionService adminSessionService;
    @MockitoSpyBean AuthenticationManager authenticationManager;
    @MockitoSpyBean RememberMeTokenRepository tokens;
    @MockitoSpyBean SessionRegistry sessionRegistry;
    @MockitoSpyBean LoginFailureService loginFailureService;

    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        reset(authenticationManager, tokens, sessionRegistry, loginFailureService);
        for (Long id : created) {
            jdbc.update("DELETE FROM persistent_logins WHERE username = (SELECT user_id FROM member WHERE id = ?)", id);
        }
        TestMembers.delete(jdbc, created);
    }

    // ---------------------------------------------------------------- 도우미

    private Member member(Role role) {
        Member saved = TestMembers.save(memberRepository, "rr", role);
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), saved.getId());
        created.add(saved.getId());
        return memberRepository.findById(saved.getId()).orElseThrow();
    }

    private record Login(MockHttpSession session, Cookie cookie) {
    }

    private Login formLogin(Member m, boolean remember, MockHttpSession existing) throws Exception {
        MockHttpServletRequestBuilder req = post("/admin/login").param("username", m.getUserId()).param("password", PASSWORD).with(csrf());
        if (remember) {
            req.param("remember-me", "on");
        }
        if (existing != null) {
            req.session(existing);
        }
        MvcResult result = mvc.perform(req).andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/admin");
        return new Login((MockHttpSession) result.getRequest().getSession(false), lastRememberMeCookie(result));
    }

    /** 브라우저 재시작 모사: 쿠키만으로 GET → remember-me 세션과 교체된 쿠키. */
    private Login restored(Cookie cookie) throws Exception {
        MvcResult result = mvc.perform(get(ME).cookie(cookie)).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(authOf(session)).isInstanceOf(RememberMeAuthenticationToken.class);
        return new Login(session, lastRememberMeCookie(result));
    }

    private static Cookie lastRememberMeCookie(MvcResult result) {
        Cookie last = null;
        for (Cookie c : result.getResponse().getCookies()) {
            if ("remember-me".equals(c.getName())) {
                last = c;
            }
        }
        return last;
    }

    private static String series(Cookie cookie) {
        String value = cookie.getValue();
        while (value.length() % 4 != 0) {
            value = value + "=";
        }
        String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        return URLDecoder.decode(decoded.split(":")[0], StandardCharsets.UTF_8);
    }

    private static Authentication authOf(MockHttpSession session) {
        SecurityContext context = (SecurityContext) session.getAttribute(CONTEXT);
        return context == null ? null : context.getAuthentication();
    }

    private MvcResult reauth(MockHttpSession session, String password) throws Exception {
        return mvc.perform(post(REAUTH).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + password + "\"}")).andReturn();
    }

    private int patchMe(MockHttpSession session, Member m) throws Exception {
        return mvc.perform(patch(ME).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userName\":\"재인증\",\"email\":\"" + m.getEmail() + "\"}")).andReturn().getResponse().getStatus();
    }

    private int failures(Member m) {
        return jdbc.queryForObject("SELECT failed_login_count FROM member WHERE id = ?", Integer.class, m.getId());
    }

    /** 비밀번호 확인 직후(ID 교체 전) 한 번만 끼워 넣는다. */
    private void afterPasswordCheck(Runnable interleave) throws Exception {
        AtomicBoolean done = new AtomicBoolean();
        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            if (done.compareAndSet(false, true)) {
                interleave.run();
            }
            return result;
        }).when(authenticationManager).authenticate(any());
    }

    private static void assertEnded(MvcResult result, MockHttpSession session) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).contains("UNAUTHORIZED");
        assertThat(session.isInvalid()).as("세션은 끝나야 한다").isTrue();
        assertThat(result.getRequest().getSession(false)).as("새 세션을 만들지 않는다").isNull();
    }

    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (thread.getState() != Thread.State.BLOCKED) {
            assertThat(System.nanoTime()).as("%s가 세션 뮤텍스에서 기다려야 한다", thread.getName()).isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    // ---------------------------------------------------------------- 성공·실패 (성공 기준 7, F6·F7)

    @Test
    @DisplayName("쿠키 복원 세션: 정답이면 204, 세션 ID가 바뀌고 완전 인증·시리즈 보존·레지스트리 새 ID 등록, 이어서 민감 작업 성공, 로그아웃이 그 기기를 지운다")
    void correctPassword_upgradesInPlace() throws Exception {
        Member m = member(Role.ROLE_MANAGER);
        Login form = formLogin(m, true, null);
        Login r = restored(form.cookie());
        String series = series(r.cookie());
        String oldId = r.session().getId();
        assertThat(patchMe(r.session(), m)).isEqualTo(403);

        MvcResult result = reauth(r.session(), PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        String newId = r.session().getId();
        assertThat(newId).as("세션 고정 방지").isNotEqualTo(oldId);
        Authentication upgraded = authOf(r.session());
        assertThat(upgraded).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(((CustomUserDetails) upgraded.getPrincipal()).getRememberMeSeries()).isEqualTo(series);
        SessionInformation info = sessionRegistry.getSessionInformation(newId);
        assertThat(info).isNotNull();
        assertThat(((CustomUserDetails) info.getPrincipal()).getId()).isEqualTo(m.getId());
        assertThat(sessionRegistry.getSessionInformation(oldId)).isNull();
        assertThat(failures(m)).isZero();

        assertThat(patchMe(r.session(), m)).isEqualTo(200);

        mvc.perform(post("/admin/logout").session(r.session()).cookie(r.cookie()).with(csrf()));
        assertThat(tokens.existsSeries(series)).as("승격 뒤에도 로그아웃이 이 기기를 지운다").isFalse();
    }

    @Test
    @DisplayName("오답은 400 REAUTHENTICATION_FAILED, 폼 로그인과 같은 실패 카운터가 오르고 여전히 쿠키 세션이다")
    void wrongPassword_isCountedAndNotUpgraded() throws Exception {
        Member m = member(Role.ROLE_MANAGER);
        Login r = restored(formLogin(m, true, null).cookie());

        MvcResult result = reauth(r.session(), "wrong-" + PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("REAUTHENTICATION_FAILED");
        assertThat(failures(m)).isEqualTo(1);
        assertThat(authOf(r.session())).isInstanceOf(RememberMeAuthenticationToken.class);
        assertThat(patchMe(r.session(), m)).isEqualTo(403);
    }

    @Test
    @DisplayName("다섯 번째 오답 응답이 곧 401 — 계정 잠금·세션 종료·토큰 삭제(무차별 대입은 폼 로그인과 같은 잠금에 걸린다)")
    void fifthWrongPassword_locksAndEnds() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        Login r = restored(formLogin(m, true, null).cookie());
        for (int i = 0; i < 4; i++) {
            assertThat(reauth(r.session(), "wrong-" + i).getResponse().getStatus()).isEqualTo(400);
        }

        MvcResult fifth = reauth(r.session(), "wrong-5");

        assertEnded(fifth, r.session());
        assertThat(jdbc.queryForObject("SELECT status FROM member WHERE id = ?", String.class, m.getId())).isEqualTo("LOCKED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM persistent_logins WHERE username = ?", Integer.class, m.getUserId())).isZero();
    }

    @Test
    @DisplayName("오답의 실패 집계가 예외로 끝나면(집계를 보장할 수 없으면) 세션을 끝낸다 — 집계 없이 계속 시도하는 경로가 없다(코드 리뷰 4라운드)")
    void failureRecordingError_endsSession() throws Exception {
        Member m = member(Role.ROLE_MANAGER);
        Login r = restored(formLogin(m, true, null).cookie());
        doThrow(new org.springframework.dao.CannotAcquireLockException("집계 실패"))
                .when(loginFailureService).recordFailure(anyString(), any(), any());

        assertEnded(reauth(r.session(), "wrong-" + PASSWORD), r.session());
    }

    @Test
    @DisplayName("계정이 잠김·비활성(DB 직접 변경)이면 정답이어도 401·승격 없음, 실패 카운터는 오르지 않는다")
    void inactiveAccount_endsWithoutUpgrade() throws Exception {
        for (String status : new String[]{"LOCKED", "DISABLED"}) {
            Member m = member(Role.ROLE_MANAGER);
            Login r = restored(formLogin(m, true, null).cookie());
            jdbc.update("UPDATE member SET status = ? WHERE id = ?", status, m.getId());

            MvcResult result = reauth(r.session(), PASSWORD);

            assertEnded(result, r.session());
            assertThat(failures(m)).as(status).isZero();
        }
    }

    @Test
    @DisplayName("이미 완전 인증인 폼 로그인 세션: 정답 204·세션 ID 그대로, 오답은 같은 카운터에 400")
    void fullyAuthenticatedSession_onlyVerifiesPassword() throws Exception {
        Member m = member(Role.ROLE_MANAGER);
        Login form = formLogin(m, false, null);
        String id = form.session().getId();
        Authentication before = authOf(form.session());

        assertThat(reauth(form.session(), PASSWORD).getResponse().getStatus()).isEqualTo(204);
        assertThat(form.session().getId()).isEqualTo(id);
        assertThat(authOf(form.session())).isSameAs(before);

        assertThat(reauth(form.session(), "wrong").getResponse().getStatus()).isEqualTo(400);
        assertThat(failures(m)).isEqualTo(1);
    }

    @Test
    @DisplayName("세션 없이 쿠키만으로 바로 보내면 CSRF가 먼저라 기존 401이고 토큰·실패 카운터가 그대로다")
    void cookieOnlyWithoutSession_isUnauthorized() throws Exception {
        Member m = member(Role.ROLE_MANAGER);
        Login form = formLogin(m, true, null);
        String token = jdbc.queryForObject("SELECT token FROM persistent_logins WHERE username = ?", String.class, m.getUserId());

        mvc.perform(post(REAUTH).cookie(form.cookie()).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(jdbc.queryForObject("SELECT token FROM persistent_logins WHERE username = ?", String.class, m.getUserId())).isEqualTo(token);
        assertThat(failures(m)).isZero();
    }

    @Test
    @DisplayName("빈 비밀번호는 400 검증 오류이고 실패로 세지 않는다")
    void blankPassword_isValidationError() throws Exception {
        Member m = member(Role.ROLE_MANAGER);
        Login r = restored(formLogin(m, true, null).cookie());

        mvc.perform(post(REAUTH).session(r.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(failures(m)).isZero();
    }

    @Test
    @DisplayName("같은 세션의 두 탭이 동시에 재인증하면 늦은 쪽은 이미 승격된 세션을 보고 204 — 승격된 세션을 끝내지 않는다(코드 리뷰 1라운드)")
    void concurrentReauthOfSameMember_secondSucceedsWithoutEndingSession() throws Exception {
        Member m = member(Role.ROLE_MANAGER);
        Login r = restored(formLogin(m, true, null).cookie());
        AtomicReference<MvcResult> first = new AtomicReference<>();
        // 늦은 쪽(이 요청)이 비밀번호 확인을 마친 직후, 다른 탭의 재인증이 끝까지 진행된다
        afterPasswordCheck(() -> {
            Thread other = run("other-tab", first, post(REAUTH).session(r.session()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}"));
            try {
                other.join(15_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        MvcResult late = reauth(r.session(), PASSWORD);

        assertThat(first.get().getResponse().getStatus()).isEqualTo(204);
        assertThat(late.getResponse().getStatus()).isEqualTo(204);
        assertThat(r.session().isInvalid()).isFalse();
        assertThat(authOf(r.session())).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(patchMe(r.session(), m)).isEqualTo(200);
    }

    @Test
    @DisplayName("재인증 중 다른 탭의 프로필 변경(refreshAuthentication — 같은 회원·시리즈의 remember-me로 교체)이 끼어도 승격된다(코드 리뷰 2라운드)")
    void sameMemberRefreshDuringReauth_stillUpgrades() throws Exception {
        Member m = member(Role.ROLE_MANAGER);
        Login r = restored(formLogin(m, true, null).cookie());
        String series = series(r.cookie());
        AtomicReference<MvcResult> profile = new AtomicReference<>();
        afterPasswordCheck(() -> {
            Thread other = run("profile-tab", profile, put(ME + "/profile-image").session(r.session()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"preset\":\"profile-1\"}"));
            try {
                other.join(15_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        MvcResult result = reauth(r.session(), PASSWORD);

        assertThat(profile.get().getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        Authentication upgraded = authOf(r.session());
        assertThat(upgraded).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(((CustomUserDetails) upgraded.getPrincipal()).getRememberMeSeries()).isEqualTo(series);
    }

    // ---------------------------------------------------------------- 폐기 경합 (F8)

    @Test
    @DisplayName("경합 ⓐ: 비밀번호 확인 직후·ID 교체 전에 역할이 바뀌면(세션 만료) 401·승격 없음")
    void roleChangeBeforeSwap_ends() throws Exception {
        Member actor = member(Role.ROLE_ADMIN);
        Member m = member(Role.ROLE_MANAGER);
        Login r = restored(formLogin(m, true, null).cookie());
        afterPasswordCheck(() -> adminMemberService.updateAdminMember(actor.getId(), m.getId(),
                AdminMemberUpdateRequest.builder().userType(Role.ROLE_ADMIN).build()));

        assertEnded(reauth(r.session(), PASSWORD), r.session());
    }

    @Test
    @DisplayName("경합 ⓑ: 같은 기기의 폼 로그인 세션 A가 본인 회원 단위 만료(시리즈 보존)를 하면 재인증 중인 형제 B는 401·승격 없음")
    void siblingMemberExpireBeforeSwap_ends() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        Login a = formLogin(m, true, null);
        Login b = restored(a.cookie());
        afterPasswordCheck(() -> adminSessionManageService.expireMemberSessions(m.getId(), a.session().getId()));

        MvcResult result = reauth(b.session(), PASSWORD);

        assertEnded(result, b.session());
        assertThat(tokens.existsSeries(series(a.cookie()))).as("A의 기기는 남는다(경합의 전제)").isTrue();
    }

    @Test
    @DisplayName("경합 ⓒ: 비밀번호 확인 직후 탈취 감지 + 토큰 삭제 실패(폐기 대기)가 끼면 401·승격 없음")
    void theftWithDeleteFailureBeforeSwap_ends() throws Exception {
        Member m = member(Role.ROLE_ADMIN);
        Login form = formLogin(m, true, null);
        Login r = restored(form.cookie());           // form.cookie()는 이제 교체된 옛 쿠키 → 재사용하면 탈취 감지
        doThrow(new RuntimeException("삭제 실패")).when(tokens).removeUserTokens(anyString());
        afterPasswordCheck(() -> {
            Thread thief = new Thread(() -> {
                try {
                    mvc.perform(get(ME).cookie(form.cookie()));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, "thief");
            thief.start();
            try {
                thief.join(15_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertEnded(reauth(r.session(), PASSWORD), r.session());
    }

    @Test
    @DisplayName("ID 교체 뒤·컨텍스트 저장 전에 다른 탭의 로그아웃으로 세션이 무효화되면 401, 새 세션도 인증 부활도 없다")
    void logoutAfterSwap_endsWithoutNewSession() throws Exception {
        Member m = member(Role.ROLE_MANAGER);
        Login r = restored(formLogin(m, true, null).cookie());
        String series = series(r.cookie());
        AtomicBoolean done = new AtomicBoolean();
        doAnswer(inv -> {
            if (series.equals(inv.getArgument(0)) && done.compareAndSet(false, true)) {
                r.session().invalidate();
            }
            return inv.callRealMethod();
        }).when(tokens).existsSeries(anyString());

        assertEnded(reauth(r.session(), PASSWORD), r.session());
    }

    // ---------------------------------------------------------------- 폼 로그인과의 직렬화 (F10·F11)

    /** 폼 로그인이 principal의 회원 ID가 {@code memberId}인 등록을 시작하면 멈추게 한다. */
    private CountDownLatch[] pauseRegistrationOf(Long memberId) {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            return inv.callRealMethod();
        }).when(sessionRegistry).registerNewSession(anyString(),
                argThat(p -> p instanceof CustomUserDetails d && memberId.equals(d.getId())));
        return new CountDownLatch[]{entered, release};
    }

    private Thread run(String name, AtomicReference<MvcResult> out, MockHttpServletRequestBuilder request) {
        Thread t = new Thread(() -> {
            try {
                out.set(mvc.perform(request).andReturn());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, name);
        t.start();
        return t;
    }

    @Test
    @DisplayName("F10: 같은 세션에서 다른 회원 폼 로그인이 등록 중이면 재인증은 세션 뮤텍스에서 기다리고, 끝난 뒤 인증 불일치로 401·세션 종료")
    void formLoginOfOtherMemberDuringReauth_isSerialized() throws Exception {
        Member a = member(Role.ROLE_ADMIN);
        Member b = member(Role.ROLE_MANAGER);
        Login r = restored(formLogin(a, true, null).cookie());
        CountDownLatch[] latch = pauseRegistrationOf(b.getId());

        AtomicReference<MvcResult> loginResult = new AtomicReference<>();
        Thread login = run("form-login-b", loginResult, post("/admin/login").session(r.session())
                .param("username", b.getUserId()).param("password", PASSWORD).with(csrf()));
        assertThat(latch[0].await(15, TimeUnit.SECONDS)).isTrue();            // F가 뮤텍스 안에서 등록 직전에 멈춤

        AtomicReference<MvcResult> reauthResult = new AtomicReference<>();
        Thread reauth = run("reauth-a", reauthResult, post(REAUTH).session(r.session()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}"));
        awaitBlocked(reauth);                                                 // R은 F가 끝날 때까지 진행하지 못한다(양성 증거)

        latch[1].countDown();
        login.join(15_000);
        reauth.join(15_000);

        assertThat(loginResult.get().getResponse().getRedirectedUrl()).isEqualTo("/admin");
        assertEnded(reauthResult.get(), r.session());
    }

    @Test
    @DisplayName("F11: 같은 세션의 두 탭이 서로 다른 회원으로 동시에 폼 로그인해도 직렬화돼 컨텍스트·레지스트리 회원이 일치하고, 그 회원 만료가 세션을 끊는다")
    void concurrentFormLoginsInSameSession_areSerialized() throws Exception {
        Member a = member(Role.ROLE_MANAGER);
        Member b = member(Role.ROLE_MANAGER);
        Member c = member(Role.ROLE_MANAGER);
        Login base = formLogin(a, false, null);
        CountDownLatch[] latch = pauseRegistrationOf(b.getId());

        AtomicReference<MvcResult> first = new AtomicReference<>();
        Thread loginB = run("login-b", first, post("/admin/login").session(base.session())
                .param("username", b.getUserId()).param("password", PASSWORD).with(csrf()));
        assertThat(latch[0].await(15, TimeUnit.SECONDS)).isTrue();

        AtomicReference<MvcResult> second = new AtomicReference<>();
        Thread loginC = run("login-c", second, post("/admin/login").session(base.session())
                .param("username", c.getUserId()).param("password", PASSWORD).with(csrf()));
        awaitBlocked(loginC);

        latch[1].countDown();
        loginB.join(15_000);
        loginC.join(15_000);

        MockHttpSession session = base.session();
        Long contextMember = ((CustomUserDetails) authOf(session).getPrincipal()).getId();
        SessionInformation info = sessionRegistry.getSessionInformation(session.getId());
        assertThat(info).isNotNull();
        assertThat(((CustomUserDetails) info.getPrincipal()).getId()).isEqualTo(contextMember);

        adminSessionService.expireSessionsFor(contextMember);
        assertThat(mvc.perform(get(ME).session(session)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
