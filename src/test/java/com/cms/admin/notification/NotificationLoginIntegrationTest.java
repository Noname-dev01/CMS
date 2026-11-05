package com.cms.admin.notification;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.notification.domain.Notification;
import com.cms.admin.notification.domain.NotificationType;
import com.cms.admin.notification.repository.NotificationRepository;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;

/**
 * 로그인 성공 시 읽은 알림 정리(PLAN-admin-notification.md §7 ③)를 실제 로그인·MariaDB로 확인한다.
 * 로그인은 실제 트랜잭션을 커밋하므로 {@code @Transactional}을 붙이지 않고 만든 회원·방문 로그를 {@link #cleanUp()}에서 지운다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class NotificationLoginIntegrationTest extends MariaDbContainerSupport {

    private static final String RAW_PASSWORD = "Notify1234567890!";

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<String> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String userId : userIds) {
            jdbc.update("DELETE FROM visit_log WHERE visitor_user_id = ?", userId);
        }
        for (Long id : memberIds) {
            jdbc.update("DELETE FROM member_permission WHERE member_id = ?", id);
            jdbc.update("DELETE FROM member WHERE id = ?", id);   // 알림은 FK CASCADE
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private Member admin(LocalDateTime passwordChangedAt) {
        String unique = "notif-login-" + System.nanoTime();
        LocalDateTime now = now();
        Member saved = memberRepository.save(Member.builder()
                .userId(unique.substring(0, Math.min(50, unique.length())))
                .pwd(passwordEncoder.encode(RAW_PASSWORD))
                .userName("알림로그인")
                .email(unique + "@notif-login.test")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .createDate(now)
                .updateDate(now)
                .passwordChangedAt(passwordChangedAt)
                .build());
        memberIds.add(saved.getId());
        userIds.add(saved.getUserId());
        return saved;
    }

    private void login(Member member) throws Exception {
        mockMvc.perform(formLogin("/admin/login").user(member.getUserId()).password(RAW_PASSWORD));
    }

    @Test
    @DisplayName("옛 만료 임박 구간(85일)의 계정이 로그인해도 비밀번호 알림이 생기지 않는다")
    void oldPassword_login_createsNoNotification() throws Exception {
        Member member = admin(now().minusDays(85));

        login(member);

        assertThat(notificationRepository.findPage(member.getId(), null, true, 50)).isEmpty();
    }

    @Test
    @DisplayName("로그인 성공 시 읽은 지 90일 지난 본인 알림만 정리되고 89일·미읽음·다른 회원의 알림은 남는다")
    void loginPurgesOwnReadNotificationsOlderThan90Days() throws Exception {
        Member member = admin(now().minusDays(1));
        Member other = admin(now().minusDays(1));
        LocalDateTime now = now();
        Notification old = saveNotification(member, now.minusDays(91));
        Notification recent = saveNotification(member, now.minusDays(89));
        Notification unread = saveNotification(member, null);
        Notification othersOld = saveNotification(other, now.minusDays(200));

        login(member);

        assertThat(notificationRepository.findById(old.getId())).isEmpty();
        assertThat(notificationRepository.findById(recent.getId())).isPresent();
        assertThat(notificationRepository.findById(unread.getId())).isPresent();
        assertThat(notificationRepository.findById(othersOld.getId())).isPresent();
    }

    private Notification saveNotification(Member member, LocalDateTime readAt) {
        return notificationRepository.save(Notification.builder()
                .memberId(member.getId()).type(NotificationType.ACCOUNT_STATUS).message("m")
                .readAt(readAt).createDate(now()).build());
    }
}
