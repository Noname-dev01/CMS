package com.cms.admin.notification.service;

import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.notification.NotificationMessages;
import com.cms.admin.notification.domain.Notification;
import com.cms.admin.notification.domain.NotificationType;
import com.cms.admin.notification.repository.NotificationRepository;
import com.cms.config.auth.AdminAccountAutoLockEvent;
import com.cms.config.auth.PasswordExpiryService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 알림 생성(PLAN-admin-notification.md §5-C). 커밋 뒤 리스너가 호출하므로 원 트랜잭션과 격리된 {@code REQUIRES_NEW}로 저장한다 —
 * 호출자(리스너)가 예외를 삼키고 ERROR 로그만 남긴다(최선 노력).
 */
@Service
@RequiredArgsConstructor
public class NotificationRecorder {

    /** 비밀번호 만료 며칠 전부터 알리는가(D9 — 비밀번호 주기당 1회). */
    static final int EXPIRY_NOTICE_DAYS = 7;

    /** 읽은 알림 보관 일수(D7) — 이 일수가 지나면 로그인 성공 시 정리된다. */
    static final int READ_RETENTION_DAYS = 90;

    private final NotificationRepository notificationRepository;
    private final MemberRepository memberRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /** E1·E2 — 한 건 저장. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long memberId, NotificationType type, String message, String linkUrl) {
        notificationRepository.save(Notification.builder()
                .memberId(memberId)
                .type(type)
                .message(message)
                .linkUrl(linkUrl)
                .createDate(LocalDateTime.now(clock))
                .build());
    }

    /**
     * E3 — 비밀번호 만료 7일 이내면 알림을 만든다. 판정은 기존 만료 쿼리(passwordChangedAt <= now − 90일, 시·분·초 비교)와 같은 기준이다:
     * {@code expiresAt = passwordChangedAt + 90일}이 {@code (now, now + 7일]} 안이면 생성(v3 R-4 — 날짜 차이로 계산하지 않는다).
     * {@code passwordChangedAt}은 로그인 재확인이 읽은 fresh 스냅샷이고, 저장은 "회원의 현재 값이 스냅샷과 같을 때만" 조건부로 해
     * 재확인 직후 비밀번호가 바뀌어도 이전 주기의 알림이 생기지 않는다(R-5). 같은 주기 중복은 예외 없이 무시된다(R-6, R-14).
     *
     * <p>로그인 경로에서 호출되므로 {@code REQUIRES_NEW}를 쓰지 않는다 — 호출자(핸들러)는 트랜잭션 밖이라 REQUIRED가 새 트랜잭션을 연다.
     */
    @Transactional
    public void recordPasswordExpiryIfNear(Long memberId, LocalDateTime passwordChangedAt) {
        if (passwordChangedAt == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = passwordChangedAt.plusDays(PasswordExpiryService.PASSWORD_EXPIRY_DAYS);
        if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plusDays(EXPIRY_NOTICE_DAYS))) {
            return;   // 이미 만료됐거나(로그인 불가 경로) 아직 7일보다 많이 남았다
        }
        notificationRepository.insertPasswordExpiryIfCurrent(memberId, NotificationType.PASSWORD_EXPIRY.name(),
                NotificationMessages.passwordExpiring(expiresAt), "/admin/member/settings",
                "PASSWORD_EXPIRY:" + passwordChangedAt, now, passwordChangedAt);
    }

    /**
     * 읽은 지 90일이 지난 본인 알림을 지운다(D7·D8) — 미읽음은 지우지 않는다. 스케줄러가 없어 로그인 성공 시 그 회원 것만 정리한다.
     * E3 생성과 <b>별개 트랜잭션</b>이다(같은 트랜잭션이면 생성 쪽 실패가 정리까지 막는다, v3 R-6).
     */
    @Transactional
    public void purgeOldReadNotifications(Long memberId) {
        notificationRepository.deleteReadBefore(memberId, LocalDateTime.now(clock).minusDays(READ_RETENTION_DAYS));
    }

    /**
     * 자동 잠금 — 한 트랜잭션에서 ① 잠긴 본인에게 E1 1건 ② 다른 ACTIVE ADMIN 전원에게 E4를 <b>다중 행 INSERT 한 문장</b>으로 저장한다.
     * 수신자 ID는 <b>잠금 없는 일반 SELECT</b>로 읽는다 — {@code INSERT … SELECT … FROM member}는 REPEATABLE READ에서 원본 읽기가 공유 잠금을 걸어
     * 수신자가 아닌 회원의 변경까지 지연시킬 수 있다(v6 R-17). 알림 메시지의 아이디·시각은 이벤트가 담은 정규 값이다(v3 R-1).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAutoLock(AdminAccountAutoLockEvent event) {
        LocalDateTime now = LocalDateTime.now(clock);

        notificationRepository.save(Notification.builder()
                .memberId(event.memberId())
                .type(NotificationType.ACCOUNT_STATUS)
                .message(NotificationMessages.autoLockedSelf(event.lockedAt()))
                .createDate(now)
                .build());

        List<Long> recipients = memberRepository.findActiveAdminIdsExcluding(event.memberId());
        if (recipients.isEmpty()) {
            return;
        }
        String message = NotificationMessages.adminAccountLocked(event.canonicalUserId(), event.lockedAt());
        String linkUrl = "/admin/member/manage?id=" + event.memberId();

        StringBuilder sql = new StringBuilder(
                "INSERT INTO notification (member_id, type, message, link_url, create_date) VALUES ");
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < recipients.size(); i++) {
            sql.append(i == 0 ? "(?, ?, ?, ?, ?)" : ", (?, ?, ?, ?, ?)");
            args.add(recipients.get(i));
            args.add(NotificationType.ADMIN_ACCOUNT_LOCKED.name());
            args.add(message);
            args.add(linkUrl);
            args.add(now);
        }
        jdbcTemplate.update(sql.toString(), args.toArray());
    }
}
