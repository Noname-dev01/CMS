package com.cms.admin.menu;

/**
 * 메뉴의 사이드바 노출 범위.
 *
 * <p>{@link com.cms.admin.member.domain.Role}과 별개의 enum으로 두는 이유:
 * 사이드바 노출은 "공용(관리영역 접근 가능한 모든 역할) / ADMIN 전용" 2단계 구분만
 * 필요하며, '공용'은 Role로 표현할 수 없다. 또한 수정 API의 부분 수정 시맨틱
 * (null=기존값 유지)에서 ADMIN 전용을 공용으로 되돌리려면 명시적 값(ALL)이 필요하다.
 */
public enum MenuAccessRole {

    /** 관리영역 접근 가능한 모든 역할(ADMIN·MANAGER)에게 노출 */
    ALL,

    /** ROLE_ADMIN에게만 노출 */
    ADMIN
}
