package com.cms.publicweb.popup.dto;

/** 공개 팝업 이미지 다운로드에 필요한 파일 참조만 담는다(노출 여부·기간 등은 싣지 않는다). */
public record PublicPopupImageRef(String storageKey, String namespace, String contentType) {
}
