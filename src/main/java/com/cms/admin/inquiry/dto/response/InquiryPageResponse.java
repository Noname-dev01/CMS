package com.cms.admin.inquiry.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 문의 목록 페이지. 상태 탭 숫자(미처리 건수)는 공개 폼의 소프트 상한과 같은 기준이라 함께 싣는다. */
@Schema(description = "문의 목록 페이지")
public record InquiryPageResponse(
        List<InquirySummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last,
        @Schema(description = "미처리(답변 완료가 아닌) 전체 건수") long unresolvedCount) {
}
