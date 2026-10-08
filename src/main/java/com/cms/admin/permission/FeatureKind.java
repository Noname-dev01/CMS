package com.cms.admin.permission;

/** 기능을 누구에게 여는지 — 코드(카탈로그)가 정하고 DB는 DELEGABLE·BOARD_SCOPED에 대해서만 MANAGER 허용 행을 둔다. */
public enum FeatureKind {
    /** 로그인한 모든 관리자(ADMIN·MANAGER)에게 상시 허용. DB를 보지 않고 권한관리에서 끌 수 없다. */
    ALWAYS,
    /** ADMIN은 항상 허용, MANAGER는 DB 허용 행({@code member_permission})이 있을 때만 허용. 위임 가능한 기능. */
    DELEGABLE,
    /**
     * ADMIN은 항상 허용, MANAGER는 <b>게시판별</b> 허용 행({@code member_board_permission})으로 판정한다(PLAN-board.md 쟁점 2).
     * 기능 단위 판정(URL 게이트·사이드바)은 "어느 게시판에서라도 그 동작이 유효한가"이고, 실제 동작은 핸들러의
     * {@link RequireBoardPermission}이 게시판 ID로 판정한다. {@code member_permission} 행으로는 부여할 수 없다.
     */
    BOARD_SCOPED,
    /** ADMIN 전용. 어떤 요청·DB 값으로도 MANAGER에게 열리지 않는다(위임 불가). */
    ADMIN_ONLY
}
