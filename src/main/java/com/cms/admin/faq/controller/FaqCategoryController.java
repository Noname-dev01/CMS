package com.cms.admin.faq.controller;

import com.cms.admin.faq.dto.request.FaqCategoryRequest;
import com.cms.admin.faq.dto.response.FaqCategoryResponse;
import com.cms.admin.faq.service.FaqService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * FAQ 카테고리 관리 API. 목록은 {@code GET /admin/api/faqs} 트리에 들어 있어 따로 조회 엔드포인트가 없다. 동작 분류: 등록 = CREATE, 수정 = UPDATE,
 * 삭제 = DELETE(PLAN-faq.md 쟁점 2·5).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/faq-categories")
@Tag(name = "Admin FAQ Category", description = "FAQ 카테고리 관리 API (기능 단위 위임)")
public class FaqCategoryController {

    private final FaqService faqService;

    @Operation(summary = "FAQ 카테고리 등록", description = "맨 끝에 추가. 최대 30개")
    @ApiResponse(responseCode = "201", description = "등록 성공")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패")
    @ApiResponse(responseCode = "403", description = "FAQ 등록 권한 없음")
    @ApiResponse(responseCode = "409", description = "카테고리 개수 상한 도달")
    @PostMapping
    @RequirePermission(feature = AdminFeature.FAQ, action = PermissionAction.CREATE)
    public ResponseEntity<FaqCategoryResponse> createCategory(@Valid @RequestBody FaqCategoryRequest request) {
        FaqCategoryResponse response = faqService.createCategory(request);
        return ResponseEntity.created(
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(response.getId())
                        .toUri()
        ).body(response);
    }

    @Operation(summary = "FAQ 카테고리 수정", description = "이름·노출 전체 교체(PUT). 순서는 바꾸지 않는다")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패")
    @ApiResponse(responseCode = "404", description = "카테고리 없음")
    @PutMapping("/{id}")
    @RequirePermission(feature = AdminFeature.FAQ, action = PermissionAction.UPDATE)
    public ResponseEntity<FaqCategoryResponse> updateCategory(@PathVariable Long id, @Valid @RequestBody FaqCategoryRequest request) {
        return ResponseEntity.ok(faqService.updateCategory(id, request));
    }

    @Operation(summary = "FAQ 카테고리 삭제", description = "FAQ가 없는 카테고리만 삭제할 수 있다")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "404", description = "카테고리 없음")
    @ApiResponse(responseCode = "409", description = "FAQ가 남아 있음")
    @DeleteMapping("/{id}")
    @RequirePermission(feature = AdminFeature.FAQ, action = PermissionAction.DELETE)
    public ResponseEntity<Void> deleteCategory(@PathVariable Long id) {
        faqService.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }
}
