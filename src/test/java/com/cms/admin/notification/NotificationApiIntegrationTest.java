package com.cms.admin.notification;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.notification.domain.Notification;
import com.cms.admin.notification.domain.NotificationType;
import com.cms.admin.notification.repository.NotificationRepository;
import com.cms.config.auth.CustomUserDetails;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 알림 API를 실제 SecurityConfig·판정기·MariaDB로 확인한다(PLAN-admin-notification.md §7 ②·⑤, D11, v3 R-1·R-7).
 * 시험마다 실제 회원·알림 행을 만들고 지운다(회원 삭제 시 알림은 FK CASCADE로 함께 지워진다).
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class NotificationApiIntegrationTest extends MariaDbContainerSupport {

    private static final String BASE = "/admin/api/members/me/notifications";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired NotificationRepository notificationRepository;

    private Member alice;
    private Member bob;

    @BeforeEach
    void setUp() {
        alice = TestMembers.save(memberRepository, "notifapi-alice", Role.ROLE_ADMIN);
        bob = TestMembers.save(memberRepository, "notifapi-bob", Role.ROLE_MANAGER);
    }

    @AfterEach
    void cleanUp() {
        TestMembers.delete(jdbc, List.of(alice.getId(), bob.getId()));
    }

    private Notification save(Member member, NotificationType type, String message) {
        return notificationRepository.save(Notification.builder()
                .memberId(member.getId())
                .type(type)
                .message(message)
                .createDate(LocalDateTime.now())
                .build());
    }

    /** 같은 회원 ID로 역할만 다른 로그인 주체 — 강등·승격 직후의 다음 요청을 흉내 낸다. */
    private static RequestPostProcessor asRole(Member member, Role role) {
        CustomUserDetails details = TestMembers.detached(member.getId(), role);
        return authentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private static final String READ_TRUE = "{\"read\": true}";

    @Test
    @DisplayName("IDOR: 다른 회원의 알림 id로 읽음 처리하면 404이고 그 알림은 바뀌지 않는다")
    void markRead_otherMembersNotification_404_andUnchanged() throws Exception {
        Notification alicesNotification = save(alice, NotificationType.ACCOUNT_STATUS, "앨리스 알림");

        mockMvc.perform(patch(BASE + "/" + alicesNotification.getId()).with(csrf()).with(asRole(bob, Role.ROLE_MANAGER))
                        .contentType(MediaType.APPLICATION_JSON).content(READ_TRUE))
                .andExpect(status().isNotFound());

        assertThat(notificationRepository.findById(alicesNotification.getId()).orElseThrow().getReadAt()).isNull();
    }

    @Test
    @DisplayName("목록·미읽음 수는 본인 것만 — 다른 회원의 알림은 개수에도 포함되지 않는다")
    void list_and_count_areOwnOnly() throws Exception {
        save(alice, NotificationType.ACCOUNT_STATUS, "앨리스 1");
        save(alice, NotificationType.PERMISSION, "앨리스 2");
        save(bob, NotificationType.PASSWORD_EXPIRY, "밥 1");

        mockMvc.perform(get(BASE).with(asRole(bob, Role.ROLE_MANAGER)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].message").value("밥 1"))
                .andExpect(jsonPath("$.unreadCount").value(1));

        mockMvc.perform(get(BASE + "/unread-count").with(asRole(alice, Role.ROLE_ADMIN)))
                .andExpect(jsonPath("$.unreadCount").value(2));
    }

    @Test
    @DisplayName("D11: ADMIN일 때 받은 ADMIN 전용 알림은 강등 후 목록·미읽음 수에서 빠지고 단건 읽음은 404이며 전체 읽음은 건드리지 않고, 재승격하면 다시 보인다")
    void adminOnlyNotification_followsCurrentRole() throws Exception {
        Notification e4 = save(alice, NotificationType.ADMIN_ACCOUNT_LOCKED, "다른 계정 자동 잠금");
        save(alice, NotificationType.ACCOUNT_STATUS, "내 계정 알림");

        // ADMIN으로는 둘 다 보인다
        mockMvc.perform(get(BASE).with(asRole(alice, Role.ROLE_ADMIN)))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.unreadCount").value(2));

        // 강등(같은 회원, 이제 MANAGER) — E4는 보이지 않고 미읽음 수에서도 빠진다
        mockMvc.perform(get(BASE).with(asRole(alice, Role.ROLE_MANAGER)))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].message").value("내 계정 알림"))
                .andExpect(jsonPath("$.unreadCount").value(1));
        mockMvc.perform(patch(BASE + "/" + e4.getId()).with(csrf()).with(asRole(alice, Role.ROLE_MANAGER))
                        .contentType(MediaType.APPLICATION_JSON).content(READ_TRUE))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch(BASE).with(csrf()).with(asRole(alice, Role.ROLE_MANAGER))
                        .contentType(MediaType.APPLICATION_JSON).content(READ_TRUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.unreadCount").value(0));
        assertThat(notificationRepository.findById(e4.getId()).orElseThrow().getReadAt()).isNull();

        // 재승격 — 숨겨졌던 E4가 미읽음으로 다시 보인다
        mockMvc.perform(get(BASE).with(asRole(alice, Role.ROLE_ADMIN)))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.unreadCount").value(1));
    }

    @Test
    @DisplayName("같은 알림을 동시에 여러 번 읽음 처리해도 모두 200이고 최초 읽음 시각이 이후 요청으로 덮어써지지 않는다")
    void concurrentMarkRead_keepsFirstReadAt() throws Exception {
        Notification n = save(alice, NotificationType.ACCOUNT_STATUS, "경합");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mockMvc.perform(patch(BASE + "/" + n.getId()).with(csrf()).with(asRole(alice, Role.ROLE_ADMIN))
                                .contentType(MediaType.APPLICATION_JSON).content(READ_TRUE))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (Future<Integer> result : results) {
            assertThat(result.get()).isEqualTo(200);
        }
        pool.shutdown();

        LocalDateTime firstReadAt = notificationRepository.findById(n.getId()).orElseThrow().getReadAt();
        assertThat(firstReadAt).isNotNull();

        // 이후 다시 읽음 처리해도(그리고 전체 읽음을 해도) 최초 읽음 시각은 그대로다
        mockMvc.perform(patch(BASE + "/" + n.getId()).with(csrf()).with(asRole(alice, Role.ROLE_ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(READ_TRUE)).andExpect(status().isOk());
        mockMvc.perform(patch(BASE).with(csrf()).with(asRole(alice, Role.ROLE_ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(READ_TRUE)).andExpect(status().isOk());
        assertThat(notificationRepository.findById(n.getId()).orElseThrow().getReadAt()).isEqualTo(firstReadAt);
    }

    @Test
    @DisplayName("비로그인은 JSON 401이다")
    void unauthenticated_json401() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("커서(beforeId)로 이어 읽으면 중복·누락 없이 전체를 읽고 마지막 페이지의 hasMore는 false다")
    void cursorPaging_coversAllWithoutGaps() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(save(alice, NotificationType.ACCOUNT_STATUS, "n" + i).getId());
        }

        var first = mockMvc.perform(get(BASE).param("size", "2").with(asRole(alice, Role.ROLE_ADMIN)))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].id").value(ids.get(4)))
                .andExpect(jsonPath("$.content[1].id").value(ids.get(3)));
        first.andReturn();

        mockMvc.perform(get(BASE).param("size", "2").param("beforeId", String.valueOf(ids.get(3))).with(asRole(alice, Role.ROLE_ADMIN)))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.content[0].id").value(ids.get(2)))
                .andExpect(jsonPath("$.content[1].id").value(ids.get(1)));
        mockMvc.perform(get(BASE).param("size", "2").param("beforeId", String.valueOf(ids.get(1))).with(asRole(alice, Role.ROLE_ADMIN)))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(ids.get(0)));
    }
}
