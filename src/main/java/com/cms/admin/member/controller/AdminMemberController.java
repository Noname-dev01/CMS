package com.cms.admin.member.controller;

import com.cms.admin.member.dto.request.AdminSignupRequest;
import com.cms.admin.member.dto.response.AdminSignupResponse;
import com.cms.admin.member.service.AdminMemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/member")
@Tag(name = "Admin Member", description = "관리자 계정 관리 API")
public class AdminMemberController {

    private final AdminMemberService adminMemberService;

    @Operation(summary = "관리자 계정 생성", description = "생성 가능한 userType: ADMIN")
    @ApiResponse(responseCode = "200", description = "관리자 계정 생성 성공")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패")
    @ApiResponse(responseCode = "403", description = "권한 없음")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminSignupResponse> createAdmin(@Valid @RequestBody AdminSignupRequest req) {
        return ResponseEntity.ok(adminMemberService.createAdmin(req));
    }
}
