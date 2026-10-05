package com.cms.admin.notification.domain;

/** 알림 종류(PLAN-admin-notification.md §0). DB에는 문자열로 저장된다. */
public enum NotificationType {

    /** E1 — 내 계정 상태 변경(관리자 수정·로그인 연속 실패 자동 잠금). */
    ACCOUNT_STATUS,

    /** E2 — 내 권한 변경(역할 변경·개별 권한 부여·회수). */
    PERMISSION,

    /** E3 — 비밀번호 만료 7일 이내. */
    PASSWORD_EXPIRY,

    /**
     * E4 — 다른 관리자 계정의 자동 잠금. 다른 계정의 아이디·잠금 시각이 담기므로 <b>열람 시점에 ADMIN인 사용자에게만</b> 보인다(D11).
     */
    ADMIN_ACCOUNT_LOCKED
}
