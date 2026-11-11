package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.session.HttpSessionIdChangedEvent;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재인증의 세션 ID 교체와 세션 만료의 직렬화(PLAN-sensitive-reauthentication.md 쟁점 6 v7·v8).
 * Spring Security 7.0.7 {@code SessionRegistryImpl}은 ID 변경 이벤트에서 옛 항목을 지우고 새로 등록해 만료 표시를 옮기지 않는다 —
 * 그래서 만료와 교체가 같은 모니터 안에서만 일어나야 만료가 사라지지 않는다. 컨테이너의 ID 변경 리스너는 {@link ListeningRequest}로 모사한다.
 */
class AdminSessionServiceSwapTest {

    private final SessionRegistryImpl registry = new SessionRegistryImpl();
    private final AdminSessionService service = new AdminSessionService(registry);

    private static CustomUserDetails principal(long memberId, String series) {
        CustomUserDetails details = new CustomUserDetails(Member.builder()
                .id(memberId).userId("user" + memberId).pwd("encoded").userName("사용자" + memberId)
                .email("user" + memberId + "@test.com").userType(Role.ROLE_ADMIN).status(MemberStatus.ACTIVE)
                .createDate(LocalDateTime.now()).updateDate(LocalDateTime.now()).build());
        details.setRememberMeSeries(series);
        return details;
    }

    /** Tomcat처럼 {@code changeSessionId()} 안에서 동기로 ID 변경 이벤트를 레지스트리에 전달하는 요청. 선택적으로 교체 도중 멈춘다. */
    private final class ListeningRequest extends MockHttpServletRequest {
        private final boolean publish;
        private final CountDownLatch entered = new CountDownLatch(1);
        private CountDownLatch release;

        ListeningRequest(MockHttpSession session, boolean publish) {
            this.publish = publish;
            setSession(session);
        }

        @Override
        public String changeSessionId() {
            entered.countDown();
            awaitRelease();
            HttpSession session = getSession(false);
            String oldId = session.getId();
            String newId = super.changeSessionId();
            if (publish) {
                registry.onApplicationEvent(new HttpSessionIdChangedEvent(session, oldId));
            }
            return newId;
        }

        private void awaitRelease() {
            if (release == null) {
                return;
            }
            try {
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static MockHttpSession session(String id) {
        return new MockHttpSession(null, id);
    }

    @Test
    @DisplayName("활성 세션: ID를 바꾸고 레지스트리 항목도 같은 principal로 새 ID에 있다")
    void activeSession_isSwapped() {
        CustomUserDetails p = principal(1L, "series-1");
        registry.registerNewSession("old", p);
        ListeningRequest request = new ListeningRequest(session("old"), true);

        assertThat(service.changeSessionIdIfActive(request, 1L)).isTrue();

        String newId = request.getSession(false).getId();
        assertThat(newId).isNotEqualTo("old");
        assertThat(registry.getSessionInformation("old")).isNull();
        assertThat(registry.getSessionInformation(newId).getPrincipal()).isSameAs(p);
        assertThat(registry.getSessionInformation(newId).isExpired()).isFalse();
    }

    @Test
    @DisplayName("교체 전에 만료된 세션·레지스트리에 없는 세션·다른 회원의 세션은 교체하지 않는다(false, ID 그대로)")
    void expiredMissingOrForeignSession_isNotSwapped() {
        registry.registerNewSession("expired", principal(1L, "s"));
        registry.getSessionInformation("expired").expireNow();
        ListeningRequest expired = new ListeningRequest(session("expired"), true);
        assertThat(service.changeSessionIdIfActive(expired, 1L)).isFalse();
        assertThat(expired.getSession(false).getId()).isEqualTo("expired");

        ListeningRequest missing = new ListeningRequest(session("missing"), true);
        assertThat(service.changeSessionIdIfActive(missing, 1L)).isFalse();
        assertThat(missing.getSession(false).getId()).isEqualTo("missing");

        registry.registerNewSession("foreign", principal(2L, "s2"));
        ListeningRequest foreign = new ListeningRequest(session("foreign"), true);
        assertThat(service.changeSessionIdIfActive(foreign, 1L)).isFalse();
        assertThat(foreign.getSession(false).getId()).isEqualTo("foreign");
    }

    @Test
    @DisplayName("ID 변경 리스너가 레지스트리를 옮기지 않았어도(예외를 삼킨 컨테이너·MockMvc) 직접 옮긴다")
    void listenerMissing_registryIsMovedDirectly() {
        CustomUserDetails p = principal(1L, "series-1");
        registry.registerNewSession("old", p);
        ListeningRequest request = new ListeningRequest(session("old"), false);

        assertThat(service.changeSessionIdIfActive(request, 1L)).isTrue();

        String newId = request.getSession(false).getId();
        assertThat(registry.getSessionInformation("old")).isNull();
        assertThat(registry.getSessionInformation(newId).getPrincipal()).isSameAs(p);
    }

    @Test
    @DisplayName("교체 뒤의 만료는 경로와 무관하게 새 ID 항목을 만료한다(회원·시리즈·아이디·핸들)")
    void expiryAfterSwap_hitsNewEntry() {
        for (int path = 0; path < 4; path++) {
            SessionRegistryImpl reg = new SessionRegistryImpl();
            AdminSessionService svc = new AdminSessionService(reg);
            CustomUserDetails p = principal(1L, "series-" + path);
            reg.registerNewSession("old-" + path, p);
            MockHttpSession s = session("old-" + path);
            // 리스너 없이 교체(직접 이동 경로) — 이동 결과는 리스너 경로와 같다(listenerMissing_registryIsMovedDirectly)
            MockHttpServletRequest plain = new MockHttpServletRequest();
            plain.setSession(s);
            assertThat(svc.changeSessionIdIfActive(plain, 1L)).isTrue();
            String newId = s.getId();

            switch (path) {
                case 0 -> svc.expireSessionsFor(1L);
                case 1 -> svc.expireSessionsWithSeries("series-1");
                case 2 -> svc.expireSessionsForUserId("USER1");
                default -> svc.expireByHandle(AdminSessionService.handleOf(newId));
            }
            assertThat(reg.getSessionInformation(newId).isExpired()).as("경로 %d", path).isTrue();
        }
    }

    @Test
    @DisplayName("교체가 진행 중이면 만료는 끝날 때까지 기다렸다가 새 ID 항목을 만료한다 — 만료 표시가 사라지지 않는다")
    void expiryDuringSwap_waitsAndHitsNewEntry() throws Exception {
        registry.registerNewSession("old", principal(1L, "series-1"));
        MockHttpSession s = session("old");
        ListeningRequest request = new ListeningRequest(s, true);
        request.release = new CountDownLatch(1);

        Thread swapper = new Thread(() -> service.changeSessionIdIfActive(request, 1L), "swapper");
        swapper.start();
        assertThat(request.entered.await(10, TimeUnit.SECONDS)).isTrue();   // 교체가 모니터 안에서 멈춤

        Thread revoker = new Thread(() -> service.expireSessionsFor(1L), "revoker");
        revoker.start();
        awaitBlocked(revoker);                                                // 만료는 모니터에서 기다린다(양성 증거)

        request.release.countDown();
        swapper.join(10_000);
        revoker.join(10_000);

        String newId = s.getId();
        assertThat(newId).isNotEqualTo("old");
        assertThat(registry.getSessionInformation(newId).isExpired()).isTrue();
    }

    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (thread.getState() != Thread.State.BLOCKED) {
            assertThat(System.nanoTime()).as("스레드가 모니터에서 대기해야 한다").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }
}
