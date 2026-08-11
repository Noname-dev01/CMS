package com.cms.admin.member.controller;

import com.cms.admin.member.dto.request.AdminMemberSearchRequest;
import com.cms.admin.member.dto.request.AdminMemberUpdateRequest;
import com.cms.admin.member.dto.request.AdminMyInfoUpdateRequest;
import com.cms.admin.member.dto.request.AdminMyPasswordChangeRequest;
import com.cms.admin.member.dto.request.AdminSignupRequest;
import com.cms.admin.member.dto.request.ProfileImagePresetRequest;
import com.cms.admin.member.dto.response.AdminMemberPageResponse;
import com.cms.admin.member.dto.response.AdminMemberResponse;
import com.cms.admin.member.dto.response.AdminSignupResponse;
import com.cms.admin.member.dto.response.ProfileImageContent;
import com.cms.admin.member.service.AdminMemberService;
import com.cms.config.auth.AdminSecurityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api")
@Tag(name = "Admin Member", description = "관리자 계정 관리 API")
public class AdminMemberController {

    private final AdminMemberService adminMemberService;
    private final AdminSecurityService adminSecurityService;

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

    @Operation(summary = "타 관리자 프로필 이미지 다운로드", description = "getAdminMember와 동일 인가 수준 — ROLE_USER 대상 포함 존재하지 않으면 404.")
    @ApiResponse(responseCode = "200", description = "다운로드 성공")
    @ApiResponse(responseCode = "403", description = "권한 없음")
    @ApiResponse(responseCode = "404", description = "관리자 없음 또는 업로드된 이미지 없음")
    @GetMapping("members/{id}/profile-image")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> getProfileImageContent(@PathVariable Long id) {
        return profileImageResponse(adminMemberService.getProfileImageContent(id));
    }

    @Operation(summary = "타 관리자 계정 수정",
            description = "부분 수정 — null 필드는 기존값 유지, userId는 변경 불가. "
                    + "상태·권한 변경(또는 LOCKED/DISABLED 동일값 재저장) 시 대상자의 기존 로그인 세션을 만료 처리한다. "
                    + "단, 이는 즉시 접근 차단 수단이 아니며 극단적인 커밋 경합 시 기존 세션이 "
                    + "세션 타임아웃(기본 30분) 또는 재잠금 시점까지 유효할 수 있다. "
                    + "본인 계정은 수정할 수 없다(내 정보 수정 사용).")
    @ApiResponse(responseCode = "200", description = "수정 성공")
    @ApiResponse(responseCode = "400", description = "검증 실패, 본인 계정 대상, 전체 필드 누락")
    @ApiResponse(responseCode = "403", description = "권한 없음")
    @ApiResponse(responseCode = "404", description = "관리자 없음(ROLE_USER 대상 포함)")
    @ApiResponse(responseCode = "409", description = "이메일 중복, 삭제된 계정, 최후 활성 ADMIN 제거 시도, 동시 변경 충돌")
    @PatchMapping("members/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminMemberResponse> updateAdminMember(
            @PathVariable Long id,
            @Valid @RequestBody AdminMemberUpdateRequest request
    ) {
        Long currentAdminId = requireCurrentAdminId();
        return ResponseEntity.ok(adminMemberService.updateAdminMember(currentAdminId, id, request));
    }

    @Operation(summary = "내 관리자 정보 조회")
    @GetMapping("members/me")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<AdminMemberResponse> getMyInfo() {
        Long adminId = requireCurrentAdminId();
        return ResponseEntity.ok(adminMemberService.getMyInfo(adminId));
    }

