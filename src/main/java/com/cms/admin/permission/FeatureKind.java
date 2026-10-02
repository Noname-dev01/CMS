package com.cms.admin.permission;

/** 기능을 누구에게 여는지 — 코드(카탈로그)가 정하고 DB는 DELEGABLE에 대해서만 MANAGER 허용 행을 둔다. */
public enum FeatureKind {
    /** 로그인한 모든 관리자(ADMIN·MANAGER)에게 상시 허용. DB를 보지 않고 권한관리에서 끌 수 없다. */
    ALWAYS,
    /** ADMIN은 항상 허용, MANAGER는 DB 허용 행이 있을 때만 허용. 위임 가능한 기능. */
    DELEGABLE,
    /** ADMIN 전용. 어떤 요청·DB 값으로도 MANAGER에게 열리지 않는다(위임 불가). */
    ADMIN_ONLY
}
