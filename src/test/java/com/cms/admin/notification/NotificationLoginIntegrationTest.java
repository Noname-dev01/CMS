package com.cms.admin.notification;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.notification.domain.Notification;
import com.cms.admin.notification.domain.NotificationType;
import com.cms.admin.notification.repository.NotificationRepository;
import com.cms.admin.notification.service.NotificationRecorder;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;

/**
 * 로그인 성공 시 알림 E3(비밀번호 만료 임박)·읽은 알림 정리(PLAN-admin-notification.md §7 ③, v3 R-4~R-6, v4 R-14)를 실제 로그인·MariaDB로 확인한다.
 * 로그인은 실제 트랜잭션을 커밋하므로 {@code @Transactional}을 붙이지 않고 만든 회원·방문 로그를 {@link #cleanUp()}에서 지운다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class NotificationLoginIntegrationTest extends MariaDbContainerSupport {

    private static final String RAW_PASSWORD = "Notify1234567890!";

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired NotificationRecorder notificationRecorder;
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

    private List<Notification> expiryNotifications(Member member) {
        return notificationRepository.findPage(member.getId(), null, true, 50).stream()
                .filter(n -> n.getType() == NotificationType.PASSWORD_EXPIRY).toList();
    }

    private void login(Member member) throws Exception {
        mockMvc.perform(formLogin("/admin/login").user(member.getUserId()).password(RAW_PASSWORD));
    }

    @Test
    @DisplayName("만료 7일 이내인 관리자가 로그인하면 절대 만료일이 든 알림이 1건 생기고, 같은 비밀번호 주기에 다시 로그인해도 늘지 않는다")
    void loginWithinSevenDays_createsOneNotification_perPasswordCycle() throws Exception {
        Member member = admin(now().minusDays(85));

        login(member);
        List<Notification> first = expiryNotifications(member);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).getLinkUrl()).isEqualTo("/admin/member/settings");
        String expectedExpiry = member.getPasswordChangedAt().plusDays(90)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        assertThat(first.get(0).getMessage()).contains(expectedExpiry);

        login(member);
        login(member);
        assertThat(expiryNotifications(member)).hasSize(1);
    }

    @Test
    @DisplayName("만료까지 7일보다 많이 남았으면 알림이 없다")
    void loginWithMoreThanSevenDaysLeft_noNotification() throws Exception {
        Member member = admin(now().minusDays(60));

        login(member);

        assertThat(expiryNotifications(member)).isEmpty();
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

    // ── 경계(v3 R-4) — recorder를 직접 호출해 시각을 정확히 지정한다 ──

    private void changePasswordAt(Member member, LocalDateTime changedAt) {
        jdbc.update("UPDATE member SET password_changed_at = ? WHERE id = ?", changedAt, member.getId());
    }

    @Test
    @DisplayName("경계: 만료 시각이 now 이후 1시간(23시에 바꾼 비밀번호의 90일째 오전)이어도 알림이 생기고, 만료 시각이 now 이전이거나 7일을 1초 넘으면 생기지 않는다")
    void windowBoundaries_useExpiresAtNotDateDifference() {
        Member member = admin(now());

        LocalDateTime oneHourLeft = now().minusDays(90).plusHours(1);
        changePasswordAt(member, oneHourLeft);
        notificationRecorder.recordPasswordExpiryIfNear(member.getId(), oneHourLeft);
        assertThat(expiryNotifications(member)).hasSize(1);

        Member expired = admin(now());
        LocalDateTime justExpired = now().minusDays(90).minusSeconds(1);
        changePasswordAt(expired, justExpired);
        notificationRecorder.recordPasswordExpiryIfNear(expired.getId(), justExpired);
        assertThat(expiryNotifications(expired)).isEmpty();                    // 이미 만료 — 로그인 불가 경로

        Member tooFar = admin(now());
        LocalDateTime sevenDaysAndASecond = now().minusDays(83).plusSeconds(2);   // 만료까지 7일 + 약 2초
        changePasswordAt(tooFar, sevenDaysAndASecond);
        notificationRecorder.recordPasswordExpiryIfNear(tooFar.getId(), sevenDaysAndASecond);
        assertThat(expiryNotifications(tooFar)).isEmpty();

        Member sevenDays = admin(now());
        LocalDateTime sevenDaysLeft = now().minusDays(83).minusSeconds(2);        // 만료까지 7일 − 약 2초
        changePasswordAt(sevenDays, sevenDaysLeft);
        notificationRecorder.recordPasswordExpiryIfNear(sevenDays.getId(), sevenDaysLeft);
        assertThat(expiryNotifications(sevenDays)).hasSize(1);
    }

    @Test
    @DisplayName("스냅샷 불일치: 재확인 직후 비밀번호가 바뀌어 현재 password_changed_at이 스냅샷과 다르면 이전 주기의 알림은 만들지 않는다")
    void staleSnapshot_createsNothing() {
        Member member = admin(now());
        LocalDateTime staleSnapshot = now().minusDays(85);
        changePasswordAt(member, now());   // 그 사이 비밀번호가 바뀌어 새 주기가 시작됐다

        notificationRecorder.recordPasswordExpiryIfNear(member.getId(), staleSnapshot);

        assertThat(expiryNotifications(member)).isEmpty();
    }

    @Test
    @DisplayName("같은 주기에 동시에 여러 번 호출해도 예외 없이 알림이 정확히 1건이다(ON DUPLICATE KEY — notification.id 한정)")
    void concurrentCalls_createExactlyOne_withoutException() throws Exception {
        Member member = admin(now());
        LocalDateTime changedAt = now().minusDays(86);
        changePasswordAt(member, changedAt);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                notificationRecorder.recordPasswordExpiryIfNear(member.getId(), changedAt);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get();   // 어느 스레드든 예외가 나면 여기서 실패한다
        }
        pool.shutdown();

        assertThat(expiryNotifications(member)).hasSize(1);
    }
}
