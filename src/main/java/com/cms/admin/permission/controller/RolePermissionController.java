package com.cms.admin.permission.controller;

import com.cms.admin.member.domain.Role;
import com.cms.admin.permission.dto.request.RolePermissionUpdateRequest;
import com.cms.admin.permission.dto.response.RolePermissionMatrixResponse;
import com.cms.admin.permission.service.RolePermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/roles/{role}/permissions")
@Tag(name = "Admin Permission", description = "권한관리 API (ADMIN 전용 — 위임 불가)")
public class RolePermissionController {

    private final RolePermissionService rolePermissionService;

    @Operation(summary = "역할 권한 매트릭스 조회", description = "카탈로그(기능·동작) + 현재 유효 허용값 + 버전. role은 ROLE_MANAGER만 관리할 수 있다")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "400", description = "관리할 수 없는 역할(ROLE_ADMIN·ROLE_USER) 또는 알 수 없는 역할")
    @ApiResponse(responseCode = "404", description = "역할 기준 행 없음")
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RolePermissionMatrixResponse> getPermissions(@PathVariable Role role) {
        return ResponseEntity.ok(rolePermissionService.getMatrix(role));
    }

    @Operation(summary = "역할 권한 교체", description = "grants는 그 역할의 위임 가능 기능 허용 집합 전체(빈 배열이면 전부 회수). 변경이 없으면 버전·무효화 없이 현재 상태를 돌려준다")
    @ApiResponse(responseCode = "200", description = "저장 성공(또는 변경 없음)")
    @ApiResponse(responseCode = "400", description = "위임 불가 기능·미지원 동작·중복·READ 없는 쓰기·관리할 수 없는 역할")
    @ApiResponse(responseCode = "404", description = "역할 기준 행 없음")
    @ApiResponse(responseCode = "409", description = "version 불일치(다른 관리자가 먼저 변경) 또는 락 대기 실패")
    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RolePermissionMatrixResponse> replacePermissions(
            @PathVariable Role role,
            @Valid @RequestBody RolePermissionUpdateRequest request
    ) {
        return ResponseEntity.ok(rolePermissionService.replace(role, request).getResponse());
    }
}
