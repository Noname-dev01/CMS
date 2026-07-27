package com.cms.admin.log.constant;

import java.util.List;

/**
 * AdminActionLogged 어노테이션의 actionType 상수 단일 출처.
 * 새 액션 타입 추가 시 이 클래스에만 추가하면 드롭다운과 동기화 테스트에 자동 반영된다.
 */
public final class AdminActionTypes {

    public static final String ADMIN_CREATE      = "ADMIN_CREATE";
    public static final String ADMIN_UPDATE      = "ADMIN_UPDATE";
    public static final String PASSWORD_CHANGE   = "PASSWORD_CHANGE";
    public static final String MENU_CREATE       = "MENU_CREATE";
    public static final String MENU_UPDATE       = "MENU_UPDATE";
    public static final String MENU_DEACTIVATE   = "MENU_DEACTIVATE";
    /** 로그인 연속 실패로 인한 계정 자동 잠금 (미인증 흐름 — actionUserId null로 기록) */
    public static final String ACCOUNT_AUTO_LOCK = "ACCOUNT_AUTO_LOCK";
    public static final String NOTICE_CREATE     = "NOTICE_CREATE";
    public static final String NOTICE_UPDATE     = "NOTICE_UPDATE";
    public static final String NOTICE_DELETE     = "NOTICE_DELETE";
    public static final String NOTICE_ATTACHMENT_UPLOAD = "NOTICE_ATTACHMENT_UPLOAD";
    public static final String NOTICE_ATTACHMENT_DELETE = "NOTICE_ATTACHMENT_DELETE";

    /** 드롭다운·동기화 테스트 공용 — 새 타입 추가 시 이 목록도 함께 갱신 */
    public static final List<String> ALL = List.of(
            ADMIN_CREATE, ADMIN_UPDATE, PASSWORD_CHANGE, MENU_CREATE, MENU_UPDATE, MENU_DEACTIVATE,
            ACCOUNT_AUTO_LOCK, NOTICE_CREATE, NOTICE_UPDATE, NOTICE_DELETE,
            NOTICE_ATTACHMENT_UPLOAD, NOTICE_ATTACHMENT_DELETE
    );

    private AdminActionTypes() {}
}
