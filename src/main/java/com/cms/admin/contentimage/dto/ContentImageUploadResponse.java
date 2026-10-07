package com.cms.admin.contentimage.dto;

import com.cms.admin.contentimage.domain.ContentImage;
import com.cms.common.html.HtmlContentSanitizer;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 본문 이미지 업로드 응답. 편집기는 {@code url}을 그대로 {@code img src}로 넣는다 — sanitizer가 허용하는 유일한 형식이다.
 * {@code getId()}는 감사 로그 targetId 추출({@code targetIdExpression = "id"})에 쓰인다.
 */
@Schema(description = "본문 이미지 업로드 결과")
public record ContentImageUploadResponse(
        @Schema(description = "이미지 ID", example = "12") Long id,
        @Schema(description = "본문에 넣을 이미지 경로", example = "/content-images/12") String url) {

    public static ContentImageUploadResponse from(ContentImage image) {
        return new ContentImageUploadResponse(image.getId(), HtmlContentSanitizer.CONTENT_IMAGE_PATH_PREFIX + image.getId());
    }

    public Long getId() {
        return id;
    }
}
