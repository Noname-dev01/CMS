package com.cms.admin.popup.domain;

/** 팝업 종류. 생성 후 바꿀 수 없다(PLAN-popup.md 쟁점 1) — 바꾸면 이미지 파일 정리·본문 비우기 경로가 하나씩 더 생긴다. */
public enum PopupType {
    /** 이미지 한 장(+선택 링크). 파일은 {@code FileStorage}의 {@code popup} 네임스페이스에 둔다. */
    IMAGE,
    /** 편집기로 쓴 HTML 본문. 이미지는 넣을 수 없다. */
    HTML
}
