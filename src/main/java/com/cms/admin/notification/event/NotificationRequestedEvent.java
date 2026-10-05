package com.cms.admin.notification.event;

import com.cms.admin.notification.domain.NotificationType;

/**
 * 알림 생성 요청(E1·E2). 원 트랜잭션은 이 이벤트를 <b>발행만</b> 하고, 커밋 뒤({@code AFTER_COMMIT}) {@code NotificationEventListener}가 저장한다 —
 * 롤백되면 알림이 생기지 않고, 알림 저장이 실패해도 원 변경은 이미 커밋돼 있다(최선 노력, PLAN-admin-notification.md §5-C).
 *
 * @param message 서버가 상수 템플릿과 enum 라벨로 조립한 문장(사용자 입력 없음)
 * @param linkUrl 같은 출처 경로 또는 null
 */
public record NotificationRequestedEvent(Long memberId, NotificationType type, String message, String linkUrl) {
}
