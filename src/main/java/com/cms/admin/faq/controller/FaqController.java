package com.cms.admin.faq.controller;

import com.cms.admin.faq.dto.request.FaqOrderRequest;
import com.cms.admin.faq.dto.request.FaqRequest;
import com.cms.admin.faq.dto.response.FaqResponse;
import com.cms.admin.faq.dto.response.FaqTreeResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * FAQ 관리 API. 경로가 {@code FAQ} URL 게이트({@code /admin/api/faqs/**}) 안이라 게이트는 READ까지만 보고, 동작별 판정은 핸들러의
 * {@link RequirePermission}이 한다(PLAN-faq.md 쟁점 2). 동작 분류: 트리·상세 = READ, 등록 = CREATE, 수정·순서 반영 = UPDATE, 삭제 = DELETE.
 * {@code PUT /order}는 {@code PUT /{id}}와 같은 컨트롤러에 있고 리터럴 경로가 우선한다(배너와 같은 구성).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/faqs")
@Tag(name = "Admin FAQ", description = "FAQ 관리 API (기능 단위 위임)")
public class FaqController {

    private final FaqService faqService;

    @Operation(summary = "FAQ 트리 조회", description = "카테고리와 FAQ(답변 본문 제외)를 표시 순서대로, 읽은 시점의 구조 version과 함께 돌려준다")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "403", description = "FAQ 조회 권한 없음")
    @GetMapping
    @RequirePermission(feature = AdminFeature.FAQ, action = PermissionAction.READ)
    public ResponseEntity<FaqTreeResponse> getTree() {
        return ResponseEntity.ok(faqService.getTree());
    }

    @Operation(summary = "FAQ 단건 조회", description = "답변 본문 포함")
    @ApiResponse(responseCode = "404", description = "FAQ 없음")
    @GetMapping("/{id}")
    @RequirePermission(feature = AdminFeature.FAQ, action = PermissionAction.READ)
    public ResponseEntity<FaqResponse> getFaq(@PathVariable Long id) {
        return ResponseEntity.ok(faqService.getFaq(id));
    }

    @Operation(summary = "FAQ 등록", description = "소속 카테고리의 맨 끝에 추가. 최대 200개")
    @ApiResponse(responseCode = "201", description = "등록 성공")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패(질문·답변·카테고리)")
    @ApiResponse(responseCode = "403", description = "FAQ 등록 권한 없음")
    @ApiResponse(responseCode = "409", description = "FAQ 개수 상한 도달")
    @PostMapping
    @RequirePermission(feature = AdminFeature.FAQ, action = PermissionAction.CREATE)
    public ResponseEntity<FaqResponse> createFaq(@Valid @RequestBody FaqRequest request) {
        FaqResponse response = faqService.createFaq(request);
        return ResponseEntity.created(
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(response.getId())
                        .toUri()
        ).body(response);
    }

    @Operation(summary = "FAQ 순서 반영", description = "전체 카테고리 순서와 각 카테고리의 전체 FAQ 순서를 구조 version과 함께 보낸다. 낡은 version은 409")
    @ApiResponse(responseCode = "400", description = "중복·현재 구조와 다른 집합")
    @ApiResponse(responseCode = "409", description = "구조가 변경됨(낡은 화면)")
    @PutMapping("/order")
    @RequirePermission(feature = AdminFeature.FAQ, action = PermissionAction.UPDATE)
    public ResponseEntity<FaqTreeResponse> saveOrder(@Valid @RequestBody FaqOrderRequest request) {
        return ResponseEntity.ok(faqService.saveOrder(request));
    }

    @Operation(summary = "FAQ 수정", description = "질문·답변·노출 전체 교체(PUT). 카테고리가 바뀌면 대상 카테고리의 맨 끝으로 이동")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패")
    @ApiResponse(responseCode = "404", description = "FAQ 없음")
    @PutMapping("/{id}")
    @RequirePermission(feature = AdminFeature.FAQ, action = PermissionAction.UPDATE)
    public ResponseEntity<FaqResponse> updateFaq(@PathVariable Long id, @Valid @RequestBody FaqRequest request) {
        return ResponseEntity.ok(faqService.updateFaq(id, request));
    }

    @Operation(summary = "FAQ 삭제", description = "행을 지운다(복구 불가)")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "404", description = "FAQ 없음")
    @DeleteMapping("/{id}")
    @RequirePermission(feature = AdminFeature.FAQ, action = PermissionAction.DELETE)
    public ResponseEntity<Void> deleteFaq(@PathVariable Long id) {
        faqService.deleteFaq(id);
        return ResponseEntity.noContent().build();
    }
}
