package com.cms.admin.log.constant;

import java.util.List;

/**
 * AdminActionLogged 어노테이션의 actionType 상수 단일 출처.
 * 새 액션 타입 추가 시 이 클래스에만 추가하면 드롭다운과 동기화 테스트에 자동 반영된다.
 */
public final class AdminActionTypes {

    public static final String ADMIN_CREATE    = "ADMIN_CREATE";
    public static final String PASSWORD_CHANGE = "PASSWORD_CHANGE";

    /** 드롭다운·동기화 테스트 공용 — 새 타입 추가 시 이 목록도 함께 갱신 */
    public static final List<String> ALL = List.of(ADMIN_CREATE, PASSWORD_CHANGE);

    private AdminActionTypes() {}
}
