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
    /** 더는 발생하지 않음 — 비활성화는 PATCH useYn=false(MENU_UPDATE)로 일원화. 과거 로그 호환으로 유지 */
    public static final String MENU_DEACTIVATE   = "MENU_DEACTIVATE";
    /** 메뉴 영구삭제(하드 삭제) — targetId = 삭제된 메뉴 번호, targetLabel = "이름 (URL)" */
    public static final String MENU_DELETE       = "MENU_DELETE";
    /** 같은 부모 아래 형제 메뉴 순서 재조정 (targetId = 부모 메뉴 번호, 최상위는 null) */
    public static final String MENU_REORDER      = "MENU_REORDER";
    /** 메뉴의 부모(상위 메뉴) 변경 (targetId = 이동한 메뉴 번호) */
    public static final String MENU_MOVE         = "MENU_MOVE";
    /** 메뉴 구조(부모·순서) 일괄 반영 (targetId 없음 — 구조 전체가 대상) */
    public static final String MENU_STRUCTURE_APPLY = "MENU_STRUCTURE_APPLY";
    /** 로그인 연속 실패로 인한 계정 자동 잠금 (미인증 흐름 — actionUserId null로 기록) */
    public static final String ACCOUNT_AUTO_LOCK = "ACCOUNT_AUTO_LOCK";
    public static final String NOTICE_CREATE     = "NOTICE_CREATE";
    public static final String NOTICE_UPDATE     = "NOTICE_UPDATE";
    public static final String NOTICE_DELETE     = "NOTICE_DELETE";
    public static final String NOTICE_ATTACHMENT_UPLOAD = "NOTICE_ATTACHMENT_UPLOAD";
    public static final String NOTICE_ATTACHMENT_DELETE = "NOTICE_ATTACHMENT_DELETE";
    /** MANAGER 회원의 권한 매트릭스 저장 — targetType = MEMBER_PERMISSION, targetId = 대상 회원 ID, targetLabel = "v3→v4: +공지사항.생성, -공지사항.삭제" */
    public static final String PERMISSION_UPDATE = "PERMISSION_UPDATE";
    /** 쪽지 발송 — targetType = MEMBER, targetId = 수신자 회원 ID. 제목·본문은 기록하지 않는다(targetLabel 없음, 실패 errorMessage는 고정 문구) */
    public static final String MESSAGE_SEND = "MESSAGE_SEND";
    /** 편집기 본문 이미지 업로드 — targetType = CONTENT_IMAGE, targetId = 이미지 ID */
    public static final String CONTENT_IMAGE_UPLOAD = "CONTENT_IMAGE_UPLOAD";
    /** 게시판 생성·수정 — targetType = BOARD, targetId = 게시판 ID. 이름은 사용자 입력이라 targetLabel을 두지 않는다 */
    public static final String BOARD_CREATE = "BOARD_CREATE";
    public static final String BOARD_UPDATE = "BOARD_UPDATE";
    /** 게시판 삭제(소프트) — targetType = BOARD, targetId = 게시판 ID. 살아 있는 게시글이 없을 때만 가능하다 */
    public static final String BOARD_DELETE = "BOARD_DELETE";
    /** 게시글 생성·수정·삭제 — targetType = POST, targetId = 게시글 ID. 제목은 사용자 입력이라 targetLabel을 두지 않는다 */
    public static final String POST_CREATE = "POST_CREATE";
    public static final String POST_UPDATE = "POST_UPDATE";
    public static final String POST_DELETE = "POST_DELETE";
    /** 게시글 첨부 업로드·삭제 — targetType = POST_ATTACHMENT, targetId = 첨부 ID */
    public static final String POST_ATTACHMENT_UPLOAD = "POST_ATTACHMENT_UPLOAD";
    public static final String POST_ATTACHMENT_DELETE = "POST_ATTACHMENT_DELETE";
    /** 배너 등록·수정·삭제 — targetType = BANNER, targetId = 배너 ID. 제목은 사용자 입력이라 targetLabel을 두지 않는다 */
    public static final String BANNER_CREATE = "BANNER_CREATE";
    public static final String BANNER_UPDATE = "BANNER_UPDATE";
    public static final String BANNER_DELETE = "BANNER_DELETE";
    /** 배너 표시 순서 일괄 저장 — targetType = BANNER, targetId 없음(순서 전체가 대상) */
    public static final String BANNER_ORDER = "BANNER_ORDER";
    /** 세션 강제 만료(단일·회원 단위) — targetType = MEMBER, targetId = 세션 소유 회원 ID(성공 행만), targetLabel = "세션 n개 만료". 세션 ID·핸들은 기록하지 않는다 */
    public static final String SESSION_EXPIRE = "SESSION_EXPIRE";

    /** 드롭다운·동기화 테스트 공용 — 새 타입 추가 시 이 목록도 함께 갱신 */
    public static final List<String> ALL = List.of(
            ADMIN_CREATE, ADMIN_UPDATE, PASSWORD_CHANGE, MENU_CREATE, MENU_UPDATE, MENU_DEACTIVATE, MENU_DELETE, MENU_REORDER, MENU_MOVE, MENU_STRUCTURE_APPLY,
            ACCOUNT_AUTO_LOCK, NOTICE_CREATE, NOTICE_UPDATE, NOTICE_DELETE,
            NOTICE_ATTACHMENT_UPLOAD, NOTICE_ATTACHMENT_DELETE, PERMISSION_UPDATE, MESSAGE_SEND, CONTENT_IMAGE_UPLOAD,
            BOARD_CREATE, BOARD_UPDATE, BOARD_DELETE,
            POST_CREATE, POST_UPDATE, POST_DELETE, POST_ATTACHMENT_UPLOAD, POST_ATTACHMENT_DELETE,
            BANNER_CREATE, BANNER_UPDATE, BANNER_DELETE, BANNER_ORDER,
            SESSION_EXPIRE
    );

    private AdminActionTypes() {}
}
