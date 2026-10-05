package com.cms.admin.notification.service;

import com.cms.admin.notification.domain.Notification;
import com.cms.admin.notification.domain.NotificationType;
import com.cms.admin.notification.dto.NotificationPageResponse;
import com.cms.admin.notification.dto.NotificationReadResponse;
import com.cms.admin.notification.dto.NotificationResponse;
import com.cms.admin.notification.repository.NotificationRepository;
import com.cms.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 본인 알림 조회·읽음 처리(PLAN-admin-notification.md §5-D). 모든 쿼리는 호출자가 넘긴 <b>본인 회원 ID</b>로 제한하고 경로로 회원 ID를 받지 않는다(IDOR 방지).
 * {@code includeAdminOnly}는 요청 시점에 ADMIN인 사용자만 true다 — ADMIN 전용 종류(E4)는 강등된 사용자에게 보이지 않는다(D11).
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    static final int DEFAULT_SIZE = 10;
    static final int MAX_SIZE = 50;

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    /** 최신순(id 내림차순) 목록. {@code beforeId}가 있으면 그보다 작은 id부터 이어 읽는다(커서). */
    @Transactional(readOnly = true)
    public NotificationPageResponse list(Long memberId, boolean includeAdminOnly, Long beforeId, Integer requestedSize) {
        int size = clampSize(requestedSize);
        // 한 건 더 읽어 다음 페이지 존재 여부(hasMore)를 판정한다.
        List<Notification> fetched = notificationRepository.findPage(memberId, beforeId, includeAdminOnly, size + 1);
        boolean hasMore = fetched.size() > size;
        List<NotificationResponse> content = fetched.stream()
                .limit(size)
                .map(NotificationResponse::from)
                .toList();
        long unreadCount = notificationRepository.countUnread(memberId, includeAdminOnly);
        return new NotificationPageResponse(content, unreadCount, hasMore);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long memberId, boolean includeAdminOnly) {
        return notificationRepository.countUnread(memberId, includeAdminOnly);
    }

    /**
     * 단건 읽음(멱등). 원자적 조건부 UPDATE로 처리해 경합해도 최초 읽음 시각이 보존된다.
     * 없거나 남의 것이거나 비ADMIN에게 숨겨진 종류면 404(존재 숨김), 이미 읽은 알림이면 기존 읽음 시각을 유지한 채 그대로 돌려준다.
     */
    @Transactional
    public NotificationReadResponse markRead(Long memberId, boolean includeAdminOnly, Long notificationId) {
        LocalDateTime now = LocalDateTime.now(clock);
        notificationRepository.markRead(notificationId, memberId, now, includeAdminOnly, NotificationType.ADMIN_ACCOUNT_LOCKED);

        // 갱신 행 수와 무관하게 소유·가시성을 다시 확인한다 — 0행이면 "이미 읽음"(기존 read_at 유지)이거나 "없음·남의 것·숨겨진 종류"다.
        Notification notification = notificationRepository.findByIdAndMemberId(notificationId, memberId)
                .filter(n -> includeAdminOnly || n.getType() != NotificationType.ADMIN_ACCOUNT_LOCKED)
                .orElseThrow(() -> new ResourceNotFoundException("알림을 찾을 수 없습니다."));
        return NotificationReadResponse.single(NotificationResponse.from(notification),
                notificationRepository.countUnread(memberId, includeAdminOnly));
    }

    /** 본인 미읽음(보이는 것) 전부 읽음. 비ADMIN의 숨겨진 ADMIN 전용 알림은 미읽음으로 남는다. */
    @Transactional
    public NotificationReadResponse markAllRead(Long memberId, boolean includeAdminOnly) {
        int updated = notificationRepository.markAllRead(memberId, LocalDateTime.now(clock), includeAdminOnly,
                NotificationType.ADMIN_ACCOUNT_LOCKED);
        return NotificationReadResponse.all(updated, notificationRepository.countUnread(memberId, includeAdminOnly));
    }

    private static int clampSize(Integer requested) {
        if (requested == null || requested < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(requested, MAX_SIZE);
    }
}
