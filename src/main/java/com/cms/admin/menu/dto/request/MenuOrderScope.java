package com.cms.admin.menu.dto.request;

/**
 * 형제 순서 재조정 요청이 담은 범위.
 * ALL은 형제 전체(활성+비활성), ACTIVE는 활성 형제만이다.
 */
public enum MenuOrderScope {
    ALL,
    ACTIVE
}
