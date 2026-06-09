package com.cms.admin.member.controller;

import com.cms.admin.member.dto.request.AdminMemberSearchRequest;
import com.cms.admin.member.dto.request.AdminMyInfoUpdateRequest;
import com.cms.admin.member.dto.request.AdminMyPasswordChangeRequest;
import com.cms.admin.member.dto.request.AdminSignupRequest;
import com.cms.admin.member.dto.request.ProfileImagePresetRequest;
import com.cms.admin.member.dto.response.AdminMemberPageResponse;
import com.cms.admin.member.dto.response.AdminMemberResponse;
import com.cms.admin.member.dto.response.AdminSignupResponse;
import com.cms.admin.member.service.AdminMemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api")
@Tag(name = "Admin Member", description = "관리자 계정 관리 API")
public class AdminMemberController {

    private final AdminMemberService adminMemberService;

    @Operation(summary = "관리자 계정 생성", description = "생성 가능한 userType: ROLE_ADMIN, ROLE_MANAGER")
    @ApiResponse(responseCode = "201", description = "관리자 계정 생성 성공")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패")
    @ApiResponse(responseCode = "403", description = "권한 없음")
    @PostMapping("members")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminSignupResponse> createAdmin(@Valid @RequestBody AdminSignupRequest req) {
        AdminSignupResponse resp = adminMemberService.createAdmin(req);
        return ResponseEntity.created(
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(resp.getId())
                        .toUri()
        ).body(resp);
    }

    @Operation(summary = "관리자 목록 조회")
    @GetMapping("members")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminMemberPageResponse> getAdminMembers(
            @ParameterObject @PageableDefault(size = 20) Pageable pageable,
            @Valid @ModelAttribute AdminMemberSearchRequest request
    ) {
        return ResponseEntity.ok(adminMemberService.getAdminMembers(request, pageable));
    }

    @Operation(summary = "관리자 상세 조회")
    @GetMapping("members/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminMemberResponse> getAdminMember(@PathVariable Long id) {
        return ResponseEntity.ok(adminMemberService.getAdminMember(id));
    }

    @Operation(summary = "내 관리자 정보 조회")
    @GetMapping("members/me")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminMemberResponse> getMyInfo() {
        return ResponseEntity.ok(adminMemberService.getMyInfo());
    }

    @Operation(summary = "내 관리자 정보 수정")
    @PatchMapping("members/me")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminMemberResponse> updateMyInfo(@Valid @RequestBody AdminMyInfoUpdateRequest request) {
        return ResponseEntity.ok(adminMemberService.updateMyInfo(request));
    }

    @Operation(summary = "내 프로필 이미지 업로드")
    @PutMapping(value = "members/me/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminMemberResponse> updateMyProfileImage(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(adminMemberService.updateMyProfileImage(file));
    }

    @Operation(summary = "내 기본 프로필 이미지 선택")
    @PutMapping(value = "members/me/profile-image", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminMemberResponse> applyDefaultProfileImage(@Valid @RequestBody ProfileImagePresetRequest request) {
        return ResponseEntity.ok(adminMemberService.applyDefaultProfileImage(request.getPreset()));
    }

    @Operation(summary = "내 프로필 이미지 초기화")
    @DeleteMapping("members/me/profile-image")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> resetMyProfileImage() {
        adminMemberService.resetMyProfileImage();
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "내 비밀번호 변경")
    @ApiResponse(responseCode = "200", description = "비밀번호 변경 성공")
    @ApiResponse(responseCode = "400", description = "현재 비밀번호 불일치 또는 새 비밀번호 확인 불일치")
    @ApiResponse(responseCode = "403", description = "권한 없음")
    @PatchMapping("members/me/password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminMemberResponse> changeMyPassword(@Valid @RequestBody AdminMyPasswordChangeRequest request) {
        return ResponseEntity.ok(adminMemberService.changeMyPassword(request));
    }
}
