package com.cms.admin.notification.service;

import com.cms.admin.notification.domain.Notification;
import com.cms.admin.notification.domain.NotificationType;
import com.cms.admin.notification.dto.NotificationPageResponse;
import com.cms.admin.notification.dto.NotificationReadResponse;
import com.cms.admin.notification.repository.NotificationRepository;
import com.cms.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 조회·읽음 서비스의 흐름(PLAN-admin-notification.md §5-D). 쿼리 의미(커서·D11 필터·원자성)는 리포지토리 시험이 실제 MariaDB로 고정한다. */
class NotificationServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime FIXED = LocalDateTime.of(2026, 10, 5, 12, 0);
    private static final long ME = 7L;

    private NotificationRepository repository;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        Clock clock = Clock.fixed(FIXED.atZone(KST).toInstant(), KST);
        service = new NotificationService(repository, clock);
    }

    private static Notification notification(long id, NotificationType type, LocalDateTime readAt) {
        return Notification.builder().id(id).memberId(ME).type(type).message("m" + id).createDate(FIXED).readAt(readAt).build();
    }

    private static List<Notification> notifications(int count) {
        List<Notification> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(notification(100 - i, NotificationType.ACCOUNT_STATUS, null));
        }
        return list;
    }

    @Test
    @DisplayName("목록은 size+1건을 읽어 다음 페이지가 있으면 hasMore=true이고 size건만 돌려준다")
    void list_hasMore_whenMoreThanSize() {
        when(repository.findPage(eq(ME), eq(null), eq(true), eq(4))).thenReturn(notifications(4));
        when(repository.countUnread(ME, true)).thenReturn(4L);

        NotificationPageResponse page = service.list(ME, true, null, 3);

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.isHasMore()).isTrue();
        assertThat(page.getUnreadCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("size건 이하면 hasMore=false")
    void list_noMore() {
        when(repository.findPage(eq(ME), eq(null), eq(true), eq(4))).thenReturn(notifications(3));

        assertThat(service.list(ME, true, null, 3).isHasMore()).isFalse();
    }

    @Test
    @DisplayName("size는 기본 10·상한 50으로 보정되고 beforeId와 열람 시점 ADMIN 여부가 그대로 쿼리에 전달된다")
    void list_clampsSize_andPassesCursorAndAdminFlag() {
        when(repository.findPage(anyLong(), any(), anyBoolean(), anyInt())).thenReturn(List.of());

        service.list(ME, false, 55L, null);
        verify(repository).findPage(ME, 55L, false, NotificationService.DEFAULT_SIZE + 1);

        service.list(ME, true, null, 9999);
        verify(repository).findPage(ME, null, true, NotificationService.MAX_SIZE + 1);

        service.list(ME, true, null, 0);
        verify(repository).findPage(ME, null, true, NotificationService.DEFAULT_SIZE + 1);
    }

    @Test
    @DisplayName("단건 읽음 — 갱신 뒤 항목과 미읽음 수를 돌려준다")
    void markRead_returnsItemAndUnreadCount() {
        when(repository.markRead(eq(5L), eq(ME), eq(FIXED), eq(true), eq(NotificationType.ADMIN_ACCOUNT_LOCKED))).thenReturn(1);
        when(repository.findByIdAndMemberId(5L, ME)).thenReturn(Optional.of(notification(5, NotificationType.PERMISSION, FIXED)));
        when(repository.countUnread(ME, true)).thenReturn(2L);

        NotificationReadResponse response = service.markRead(ME, true, 5L);

        assertThat(response.getNotification().getId()).isEqualTo(5L);
        assertThat(response.getNotification().isRead()).isTrue();
        assertThat(response.getUnreadCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("이미 읽은 알림(갱신 0행)은 404가 아니라 기존 읽음 상태 그대로 200으로 돌려준다(멱등)")
    void markRead_alreadyRead_isIdempotent() {
        LocalDateTime firstRead = FIXED.minusDays(1);
        when(repository.markRead(anyLong(), anyLong(), any(), anyBoolean(), any())).thenReturn(0);
        when(repository.findByIdAndMemberId(5L, ME)).thenReturn(Optional.of(notification(5, NotificationType.PERMISSION, firstRead)));

        NotificationReadResponse response = service.markRead(ME, true, 5L);

        assertThat(response.getNotification().isRead()).isTrue();
    }

    @Test
    @DisplayName("없거나 남의 알림이면 404(존재 숨김)")
    void markRead_notOwnedOrMissing_notFound() {
        when(repository.markRead(anyLong(), anyLong(), any(), anyBoolean(), any())).thenReturn(0);
        when(repository.findByIdAndMemberId(5L, ME)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(ME, true, 5L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("D11: 비ADMIN은 ADMIN 전용 종류 알림을 읽음 처리·조회할 수 없다(404) — 갱신 0행 뒤 가시성도 다시 확인한다")
    void markRead_adminOnlyType_notFoundForNonAdmin() {
        when(repository.markRead(anyLong(), anyLong(), any(), eq(false), any())).thenReturn(0);
        when(repository.findByIdAndMemberId(5L, ME))
                .thenReturn(Optional.of(notification(5, NotificationType.ADMIN_ACCOUNT_LOCKED, null)));

        assertThatThrownBy(() -> service.markRead(ME, false, 5L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("전체 읽음 — 갱신 건수와 남은 미읽음 수를 돌려준다")
    void markAllRead_returnsUpdatedAndUnread() {
        when(repository.markAllRead(eq(ME), eq(FIXED), eq(false), eq(NotificationType.ADMIN_ACCOUNT_LOCKED))).thenReturn(3);
        when(repository.countUnread(ME, false)).thenReturn(0L);

        NotificationReadResponse response = service.markAllRead(ME, false);

        assertThat(response.getUpdated()).isEqualTo(3);
        assertThat(response.getUnreadCount()).isZero();
        assertThat(response.getNotification()).isNull();
    }
}
