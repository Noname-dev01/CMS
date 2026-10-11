package com.cms.admin.popup.dto.response;

/** 관리자 미리보기용 팝업 이미지(노출 여부와 무관). 파일이 2MB 이하라 byte[]로 읽는다. */
public record PopupImageDownload(String contentType, byte[] content) {
}
