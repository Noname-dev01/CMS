package com.cms.admin.member.service;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.AdminMemberSearchRequest;
import com.cms.admin.member.dto.request.AdminMyInfoUpdateRequest;
import com.cms.admin.member.dto.request.AdminMyPasswordChangeRequest;
import com.cms.admin.member.dto.request.AdminSignupRequest;
import com.cms.admin.member.dto.response.AdminMemberPageResponse;
import com.cms.admin.member.dto.response.AdminMemberResponse;
import com.cms.admin.member.dto.response.AdminSignupResponse;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.common.exception.DuplicateResourceException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.config.auth.AdminSecurityService;
import com.cms.config.auth.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminMemberService {

    private static final Map<String, String> DEFAULT_PROFILE_IMAGE_MAP = Map.of(
            "profile-default", "/img/undraw_profile.svg",
            "profile-1", "/img/undraw_profile_1.svg",
            "profile-2", "/img/undraw_profile_2.svg",
            "profile-3", "/img/undraw_profile_3.svg"
    );

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminSecurityService adminSecurityService;

    @Transactional
    @AdminActionLogged(actionType = "ADMIN_CREATE", targetType = "MEMBER", targetIdExpression = "id")
    public AdminSignupResponse createAdmin(AdminSignupRequest req) {
        if (!adminSecurityService.hasAdminAuthority()) {
            throw new InvalidRequestException("관리자 권한이 없습니다.");
        }

        if (memberRepository.existsByUserId(req.getUserId())){
            throw new DuplicateResourceException("이미 사용 중인 아이디입니다.");
        }

        if (req.getEmail() != null && !req.getEmail().isBlank()
                && memberRepository.existsByEmail(req.getEmail())) {
            throw new DuplicateResourceException("이미 사용 중인 이메일입니다.");
        }

        Date now = new Date();

        Member saved = memberRepository.save(
                Member.builder()
                        .userId(req.getUserId())
                        .pwd(passwordEncoder.encode(req.getPwd()))
                        .userName(req.getUserName())
                        .email(req.getEmail())
                        .userType(req.getUserType()) // MANAGER or ADMIN
                        .status(MemberStatus.ACTIVE)
                        .createDate(now)
                        .updateDate(now)
                        .resetToken(null)
                        .build()
        );

        return AdminSignupResponse.builder()
                .id(saved.getId())
                .userId(saved.getUserId())
                .userName(saved.getUserName())
                .email(saved.getEmail())
                .userType(saved.getUserType())
                .status(saved.getStatus())
                .createDate(saved.getCreateDate())
                .build();
    }
    @Transactional(readOnly = true)
    public AdminMemberPageResponse getAdminMembers(AdminMemberSearchRequest request, Pageable pageable) {
        validateAdminAuthority();

        Page<Member> page = memberRepository.searchAdminMembers(request, pageable);

        List<AdminMemberResponse> content = page.getContent().stream()
                .map(member -> toResponse(member, false))
                .toList();

        return AdminMemberPageResponse.builder()
                .content(content)
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }

    @Transactional(readOnly = true)
    public AdminMemberResponse getAdminMember(Long id) {
        validateAdminAuthority();

        Member member = memberRepository.findById(id)
                .orElseThrow(() -> new InvalidRequestException("관리자를 찾을 수 없습니다."));

        validateAdminTarget(member);

        return toResponse(member, true);
    }

    @Transactional(readOnly = true)
    public AdminMemberResponse getMyInfo() {
        validateAdminAuthority();

        Long adminId = adminSecurityService.getCurrentAdminId();

        Member member = memberRepository.findById(adminId)
                .orElseThrow(() -> new InvalidRequestException("관리자를 찾을 수 없습니다."));

        return toResponse(member, true);
    }

    @Transactional
    public AdminMemberResponse updateMyInfo(AdminMyInfoUpdateRequest request) {
        validateAdminAuthority();

        Long adminId = adminSecurityService.getCurrentAdminId();
        Member member = memberRepository.findById(adminId)
                .orElseThrow(() -> new InvalidRequestException("관리자를 찾을 수 없습니다."));

        String normalizedUserName = normalizeUserName(request.getUserName());
        String normalizedEmail = normalizeEmail(request.getEmail());

        validateDuplicatedEmail(normalizedEmail, member.getId());

        member.setUserName(normalizedUserName);
        member.setEmail(normalizedEmail);
        member.setUpdateDate(new Date());
        refreshAuthentication(member);

        return toResponse(member, true);
    }

    @Transactional
    public AdminMemberResponse updateMyProfileImage(MultipartFile file) {
        validateAdminAuthority();

        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("업로드할 이미지 파일을 선택해주세요.");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new InvalidRequestException("이미지 파일만 업로드할 수 있습니다.");
        }

        long maxFileSize = 2 * 1024 * 1024;
        if (file.getSize() > maxFileSize) {
            throw new InvalidRequestException("프로필 이미지는 2MB 이하만 업로드할 수 있습니다.");
        }

        Long adminId = adminSecurityService.getCurrentAdminId();
        Member member = memberRepository.findById(adminId)
                .orElseThrow(() -> new InvalidRequestException("관리자를 찾을 수 없습니다."));

        try {
            String encoded = Base64.getEncoder().encodeToString(file.getBytes());
            member.setProfileImageUrl("data:" + contentType + ";base64," + encoded);
            member.setUpdateDate(new Date());
            refreshAuthentication(member);
        } catch (IOException e) {
            throw new InvalidRequestException("프로필 이미지를 처리할 수 없습니다.");
        }

        return toResponse(member, true);
    }

    @Transactional
    public AdminMemberResponse resetMyProfileImage() {
        validateAdminAuthority();

        Long adminId = adminSecurityService.getCurrentAdminId();
        Member member = memberRepository.findById(adminId)
                .orElseThrow(() -> new InvalidRequestException("관리자를 찾을 수 없습니다."));

        member.setProfileImageUrl(null);
        member.setUpdateDate(new Date());
        refreshAuthentication(member);

        return toResponse(member, true);
    }

    @Transactional
    @AdminActionLogged(actionType = "PASSWORD_CHANGE", targetType = "MEMBER", targetIdExpression = "id")
    public AdminMemberResponse changeMyPassword(AdminMyPasswordChangeRequest request) {
        validateAdminAuthority();

        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new InvalidRequestException("새 비밀번호와 비밀번호 확인이 일치하지 않습니다.");
        }

        Long adminId = adminSecurityService.getCurrentAdminId();
        Member member = memberRepository.findById(adminId)
                .orElseThrow(() -> new InvalidRequestException("관리자를 찾을 수 없습니다."));

        if (!passwordEncoder.matches(request.getCurrentPassword(), member.getPwd())) {
            throw new InvalidRequestException("현재 비밀번호가 올바르지 않습니다.");
        }

        member.setPwd(passwordEncoder.encode(request.getNewPassword()));
        member.setUpdateDate(new Date());
        refreshAuthentication(member);

        return toResponse(member, false);
    }

    @Transactional
    public AdminMemberResponse applyDefaultProfileImage(String presetKey) {
        validateAdminAuthority();

        String presetImageUrl = DEFAULT_PROFILE_IMAGE_MAP.get(presetKey);
        if (presetImageUrl == null) {
            throw new InvalidRequestException("선택할 수 없는 기본 프로필 이미지입니다.");
        }

        Long adminId = adminSecurityService.getCurrentAdminId();
        Member member = memberRepository.findById(adminId)
                .orElseThrow(() -> new InvalidRequestException("관리자를 찾을 수 없습니다."));

        member.setProfileImageUrl(presetImageUrl);
        member.setUpdateDate(new Date());
        refreshAuthentication(member);

        return toResponse(member, true);
    }

    private void validateAdminAuthority() {
        if (!adminSecurityService.hasAdminAuthority()) {
            throw new InvalidRequestException("관리자 권한이 없습니다.");
        }
    }

    private void validateAdminTarget(Member member) {
        if (member.getUserType() != Role.ROLE_ADMIN && member.getUserType() != Role.ROLE_MANAGER) {
            throw new InvalidRequestException("관리자 대상만 조회할 수 있습니다.");
        }

        if (member.getStatus() == MemberStatus.DELETED) {
            throw new InvalidRequestException("삭제된 관리자 정보는 조회할 수 없습니다.");
        }
    }

    private void refreshAuthentication(Member member) {
        Authentication currentAuth = SecurityContextHolder.getContext().getAuthentication();
        if (currentAuth == null) {
            return;
        }

        CustomUserDetails refreshedUser = new CustomUserDetails(member);
        UsernamePasswordAuthenticationToken newAuth = new UsernamePasswordAuthenticationToken(
                refreshedUser,
                currentAuth.getCredentials(),
                refreshedUser.getAuthorities()
        );
        newAuth.setDetails(currentAuth.getDetails());
        SecurityContextHolder.getContext().setAuthentication(newAuth);
    }

    private String normalizeUserName(String userName) {
        return userName == null ? null : userName.trim();
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private void validateDuplicatedEmail(String email, Long currentMemberId) {
        memberRepository.findByEmail(email)
                .filter(foundMember -> !foundMember.getId().equals(currentMemberId))
                .ifPresent(foundMember -> {
                    throw new DuplicateResourceException("이미 사용 중인 이메일입니다.");
                });
    }

    private AdminMemberResponse toResponse(Member member, boolean includeProfileImage) {
        return AdminMemberResponse.builder()
                .id(member.getId())
                .userId(member.getUserId())
                .userName(member.getUserName())
                .email(member.getEmail())
                .userType(member.getUserType())
                .status(member.getStatus())
                .createDate(member.getCreateDate())
                .updateDate(member.getUpdateDate())
                .profileImageUrl(includeProfileImage ? member.getProfileImageUrl() : null)
                .build();
    }
}
