package com.cms.admin.media.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 라이브러리 목록 페이지 + 전체 용량 현황(카운터 기준 — DB에 등록된 이미지 전체, 호출자가 볼 수 있는 항목만이 아님). */
@Schema(description = "미디어 라이브러리 목록")
public record MediaPageResponse(
        List<MediaResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last,
        @Schema(description = "사용 중인 전체 바이트") long usedBytes,
        @Schema(description = "등록된 전체 이미지 수") long usedCount,
        @Schema(description = "전체 바이트 상한") long maxBytes,
        @Schema(description = "전체 개수 상한") long maxCount) {
}
