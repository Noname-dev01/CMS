package com.cms.admin.notification.repository;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.notification.domain.Notification;
import com.cms.admin.notification.domain.NotificationType;
import com.cms.config.QuerydslConfig;
import com.cms.support.MariaDbContainerSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림 쿼리(PLAN-admin-notification.md §5-D·§7 ②, v3 R-7·R-12, D11)를 실제 MariaDB로 검증한다.
 * {@code @DataJpaTest}는 각 테스트를 트랜잭션으로 감싸고 롤백하므로 실DB에 데이터가 남지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class)
@ActiveProfiles("dev")
class NotificationRepositoryDataJpaTest extends MariaDbContainerSupport {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 12, 0);

    @Autowired NotificationRepository notificationRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired EntityManager em;

    private Member owner;
    private Member other;

    @BeforeEach
    void setUp() {
        owner = saveMember("notif-owner");
        other = saveMember("notif-other");
    }

    private Member saveMember(String prefix) {
        String unique = prefix + System.nanoTime();
        return memberRepository.saveAndFlush(Member.builder()
                .userId(unique.substring(0, Math.min(50, unique.length())))
                .pwd("encoded")
                .userName(prefix)
                .email(unique + "@test.example")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .createDate(NOW)
                .updateDate(NOW)
                .passwordChangedAt(NOW.minusDays(85))
                .build());
    }

    private Notification save(Member member, NotificationType type, LocalDateTime readAt) {
        return notificationRepository.saveAndFlush(Notification.builder()
                .memberId(member.getId())
                .type(type)
                .message("메시지 " + type)
                .readAt(readAt)
                .createDate(NOW)
                .build());
    }

    private LocalDateTime readAtOf(Long id) {
        em.clear();
        return notificationRepository.findById(id).orElseThrow().getReadAt();
    }

    @Test
    @DisplayName("목록은 본인 것만 id 내림차순이고 beforeId 커서로 이어 읽으면 중간에 새 알림이 삽입돼도 중복·누락이 없다")
    void findPage_cursor_noDuplicatesOrGaps() {
        Notification n1 = save(owner, NotificationType.ACCOUNT_STATUS, null);
        Notification n2 = save(owner, NotificationType.PERMISSION, null);
        Notification n3 = save(owner, NotificationType.PASSWORD_EXPIRY, null);
        save(other, NotificationType.ACCOUNT_STATUS, null);

        List<Notification> first = notificationRepository.findPage(owner.getId(), null, true, 2);
        assertThat(first).extracting(Notification::getId).containsExactly(n3.getId(), n2.getId());

        // 첫 페이지를 읽은 뒤 새 알림이 삽입돼도(offset 방식이면 밀려 중복) 커서는 다음 항목부터 이어진다
        Notification n4 = save(owner, NotificationType.ACCOUNT_STATUS, null);
        List<Notification> next = notificationRepository.findPage(owner.getId(), n2.getId(), true, 2);
        assertThat(next).extracting(Notification::getId).containsExactly(n1.getId());
        assertThat(notificationRepository.findPage(owner.getId(), null, true, 10))
                .extracting(Notification::getId).containsExactly(n4.getId(), n3.getId(), n2.getId(), n1.getId());
    }

    @Test
    @DisplayName("D11: 비ADMIN 조회는 ADMIN 전용 종류를 쿼리 조건으로 제외하고 미읽음 수에서도 뺀다 — ADMIN은 모두 본다")
    void adminOnlyType_hiddenFromNonAdminInListAndCount() {
        save(owner, NotificationType.ACCOUNT_STATUS, null);
        save(owner, NotificationType.ADMIN_ACCOUNT_LOCKED, null);

        assertThat(notificationRepository.findPage(owner.getId(), null, false, 10))
                .extracting(Notification::getType).containsExactly(NotificationType.ACCOUNT_STATUS);
        assertThat(notificationRepository.countUnread(owner.getId(), false)).isEqualTo(1);

        assertThat(notificationRepository.findPage(owner.getId(), null, true, 10)).hasSize(2);
        assertThat(notificationRepository.countUnread(owner.getId(), true)).isEqualTo(2);
    }

    @Test
    @DisplayName("미읽음 수는 본인 것만 세고 읽은 알림은 세지 않는다")
    void countUnread_ownOnly_excludesRead() {
        save(owner, NotificationType.ACCOUNT_STATUS, null);
        save(owner, NotificationType.PERMISSION, NOW);
        save(other, NotificationType.ACCOUNT_STATUS, null);

        assertThat(notificationRepository.countUnread(owner.getId(), true)).isEqualTo(1);
        assertThat(notificationRepository.countUnread(other.getId(), true)).isEqualTo(1);
    }

    @Test
    @DisplayName("단건 읽음은 원자적이다 — 첫 요청만 갱신하고 이미 읽은 알림의 최초 읽음 시각은 덮어쓰지 않는다")
    void markRead_isAtomic_keepsFirstReadAt() {
        Notification n = save(owner, NotificationType.ACCOUNT_STATUS, null);
        LocalDateTime first = NOW.plusMinutes(1);
        LocalDateTime second = NOW.plusMinutes(9);

        assertThat(notificationRepository.markRead(n.getId(), owner.getId(), first, true, NotificationType.ADMIN_ACCOUNT_LOCKED)).isEqualTo(1);
        assertThat(notificationRepository.markRead(n.getId(), owner.getId(), second, true, NotificationType.ADMIN_ACCOUNT_LOCKED)).isZero();

        assertThat(readAtOf(n.getId())).isEqualTo(first);
    }

    @Test
    @DisplayName("남의 알림은 읽음 처리되지 않는다(0행) — 호출자가 404로 숨긴다")
    void markRead_otherMembersNotification_isNotUpdated() {
        Notification n = save(owner, NotificationType.ACCOUNT_STATUS, null);

        assertThat(notificationRepository.markRead(n.getId(), other.getId(), NOW, true, NotificationType.ADMIN_ACCOUNT_LOCKED)).isZero();

        assertThat(readAtOf(n.getId())).isNull();
    }

    @Test
    @DisplayName("D11: 비ADMIN은 ADMIN 전용 알림을 읽음 처리할 수 없다(0행)")
    void markRead_adminOnlyType_notUpdatedForNonAdmin() {
        Notification n = save(owner, NotificationType.ADMIN_ACCOUNT_LOCKED, null);

        assertThat(notificationRepository.markRead(n.getId(), owner.getId(), NOW, false, NotificationType.ADMIN_ACCOUNT_LOCKED)).isZero();
        assertThat(readAtOf(n.getId())).isNull();

        assertThat(notificationRepository.markRead(n.getId(), owner.getId(), NOW, true, NotificationType.ADMIN_ACCOUNT_LOCKED)).isEqualTo(1);
    }

    @Test
    @DisplayName("전체 읽음은 본인 미읽음만 갱신하고 비ADMIN의 숨겨진 ADMIN 전용 알림은 미읽음으로 남긴다")
    void markAllRead_ownVisibleOnly() {
        Notification a = save(owner, NotificationType.ACCOUNT_STATUS, null);
        Notification hidden = save(owner, NotificationType.ADMIN_ACCOUNT_LOCKED, null);
        Notification alreadyRead = save(owner, NotificationType.PERMISSION, NOW.minusDays(1));
        Notification others = save(other, NotificationType.ACCOUNT_STATUS, null);

        int updated = notificationRepository.markAllRead(owner.getId(), NOW.plusMinutes(5), false, NotificationType.ADMIN_ACCOUNT_LOCKED);

        assertThat(updated).isEqualTo(1);
        assertThat(readAtOf(a.getId())).isEqualTo(NOW.plusMinutes(5));
        assertThat(readAtOf(hidden.getId())).isNull();                       // 재승격 시 다시 보이도록 미읽음 유지
        assertThat(readAtOf(alreadyRead.getId())).isEqualTo(NOW.minusDays(1)); // 기존 읽음 시각 보존
        assertThat(readAtOf(others.getId())).isNull();                        // 남의 것은 건드리지 않는다
    }

    @Test
    @DisplayName("정리는 본인의 읽은 지 기준 시각 이전인 알림만 지우고 미읽음·최근 읽음·남의 알림은 남긴다")
    void deleteReadBefore_onlyOldReadOfOwner() {
        Notification old = save(owner, NotificationType.ACCOUNT_STATUS, NOW.minusDays(91));
        Notification recent = save(owner, NotificationType.PERMISSION, NOW.minusDays(89));
        Notification unread = save(owner, NotificationType.PASSWORD_EXPIRY, null);
        Notification othersOld = save(other, NotificationType.ACCOUNT_STATUS, NOW.minusDays(200));

        int deleted = notificationRepository.deleteReadBefore(owner.getId(), NOW.minusDays(90));
        em.clear();

        assertThat(deleted).isEqualTo(1);
        assertThat(notificationRepository.findById(old.getId())).isEmpty();
        assertThat(notificationRepository.findById(recent.getId())).isPresent();
        assertThat(notificationRepository.findById(unread.getId())).isPresent();
        assertThat(notificationRepository.findById(othersOld.getId())).isPresent();
    }

    @Test
    @DisplayName("E3 조건부 INSERT: 스냅샷이 현재 password_changed_at과 같을 때만 1건 생성하고, 같은 주기 재시도는 예외 없이 무시하며, 불일치면 생성하지 않는다")
    void insertPasswordExpiryIfCurrent_conditionalAndIdempotent() {
        LocalDateTime changedAt = owner.getPasswordChangedAt();
        String key = "PASSWORD_EXPIRY:" + changedAt;

        // 최초 생성
        notificationRepository.insertPasswordExpiryIfCurrent(owner.getId(), "PASSWORD_EXPIRY", "곧 만료됩니다",
                "/admin/member/settings", key, NOW, changedAt);
        em.clear();
        assertThat(notificationRepository.findPage(owner.getId(), null, true, 10)).hasSize(1);

        // 같은 주기 재시도 — ON DUPLICATE KEY가 member.id와 모호하지 않게 notification.id로 한정돼 예외 없이 무시된다
        notificationRepository.insertPasswordExpiryIfCurrent(owner.getId(), "PASSWORD_EXPIRY", "곧 만료됩니다",
                "/admin/member/settings", key, NOW.plusHours(1), changedAt);
        em.clear();
        assertThat(notificationRepository.findPage(owner.getId(), null, true, 10)).hasSize(1);

        // 재확인 직후 비밀번호가 바뀐 경우(스냅샷 불일치) — 이전 주기의 알림은 만들지 않는다
        notificationRepository.insertPasswordExpiryIfCurrent(other.getId(), "PASSWORD_EXPIRY", "곧 만료됩니다",
                "/admin/member/settings", "PASSWORD_EXPIRY:stale", NOW, other.getPasswordChangedAt().minusDays(30));
        em.clear();
        assertThat(notificationRepository.findPage(other.getId(), null, true, 10)).isEmpty();
    }

    @Test
    @DisplayName("회원 키 수신자 조회(findActiveAdminIdsExcluding)는 ACTIVE ADMIN만, 제외 대상 본인은 빼고 읽는다")
    void findActiveAdminIdsExcluding_filters() {
        Member manager = memberRepository.saveAndFlush(Member.builder()
                .userId("notif-mgr" + System.nanoTime()).pwd("e").userName("m").email("m" + System.nanoTime() + "@t.example")
                .userType(Role.ROLE_MANAGER).status(MemberStatus.ACTIVE).createDate(NOW).updateDate(NOW).passwordChangedAt(NOW).build());
        Member locked = memberRepository.saveAndFlush(Member.builder()
                .userId("notif-lck" + System.nanoTime()).pwd("e").userName("l").email("l" + System.nanoTime() + "@t.example")
                .userType(Role.ROLE_ADMIN).status(MemberStatus.LOCKED).createDate(NOW).updateDate(NOW).passwordChangedAt(NOW).build());

        List<Long> ids = memberRepository.findActiveAdminIdsExcluding(owner.getId());

        assertThat(ids).contains(other.getId()).doesNotContain(owner.getId(), manager.getId(), locked.getId());
    }
}
