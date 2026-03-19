package com.cms.admin.member.service;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.AdminMemberSearchRequest;
import com.cms.admin.member.dto.request.AdminSignupRequest;
import com.cms.admin.member.dto.response.AdminMemberPageResponse;
import com.cms.admin.member.dto.response.AdminMemberResponse;
import com.cms.admin.member.dto.response.AdminSignupResponse;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.common.exception.DuplicateResourceException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.config.auth.AdminSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminMemberService {

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
                .map(this::toResponse)
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

        return toResponse(member);
    }

    @Transactional(readOnly = true)
    public AdminMemberResponse getMyInfo() {
        validateAdminAuthority();

        Long adminId = adminSecurityService.getCurrentAdminId();

        Member member = memberRepository.findById(adminId)
                .orElseThrow(() -> new InvalidRequestException("관리자를 찾을 수 없습니다."));

        return toResponse(member);
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

    private AdminMemberResponse toResponse(Member member) {
        return AdminMemberResponse.builder()
                .id(member.getId())
                .userId(member.getUserId())
                .userName(member.getUserName())
                .email(member.getEmail())
                .userType(member.getUserType())
                .status(member.getStatus())
                .createDate(member.getCreateDate())
                .build();
    }
}
