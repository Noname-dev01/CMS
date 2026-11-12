package com.cms.admin.inquiry.controller;

import com.cms.admin.inquiry.domain.InquiryStatus;
import com.cms.admin.inquiry.dto.request.InquiryStatusRequest;
import com.cms.admin.inquiry.dto.response.InquiryPageResponse;
import com.cms.admin.inquiry.dto.response.InquiryResponse;
import com.cms.admin.inquiry.service.InquiryService;
import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문의함 API. 경로가 {@code INQUIRY} URL 게이트({@code /admin/api/inquiries/**}) 안이라 게이트는 READ까지만 보고, 동작별 판정은 핸들러의
 * {@link RequirePermission}이 한다(PLAN-inquiry.md 쟁점 2·7). 동작 분류: 목록·상세 = READ, 상태 변경 = UPDATE, 삭제 = DELETE.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/inquiries")
@Tag(name = "Admin Inquiry", description = "문의함 관리 API (기능 단위 위임)")
public class InquiryController {

    private final InquiryService inquiryService;

    @Operation(summary = "문의 목록 조회", description = "최신순. 본문은 싣지 않는다. status로 상태 탭을 거른다(생략하면 전체). 미처리 전체 건수를 함께 돌려준다")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "400", description = "status 값이 정의되지 않은 상태")
    @ApiResponse(responseCode = "403", description = "문의 조회 권한 없음")
    @GetMapping
    @RequirePermission(feature = AdminFeature.INQUIRY, action = PermissionAction.READ)
    public ResponseEntity<InquiryPageResponse> getInquiries(
            @RequestParam(required = false) InquiryStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(inquiryService.getInquiries(status, page, size));
    }

    @Operation(summary = "문의 단건 조회", description = "본문 포함. 조회가 상태를 바꾸지 않는다")
    @ApiResponse(responseCode = "404", description = "문의 없음")
    @GetMapping("/{id}")
    @RequirePermission(feature = AdminFeature.INQUIRY, action = PermissionAction.READ)
    public ResponseEntity<InquiryResponse> getInquiry(@PathVariable Long id) {
        return ResponseEntity.ok(inquiryService.getInquiry(id));
    }

    @Operation(summary = "문의 상태 변경", description = "접수(RECEIVED)·처리 중(IN_PROGRESS)·답변 완료(ANSWERED) 사이를 제한 없이 바꾼다. 같은 값이면 멱등 200")
    @ApiResponse(responseCode = "400", description = "상태 값 누락·정의되지 않은 값")
    @ApiResponse(responseCode = "404", description = "문의 없음")
    @PatchMapping("/{id}")
    @RequirePermission(feature = AdminFeature.INQUIRY, action = PermissionAction.UPDATE)
    public ResponseEntity<InquiryResponse> changeStatus(@PathVariable Long id, @Valid @RequestBody InquiryStatusRequest request) {
        return ResponseEntity.ok(inquiryService.changeStatus(id, request.getStatus()));
    }

    @Operation(summary = "문의 삭제", description = "행을 지운다(복구 불가). 개인정보 파기 수단이다")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "404", description = "문의 없음")
    @DeleteMapping("/{id}")
    @RequirePermission(feature = AdminFeature.INQUIRY, action = PermissionAction.DELETE)
    public ResponseEntity<Void> deleteInquiry(@PathVariable Long id) {
        inquiryService.deleteInquiry(id);
        return ResponseEntity.noContent().build();
    }
}
