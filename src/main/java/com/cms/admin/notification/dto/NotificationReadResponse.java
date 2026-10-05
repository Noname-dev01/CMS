package com.cms.admin.notification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 읽음 처리 결과. 단건은 {@code notification}(갱신 뒤 항목)이, 전체 읽음은 {@code updated}(방금 읽음 처리한 건수)가 채워진다.
 * 화면은 배지를 항상 이 응답이 아니라 큐 종료 뒤 재조회한 {@code unreadCount}로 확정한다(계획서 v4 R-15).
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@AllArgsConstructor
public class NotificationReadResponse {

    private NotificationResponse notification;
    private Integer updated;
    private long unreadCount;

    public static NotificationReadResponse single(NotificationResponse notification, long unreadCount) {
        return new NotificationReadResponse(notification, null, unreadCount);
    }

    public static NotificationReadResponse all(int updated, long unreadCount) {
        return new NotificationReadResponse(null, updated, unreadCount);
    }
}