    @Operation(summary = "내 관리자 정보 수정")
    @PatchMapping("members/me")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<AdminMemberResponse> updateMyInfo(@Valid @RequestBody AdminMyInfoUpdateRequest request) {
        Long adminId = requireCurrentAdminId();
        AdminMemberResponse response = adminMemberService.updateMyInfo(adminId, request);
        // 자기수정 성공 후 반드시 refresh해야 상단바 이름·프로필 이미지가 즉시 갱신된다.
        adminSecurityService.refreshAuthentication(adminId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "내 프로필 이미지 업로드")
    @PutMapping(value = "members/me/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<AdminMemberResponse> updateMyProfileImage(@RequestPart("file") MultipartFile file) {
        Long adminId = requireCurrentAdminId();
        AdminMemberResponse response = adminMemberService.updateMyProfileImage(adminId, file);
        // 자기수정 성공 후 반드시 refresh해야 상단바 이름·프로필 이미지가 즉시 갱신된다.
        adminSecurityService.refreshAuthentication(adminId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "내 기본 프로필 이미지 선택")
    @PutMapping(value = "members/me/profile-image", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<AdminMemberResponse> applyDefaultProfileImage(@Valid @RequestBody ProfileImagePresetRequest request) {
        Long adminId = requireCurrentAdminId();
        AdminMemberResponse response = adminMemberService.applyDefaultProfileImage(adminId, request.getPreset());
        // 자기수정 성공 후 반드시 refresh해야 상단바 이름·프로필 이미지가 즉시 갱신된다.
        adminSecurityService.refreshAuthentication(adminId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "내 프로필 이미지 초기화")
    @DeleteMapping("members/me/profile-image")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<Void> resetMyProfileImage() {
        Long adminId = requireCurrentAdminId();
        adminMemberService.resetMyProfileImage(adminId);
        // 자기수정 성공 후 반드시 refresh해야 상단바 이름·프로필 이미지가 즉시 갱신된다.
        adminSecurityService.refreshAuthentication(adminId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "내 프로필 이미지 다운로드")
    @ApiResponse(responseCode = "200", description = "다운로드 성공")
    @ApiResponse(responseCode = "404", description = "업로드된 이미지 없음")
    @GetMapping("members/me/profile-image")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<byte[]> getMyProfileImageContent() {
        Long adminId = requireCurrentAdminId();
        return profileImageResponse(adminMemberService.getMyProfileImageContent(adminId));
    }

    @Operation(summary = "내 비밀번호 변경")
    @ApiResponse(responseCode = "200", description = "비밀번호 변경 성공")
    @ApiResponse(responseCode = "400", description = "현재 비밀번호 불일치 또는 새 비밀번호 확인 불일치")
    @ApiResponse(responseCode = "403", description = "권한 없음")
    @PatchMapping("members/me/password")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<AdminMemberResponse> changeMyPassword(@Valid @RequestBody AdminMyPasswordChangeRequest request) {
        Long adminId = requireCurrentAdminId();
        AdminMemberResponse response = adminMemberService.changeMyPassword(adminId, request);
        // 자기수정 성공 후 반드시 refresh해야 상단바 이름·프로필 이미지가 즉시 갱신된다.
        adminSecurityService.refreshAuthentication(adminId);
        return ResponseEntity.ok(response);
    }

    /**
     * 프로필 이미지 다운로드 공통 응답 — NoticeAttachmentController와 달리
     * {@code Content-Disposition}을 설정하지 않는다(인라인 렌더링 목적, {@code <img src>}가
     * 다운로드 다이얼로그 없이 표시되어야 한다). {@code Cache-Control: private, no-store}로
     * 인증된 개인 이미지가 공유 캐시에 남지 않게 한다(캐시 무효화 자체는 응답 URL의
     * 버전 쿼리 파라미터가 담당 — ProfileImageUrls 참조).
     */
    private ResponseEntity<byte[]> profileImageResponse(ProfileImageContent content) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(content.content());
    }

    /**
     * 현재 인증된 관리자 id를 반환한다.
     * {@code @PreAuthorize("hasRole('ADMIN')")} 통과 후라면 null이 되지 않는 방어 코드지만,
     * null일 경우 AccessDeniedException(→ 403)으로 처리해 500 변질을 방지한다.
     */
    private Long requireCurrentAdminId() {
        Long adminId = adminSecurityService.getCurrentAdminId();
        if (adminId == null) {
            throw new AccessDeniedException("인증 정보를 확인할 수 없습니다.");
        }
        return adminId;
    }
}
