package com.cms.admin.media.service;

import com.cms.admin.contentimage.domain.ContentImage;

/**
 * 소유자(배너·팝업)에 연결된 이미지의 파일 위치·메타. 소유자 테이블의 사본 컬럼 대신 {@code content_image_ref → content_image}로 읽은 값이다
 * (PLAN-media-library.md D4). {@code namespace}가 빈 문자열이면 루트 영역이다.
 */
public record MediaImageInfo(Long imageId, String storageKey, String namespace, String contentType, Long fileSize,
                             String originalName) {

    public static MediaImageInfo from(ContentImage image) {
        return new MediaImageInfo(image.getId(), image.getStorageKey(), image.getNamespace(), image.getContentType(),
                image.getFileSize(), image.getOriginalName());
    }

    public boolean isRootNamespace() {
        return namespace == null || namespace.isEmpty();
    }
}
