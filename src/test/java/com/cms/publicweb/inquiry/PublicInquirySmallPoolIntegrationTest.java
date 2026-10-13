package com.cms.publicweb.inquiry;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 요청당 커넥션 2개 금지 시험(PLAN-inquiry.md R4-1). 커넥션 풀을 2개로 줄이고 획득 대기를 짧게 한 컨텍스트에서 풀 크기보다 많은 문의를 한꺼번에 접수한다.
 * 알림이 {@code AFTER_COMMIT} 리스너의 {@code REQUIRES_NEW}로 제출 트랜잭션의 커넥션을 쥔 채 두 번째 커넥션을 요구하는 구조라면, 동시 제출이 풀을 서로 붙잡고
 * 획득 타임아웃까지 막혀 일부가 실패한다. 접수 → (커밋·반환) → 알림 순차 구조에서는 모두 303이다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "cms.rate-limit.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=2",
        "spring.datasource.hikari.connection-timeout=2500"
})
class PublicInquirySmallPoolIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @MockitoBean CaptchaVerifier captchaVerifier;

    private Member admin;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM inquiry");
        admin = TestMembers.save(memberRepository, "sp-admin", Role.ROLE_ADMIN);
        when(captchaVerifier.verify(any(), any())).thenReturn(true);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM inquiry");
        TestMembers.delete(jdbc, List.of(admin.getId()));
    }

    @Test
    @DisplayName("풀 2개에서 문의 8건을 동시에 접수해도 전부 303이고 8건이 저장된다 — 알림이 요청당 두 번째 커넥션을 쥐지 않는다")
    void concurrentSubmits_doNotExhaustSmallPool() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int n = i;
                calls.add(() -> {
                    ready.countDown();
                    go.await();
                    return mockMvc.perform(post("/main/inquiry").with(csrf())
                                    .param("name", "홍길동" + n).param("email", "visitor" + n + "@example.com").param("title", "제목 " + n)
                                    .param("body", "본문 " + n).param("consent", "true").param("cf-turnstile-response", "token"))
                            .andReturn().getResponse().getStatus();
                });
            }
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(call));
            }
            ready.await();
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }

            assertThat(statuses).containsOnly(303);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inquiry", Integer.class)).isEqualTo(threads);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE member_id = ? AND type = 'INQUIRY_RECEIVED'",
                    Integer.class, admin.getId())).as("병합 — 미읽음 문의 알림은 회원당 최대 1건(경합 중복은 허용하되 8건 전부는 아니다)").isBetween(1, 3);
        } finally {
            pool.shutdownNow();
        }
    }
}
