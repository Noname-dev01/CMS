package com.cms.admin.notice.controller;

import com.cms.admin.notice.dto.request.NoticeCreateRequest;
import com.cms.admin.notice.dto.request.NoticeSearchRequest;
import com.cms.admin.notice.dto.request.NoticeUpdateRequest;
import com.cms.admin.notice.dto.response.NoticePageResponse;
import com.cms.admin.notice.dto.response.NoticeResponse;
import com.cms.admin.notice.service.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/notices")
@Tag(name = "Admin Notice", description = "공지사항 관리 API")
public class NoticeController {

    private final NoticeService noticeService;

    @Operation(summary = "공지사항 목록 조회", description = "keyword(제목 포함 검색)·useYn으로 필터링, 페이지 크기는 최대 100으로 clamp된다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<NoticePageResponse> getNotices(
            @ParameterObject @PageableDefault(size = 20) Pageable pageable,
            @Valid @ModelAttribute NoticeSearchRequest request
    ) {
        return ResponseEntity.ok(noticeService.getNotices(request, pageable));
    }

    @Operation(summary = "공지사항 상세 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "공지사항 없음")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<NoticeResponse> getNotice(@PathVariable Long id) {
        return ResponseEntity.ok(noticeService.getNotice(id));
    }

    @Operation(summary = "공지사항 생성")
    @ApiResponse(responseCode = "201", description = "생성 성공")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패")
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<NoticeResponse> createNotice(@Valid @RequestBody NoticeCreateRequest request) {
        NoticeResponse response = noticeService.createNotice(request);
        return ResponseEntity.created(
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(response.getId())
                        .toUri()
        ).body(response);
    }

    @Operation(summary = "공지사항 부분 수정", description = "null 필드는 기존값 유지. 값이 오면 공백은 거부되고, 전체 필드가 null이면 400.")
    @ApiResponse(responseCode = "200", description = "수정 성공")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패, 공백 값, 전체 필드 누락")
    @ApiResponse(responseCode = "404", description = "공지사항 없음")
    @ApiResponse(responseCode = "409", description = "동시 변경 충돌")
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<NoticeResponse> updateNotice(
            @PathVariable Long id,
            @Valid @RequestBody NoticeUpdateRequest request
    ) {
        return ResponseEntity.ok(noticeService.updateNotice(id, request));
    }

    @Operation(summary = "공지사항 삭제", description = "하드 삭제가 아닌 소프트 삭제(deleted=true).")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "404", description = "공지사항 없음")
    @ApiResponse(responseCode = "409", description = "동시 변경 충돌")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<Void> deleteNotice(@PathVariable Long id) {
        noticeService.deleteNotice(id);
        return ResponseEntity.noContent().build();
    }
}
