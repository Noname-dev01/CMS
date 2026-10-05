package com.cms.admin.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 배지용 미읽음 수. */
@Getter
@AllArgsConstructor
public class NotificationUnreadCountResponse {

    private long unreadCount;
}
