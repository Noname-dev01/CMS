package com.cms.admin.media.dto;

import com.cms.admin.contentimage.domain.ContentImage;
import com.cms.admin.contentimage.service.ContentImageService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 미디어 라이브러리 항목. 저장 키·네임스페이스는 내보내지 않는다. {@code getId()}는 감사 로그 targetId 추출({@code targetIdExpression = "id"})에 쓰인다.
 */
@Schema(description = "미디어 라이브러리 항목")
public record MediaResponse(
        @Schema(description = "이미지 ID", example = "12") Long id,
        @Schema(description = "원본 파일명(없으면 null)", nullable = true) String originalName,
        @Schema(description = "MIME 형식", example = "image/png") String contentType,
        @Schema(description = "파일 크기(byte)") Long fileSize,
        @Schema(description = "출처 — LIBRARY(라이브러리 업로드)·BANNER·POPUP(배너·팝업에서 올림)·BOARD(게시판 본문 이미지, ADMIN에게만 보임)", example = "LIBRARY") String origin,
        @Schema(description = "업로더 아이디") String uploaderId,
        LocalDateTime createDate,
        @Schema(description = "사용처 개수(참조 행 수)") int usageCount,
        @Schema(description = "배너·팝업·본문에서 고를 수 있는 이미지인가(라이브러리 계열 출처)") boolean selectable,
        @Schema(description = "관리자 미리보기 경로", example = "/admin/api/media/12/file") String previewUrl) {

    public static MediaResponse from(ContentImage image, int usageCount) {
        String origin = ContentImageService.SCOPE_BOARD.equals(image.getScopeType()) || "NOTICE".equals(image.getScopeType())
                ? ContentImageService.SCOPE_BOARD
                : image.getScopeType();
        return new MediaResponse(image.getId(), image.getOriginalName(), image.getContentType(), image.getFileSize(), origin,
                image.getUploaderId(), image.getCreateDate(), usageCount, ContentImageService.isLibraryScope(image.getScopeType()),
                "/admin/api/media/" + image.getId() + "/file");
    }

    public Long getId() {
        return id;
    }
}
