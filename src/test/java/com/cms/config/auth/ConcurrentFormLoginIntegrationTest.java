package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 같은 세션의 폼 로그인 직렬화({@link LoginSessionMutexFilter}) — 실제 SecurityConfig·폼 로그인·MariaDB.
 *
 * <p>Spring Security 7.0.7은 폼 로그인의 세션 ID 교체만 세션 뮤텍스 안에서 하고 레지스트리 등록·컨텍스트 저장은 그 밖에서 한다. 같은 세션의 두 탭이
 * 서로 다른 계정으로 동시에 로그인하면 "실제 인증 회원 ≠ 레지스트리 회원"이 남아, 그 세션이 실제 회원의 강제 만료(강등·비활성화 등)를 피할 수 있다.
 * 레지스트리 등록 직전에 스파이로 멈춰 이 순서를 결정적으로 만든다(PLAN-remember-me-removal.md 쟁점 2 — 원래 PLAN-sensitive-reauthentication.md F11).
 */
@SpringBootTest(classes = CmsTestApplication.class)
// 결과 출력 끔 — 동시 요청 시험에서 한 요청의 출력기가 다른 요청이 쓰는 세션 속성을 읽는 시험 도구 경합을 피한다(제품 동작 아님)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ConcurrentFormLoginIntegrationTest extends MariaDbContainerSupport {

    private static final String PASSWORD = "Concurrent-Login-Pass-2026!";
    private static final String CONTEXT = "SPRING_SECURITY_CONTEXT";

    @Autowired MockMvc mvc;
    @Autowired MemberRepository memberRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminSessionService adminSessionService;
    @MockitoSpyBean SessionRegistry sessionRegistry;

    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        reset(sessionRegistry);
        TestMembers.delete(jdbc, created);
    }

    private Member member() {
        Member saved = TestMembers.save(memberRepository, "cfl", Role.ROLE_MANAGER);
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), saved.getId());
        created.add(saved.getId());
        return memberRepository.findById(saved.getId()).orElseThrow();
    }

    private MockHttpServletRequestBuilder login(Member m) {
        return post("/admin/login").param("username", m.getUserId()).param("password", PASSWORD).with(csrf());
    }

    private static Authentication authOf(MockHttpSession session) {
        SecurityContext context = (SecurityContext) session.getAttribute(CONTEXT);
        return context == null ? null : context.getAuthentication();
    }

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

    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (thread.getState() != Thread.State.BLOCKED) {
            assertThat(System.nanoTime()).as("%s가 세션 뮤텍스에서 기다려야 한다", thread.getName()).isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    @Test
    @DisplayName("같은 세션의 두 탭이 서로 다른 회원으로 동시에 폼 로그인해도 직렬화돼 컨텍스트·레지스트리 회원이 일치하고, 그 회원 만료가 세션을 끊는다")
    void concurrentFormLoginsInSameSession_areSerialized() throws Exception {
        Member a = member();
        Member b = member();
        Member c = member();
        MvcResult base = mvc.perform(login(a)).andReturn();
        assertThat(base.getResponse().getRedirectedUrl()).isEqualTo("/admin");
        MockHttpSession session = (MockHttpSession) base.getRequest().getSession(false);
        CountDownLatch[] latch = pauseRegistrationOf(b.getId());

        AtomicReference<MvcResult> first = new AtomicReference<>();
        Thread loginB = run("login-b", first, login(b).session(session));
        assertThat(latch[0].await(15, TimeUnit.SECONDS)).isTrue();             // B가 레지스트리 등록 직전에 멈춤

        AtomicReference<MvcResult> second = new AtomicReference<>();
        Thread loginC = run("login-c", second, login(c).session(session));
        awaitBlocked(loginC);                                                  // C는 B가 끝날 때까지 세션 뮤텍스에서 기다린다(양성 증거)

        latch[1].countDown();
        loginB.join(15_000);
        loginC.join(15_000);

        Long contextMember = ((CustomUserDetails) authOf(session).getPrincipal()).getId();
        SessionInformation info = sessionRegistry.getSessionInformation(session.getId());
        assertThat(info).isNotNull();
        assertThat(((CustomUserDetails) info.getPrincipal()).getId()).isEqualTo(contextMember);

        adminSessionService.expireSessionsFor(contextMember);
        assertThat(mvc.perform(get("/admin/api/members/me").session(session)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
