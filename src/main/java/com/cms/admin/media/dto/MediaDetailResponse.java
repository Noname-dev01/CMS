package com.cms.admin.media.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 라이브러리 항목 상세 — 사용처 목록 포함. 게시글 라벨은 호출자가 그 게시판을 읽을 수 있을 때만 채운다(PLAN-media-library.md D3). */
@Schema(description = "미디어 라이브러리 항목 상세")
public record MediaDetailResponse(MediaResponse media, List<Usage> usages) {

    @Schema(description = "사용처 한 건")
    public record Usage(
            @Schema(description = "소유자 종류 — BANNER·POPUP·POST", example = "BANNER") String ownerType,
            @Schema(description = "소유자 ID") Long ownerId,
            @Schema(description = "표시 이름(권한이 없으면 고정 문구)") String label) {
    }
}
