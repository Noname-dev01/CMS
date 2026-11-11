package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.MutableClock;
import com.cms.support.TestMembers;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.authentication.rememberme.PersistentRememberMeToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 만료된 remember-me 토큰 행 정리(PLAN-remember-me-token-purge.md F1~F7) — 로그인 성공 시 전역 정리를 실제 SecurityConfig·폼 로그인·
 * 쿠키 자동 로그인·MariaDB로 확인한다. 시각은 밀리초에 정렬된 {@link MutableClock}으로 고정하고, 행은 운영과 같은 경로
 * ({@code createNewToken} + {@code Date})로 만든다 — 운영 쓰기가 모두 밀리초라서다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import(RememberMeTokenPurgeIntegrationTest.ClockConfig.class)
class RememberMeTokenPurgeIntegrationTest extends MariaDbContainerSupport {

    private static final String PASSWORD = "Purge-Remember-Pass-2026!";
    private static final String ME = "/admin/api/members/me";
    private static final LocalDateTime NOW_KST = LocalDateTime.of(2090, 6, 1, 12, 0);
    private static final Duration VALIDITY = Duration.ofSeconds(AdminRememberMeServices.VALIDITY_SECONDS);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW_KST);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired MemberRepository memberRepository;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired MutableClock clock;
    @MockitoSpyBean RememberMeTokenRepository tokens;

    private final List<Long> created = new ArrayList<>();
    private final List<String> usernames = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock.set(NOW_KST);
    }

    @AfterEach
    void cleanUp() {
        reset(tokens);
        for (String username : usernames) {
            jdbc.update("DELETE FROM persistent_logins WHERE username = ?", username);
        }
        TestMembers.delete(jdbc, created);
    }

    // ---------------------------------------------------------------- 도우미

    private Member member() {
        Member saved = TestMembers.save(memberRepository, "pg", Role.ROLE_ADMIN);
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), saved.getId());
        created.add(saved.getId());
        usernames.add(saved.getUserId());
        return memberRepository.findById(saved.getId()).orElseThrow();
    }

    private Instant now() {
        return clock.instant();
    }

    private Instant cutoff() {
        return now().minus(VALIDITY);
    }

    /** 운영 발급 경로와 같은 방법(라이브러리 createNewToken + java.util.Date)으로 행을 만든다. 쿠키를 돌려준다. */
    private Cookie token(Member owner, Instant lastUsed) {
        String series = "s-" + UUID.randomUUID();
        String value = "t-" + UUID.randomUUID();
        tokens.createNewToken(new PersistentRememberMeToken(owner.getUserId(), series, value, Date.from(lastUsed)));
        assertThat(lastUsed(series)).as("저장된 last_used가 의도한 시각이어야 한다").isEqualTo(lastUsed);
        return encode(series, value);
    }

    private Instant lastUsed(String series) {
        Timestamp ts = jdbc.queryForObject("SELECT last_used FROM persistent_logins WHERE series = ?", Timestamp.class, series);
        return ts == null ? null : ts.toInstant();
    }

    private boolean exists(Cookie cookie) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM persistent_logins WHERE series = ?", Integer.class,
                seriesOf(cookie)) > 0;
    }

    private int rows(Member m) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM persistent_logins WHERE username = ?", Integer.class, m.getUserId());
    }

    private int visits(Member m) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM visit_log WHERE visitor_user_id = ?", Integer.class, m.getUserId());
    }

    /** remember-me 없이 폼 로그인하고, 그 세션으로 인증 API가 200인지 돌려준다. */
    private boolean formLogin(Member m) throws Exception {
        MvcResult result = mvc.perform(post("/admin/login")
                .param("username", m.getUserId()).param("password", PASSWORD).with(csrf())).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        return session != null && mvc.perform(get(ME).session(session)).andReturn().getResponse().getStatus() == 200;
    }

    /** 세션 없이 쿠키만으로 API를 부른다(쿠키 자동 로그인). */
    private int cookieOnly(Cookie cookie) throws Exception {
        return mvc.perform(get(ME).cookie(cookie)).andReturn().getResponse().getStatus();
    }

    private static Cookie encode(String series, String token) {
        String joined = URLEncoder.encode(series, StandardCharsets.UTF_8) + ":" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        String value = Base64.getEncoder().encodeToString(joined.getBytes(StandardCharsets.UTF_8));
        return new Cookie("remember-me", value.replaceAll("=+$", ""));
    }

    private static String seriesOf(Cookie cookie) {
        String value = cookie.getValue();
        while (value.length() % 4 != 0) {
            value = value + "=";
        }
        return java.net.URLDecoder.decode(
                new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8).split(":")[0], StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- F1·F5 경계와 범위

    @Test
    @DisplayName("F1·F5: 다른 회원의 폼 로그인이 cutoff보다 1ms 이른 행만 지우고, 경계 행과 유효 행은 남긴다 — 경계 행은 자동 로그인도 된다")
    void formLoginPurgesOnlyStrictlyExpiredRowsOfAllMembers() throws Exception {
        Member a = member();
        Member b = member();
        Cookie expired = token(b, cutoff().minusMillis(1));
        Cookie boundary = token(b, cutoff());
        Cookie recent = token(b, now().minus(Duration.ofDays(1)));

        assertThat(formLogin(a)).isTrue();

        assertThat(exists(expired)).as("만료 행(다른 회원 것)은 지워진다").isFalse();
        assertThat(exists(boundary)).as("경계 행은 아직 유효하다").isTrue();
        assertThat(exists(recent)).isTrue();
        assertThat(cookieOnly(boundary)).as("경계 행으로 자동 로그인").isEqualTo(200);
    }

    @Test
    @DisplayName("F5: 쿠키 자동 로그인 경로도 전역 정리를 한다")
    void autoLoginAlsoPurges() throws Exception {
        Member b = member();
        Member c = member();
        Cookie valid = token(b, now().minus(Duration.ofDays(1)));
        Cookie expiredOfOther = token(c, cutoff().minusMillis(1));

        assertThat(cookieOnly(valid)).isEqualTo(200);

        assertThat(exists(expiredOfOther)).isFalse();
        assertThat(rows(b)).as("자동 로그인한 기기 행(교체됨)은 남는다").isEqualTo(1);
    }

    // ---------------------------------------------------------------- F3 조회 후 갱신

    @Test
    @DisplayName("F3: 만료로 조회된 뒤 갱신된 행은 지우지 않는다(삭제 문장이 조건을 다시 확인)")
    void rowRefreshedAfterSelectionSurvives() throws Exception {
        Member a = member();
        Member b = member();
        Cookie expired = token(b, cutoff().minusMillis(1));
        doAnswer(inv -> {
            Object selected = inv.callRealMethod();
            jdbc.update("UPDATE persistent_logins SET last_used = ? WHERE series = ?", Timestamp.from(now()), seriesOf(expired));
            return selected;
        }).when(tokens).findExpiredSeries(any(Instant.class));

        assertThat(formLogin(a)).isTrue();

        assertThat(exists(expired)).isTrue();
    }

    // ---------------------------------------------------------------- F4 실패 격리·부분 삭제

    @Test
    @DisplayName("F4①: 조회가 실패해도 폼·자동 로그인은 성공하고 방문이 기록되며 ERROR 로그가 남는다")
    void purgeFailureDoesNotBreakLogin(CapturedOutput output) throws Exception {
        Member a = member();
        Member b = member();
        Cookie valid = token(b, now().minus(Duration.ofDays(1)));
        doThrow(new DataAccessResourceFailureException("purge-boom")).when(tokens).findExpiredSeries(any(Instant.class));

        assertThat(formLogin(a)).isTrue();
        assertThat(visits(a)).isEqualTo(1);
        assertThat(cookieOnly(valid)).isEqualTo(200);
        assertThat(output).contains("remember-me 만료 토큰 정리 실패");
    }

    @Test
    @DisplayName("F4②: 삭제 중간에 실패하면 앞서 지운 행은 지워진 채, 남은 행은 다음 로그인에서 지워진다")
    void partialDeleteIsRetriedOnNextLogin() throws Exception {
        Member a = member();
        Member b = member();
        token(b, cutoff().minusMillis(1));
        token(b, cutoff().minusMillis(2));
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            if (calls.incrementAndGet() == 2) {
                throw new DataAccessResourceFailureException("second-delete-boom");
            }
            return inv.callRealMethod();
        }).when(tokens).deleteExpiredSeries(anyString(), any(Instant.class));

        assertThat(formLogin(a)).isTrue();
        assertThat(rows(b)).isEqualTo(1);

        reset(tokens);
        assertThat(formLogin(a)).isTrue();
        assertThat(rows(b)).isZero();
    }

    // ---------------------------------------------------------------- F6·F7 로그인 지연 상한

    @Test
    @DisplayName("F6: 다른 트랜잭션이 만료 행을 잠그고 있어도 폼·자동 로그인은 5초 안에 성공하고, 풀린 뒤 다음 로그인이 지운다")
    void lockedExpiredRowDoesNotStallLogin() throws Exception {
        Member a = member();
        Member b = member();
        Cookie expired = token(b, cutoff().minusMillis(1));
        Cookie valid = token(a, now().minus(Duration.ofDays(1)));

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT series FROM persistent_logins WHERE series = ? FOR UPDATE")) {
                lock.setString(1, seriesOf(expired));
                lock.executeQuery().close();
            }
            long start = System.nanoTime();
            assertThat(formLogin(a)).isTrue();
            assertThat(cookieOnly(valid)).isEqualTo(200);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            assertThat(exists(expired)).isTrue();
            holder.rollback();
        }

        assertThat(formLogin(a)).isTrue();
        assertThat(exists(expired)).isFalse();
    }

    @Test
    @DisplayName("F8: 다른 연결이 persistent_logins 테이블만 잠가도(LOCK TABLES) remember-me 없는 폼 로그인은 5초 안에 성공한다")
    void tableLockDoesNotStallPlainFormLogin() throws Exception {
        Member a = member();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            try (var st = holder.createStatement()) {
                st.execute("LOCK TABLES persistent_logins WRITE");
            }
            try {
                long start = System.nanoTime();
                java.util.concurrent.Future<Boolean> login = executor.submit(() -> formLogin(a));
                // 회귀면 기본 lock_wait_timeout(1일)까지 멈춘다 — 시험이 걸리지 않도록 10초에서 끊는다.
                assertThat(login.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            } finally {
                try (var st = holder.createStatement()) {
                    st.execute("UNLOCK TABLES");
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("F7:문장마다 성공하며 쌓이는 대기는 경과 예산에서 끊기고, 남은 행은 다음 로그인에서 지워진다")
    void accumulatedWaitIsCutByBudget() throws Exception {
        Member a = member();
        Member b = member();
        for (int i = 1; i <= 10; i++) {
            token(b, cutoff().minusMillis(i));
        }
        doAnswer(inv -> {
            Thread.sleep(500);
            return inv.callRealMethod();
        }).when(tokens).deleteExpiredSeries(anyString(), any(Instant.class));

        long start = System.nanoTime();
        assertThat(formLogin(a)).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
        assertThat(rows(b)).isBetween(1, 9);

        reset(tokens);
        assertThat(formLogin(a)).isTrue();
        assertThat(rows(b)).isZero();
    }
}
