package com.cms.admin.log.controller;

import com.cms.admin.log.dto.request.AdminActionLogSearchRequest;
import com.cms.admin.log.dto.response.AdminActionLogPageResponse;
import com.cms.admin.log.service.AdminActionLogQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api")
@Tag(name = "Admin Action Log", description = "관리자 활동 로그 조회 API")
public class AdminActionLogController {

    private final AdminActionLogQueryService adminActionLogQueryService;

    @Operation(summary = "관리자 활동 로그 목록 조회",
               description = "기간 미지정 시 최근 30일, 명시 시 from~to 최대 3개월 제한")
    @GetMapping("logs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminActionLogPageResponse> getActionLogs(
            @ParameterObject @PageableDefault(size = 20) Pageable pageable,
            @Valid @ModelAttribute AdminActionLogSearchRequest request
    ) {
        return ResponseEntity.ok(adminActionLogQueryService.searchActionLogs(request, pageable));
    }
}
