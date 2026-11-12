package com.cms.admin.permission.controller;

import com.cms.admin.permission.dto.request.MemberPermissionUpdateRequest;
import com.cms.admin.permission.dto.response.MemberPermissionMatrixResponse;
import com.cms.admin.permission.service.MemberPermissionService;
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
@RequestMapping("/admin/api/members/{id}/permissions")
@Tag(name = "Admin Permission", description = "권한관리 API (ADMIN 전용 — 위임 불가)")
public class MemberPermissionController {

    private final MemberPermissionService memberPermissionService;

    @Operation(summary = "회원 권한 매트릭스 조회", description = "카탈로그(기능·동작) + 그 회원의 현재 유효 허용값 + 버전. 대상은 ROLE_MANAGER 회원만")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "400", description = "대상이 ROLE_ADMIN(코드로 고정) 또는 id가 숫자가 아님")
    @ApiResponse(responseCode = "404", description = "회원 없음(ROLE_USER 대상 포함)")
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MemberPermissionMatrixResponse> getPermissions(@PathVariable Long id) {
        return ResponseEntity.ok(memberPermissionService.getMatrix(id));
    }

    @Operation(summary = "회원 권한 교체", description = "grants는 그 회원의 위임 가능 기능 허용 집합 전체(빈 배열이면 전부 회수). 변경이 없으면 버전·무효화 없이 현재 상태를 돌려준다")
    @ApiResponse(responseCode = "200", description = "저장 성공(또는 변경 없음)")
    @ApiResponse(responseCode = "400", description = "위임 불가 기능·미지원 동작·중복·READ 없는 쓰기·대상이 ROLE_ADMIN")
    @ApiResponse(responseCode = "404", description = "회원 없음(ROLE_USER 대상 포함)")
    @ApiResponse(responseCode = "409", description = "version 불일치(다른 관리자가 먼저 변경 또는 역할 변경)·삭제된 계정·형식이 올바르지 않은 행·락 대기 실패")
    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MemberPermissionMatrixResponse> replacePermissions(
            @PathVariable Long id,
            @Valid @RequestBody MemberPermissionUpdateRequest request
    ) {
        return ResponseEntity.ok(memberPermissionService.replace(id, request).getResponse());
    }
}
