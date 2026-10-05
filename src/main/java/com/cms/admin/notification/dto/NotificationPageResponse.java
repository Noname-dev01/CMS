package com.cms.admin.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * 알림 목록(커서 방식). 다음 페이지는 마지막 항목의 {@code id}를 {@code beforeId}로 보낸다.
 * {@code unreadCount}는 요청자가 볼 수 있는 미읽음 수(D11 필터 적용).
 */
@Getter
@AllArgsConstructor
public class NotificationPageResponse {

    private List<NotificationResponse> content;
    private long unreadCount;
    private boolean hasMore;
}
