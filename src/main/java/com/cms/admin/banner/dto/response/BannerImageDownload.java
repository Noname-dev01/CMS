package com.cms.admin.banner.dto.response;

/** 관리자 미리보기용 배너 이미지(노출 여부와 무관). 파일이 5MB 이하라 byte[]로 읽는다. */
public record BannerImageDownload(String contentType, byte[] content) {
}
