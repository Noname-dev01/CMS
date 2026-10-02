package com.cms.admin.permission;

/** 기능에 대해 허용할 수 있는 동작. 권한관리에서 기능×동작 단위로 켜고 끈다. 라벨은 감사 라벨·API 응답·화면 열 머리글의 단일 출처다. */
public enum PermissionAction {
    READ("조회"),
    CREATE("생성"),
    UPDATE("수정"),
    DELETE("삭제");

    private final String label;

    PermissionAction(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
