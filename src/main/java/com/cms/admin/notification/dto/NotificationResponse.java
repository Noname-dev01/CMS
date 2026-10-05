package com.cms.admin.notification.dto;

import com.cms.admin.notification.domain.Notification;
import com.cms.admin.notification.domain.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/** 알림 한 건. 엔티티를 직접 노출하지 않는다 — 수신 회원 ID·중복 방지 키 등 화면에 필요 없는 값은 담지 않는다. */
@Getter
@Builder
@AllArgsConstructor
public class NotificationResponse {

    private Long id;
    private NotificationType type;
    private String message;
    /** 같은 출처 경로(없을 수 있음). */
    private String linkUrl;
    private boolean read;
    private LocalDateTime createDate;

    public static NotificationResponse from(Notification notification) {
        return NotificationResponse.builder()
                .id(notification.getId())
                .type(notification.getType())
                .message(notification.getMessage())
                .linkUrl(notification.getLinkUrl())
                .read(notification.isRead())
                .createDate(notification.getCreateDate())
                .build();
    }
}
