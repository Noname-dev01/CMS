package com.cms.admin.member.service;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.AdminMemberSearchRequest;
import com.cms.admin.member.dto.request.AdminMemberUpdateRequest;
import com.cms.admin.member.dto.request.AdminMyInfoUpdateRequest;
import com.cms.admin.member.dto.request.AdminMyPasswordChangeRequest;
import com.cms.admin.member.dto.request.AdminSignupRequest;
import com.cms.admin.member.dto.response.AdminMemberPageResponse;
import com.cms.admin.member.dto.response.AdminMemberResponse;
import com.cms.admin.member.dto.response.AdminSignupResponse;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.DuplicateResourceException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.config.auth.AdminSessionRevokeEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
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
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.ADMIN_CREATE, targetType = "MEMBER", targetIdExpression = "id")
    public AdminSignupResponse createAdmin(AdminSignupRequest req) {
        if (memberRepository.existsByUserId(req.getUserId())){
            throw new DuplicateResourceException("이미 사용 중인 아이디입니다.");
        }

        if (req.getEmail() != null && !req.getEmail().isBlank()
                && memberRepository.existsByEmail(req.getEmail())) {
            throw new DuplicateResourceException("이미 사용 중인 이메일입니다.");
        }

        // 앱 KST Clock 단일 시간원 — 한 행의 createDate·passwordChangedAt이 다른 시간원을 갖지 않게 통일
        LocalDateTime now = LocalDateTime.now(clock);

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
                        .passwordChangedAt(now)
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
        Member member = memberRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("관리자를 찾을 수 없습니다."));

        validateAdminTarget(member);

        return toResponse(member, true);
    }

    @Transactional(readOnly = true)
    public AdminMemberResponse getMyInfo(Long adminId) {
        Member member = memberRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("관리자를 찾을 수 없습니다."));

        return toResponse(member, true);
    }

    @Transactional
    public AdminMemberResponse updateMyInfo(Long adminId, AdminMyInfoUpdateRequest request) {
        Member member = memberRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("관리자를 찾을 수 없습니다."));

        String normalizedUserName = normalizeUserName(request.getUserName());
        String normalizedEmail = normalizeEmail(request.getEmail());

        validateDuplicatedEmail(normalizedEmail, member.getId());

        member.updateInfo(normalizedUserName, normalizedEmail);

        return toResponse(member, true);
    }

    /**
     * 타 관리자 계정 수정 (부분 수정 — null 필드는 기존값 유지).
     *
     * <p>동시성 제어 2중 잠금:
     * <ul>
     *   <li>대상 행 잠금(findByIdForUpdate): 같은 대상에 대한 동시 PATCH의 lost update 차단</li>
     *   <li>최후 활성 ADMIN 가드(findActiveAdminIdsForUpdate): 동시 상호 강등/잠금으로
     *       활성 ADMIN이 0명이 되는 것을 차단 — 변경 후 활성 ADMIN이 1명 미만이면 409</li>
     * </ul>
     * 두 잠금의 교차로 데드락이 나면 InnoDB가 감지·롤백하고 전역 핸들러가 409로 변환한다.
     *
     * <p>상태·권한 실변경 또는 멱등 재잠금(LOCKED/DISABLED 동일값 재저장) 시
     * {@link AdminSessionRevokeEvent}를 발행해 커밋 후 대상자의 기존 세션을 만료 처리한다(best-effort).
     */
    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.ADMIN_UPDATE, targetType = "MEMBER", targetIdExpression = "id")
    public AdminMemberResponse updateAdminMember(Long currentAdminId, Long targetId, AdminMemberUpdateRequest request) {
        if (targetId.equals(currentAdminId)) {
            throw new InvalidRequestException("본인 계정은 내 정보 수정을 이용해주세요.");
        }

        Member target = memberRepository.findByIdForUpdate(targetId)
                .orElseThrow(() -> new ResourceNotFoundException("관리자를 찾을 수 없습니다."));

        validateAdminTarget(target);

        if (target.getStatus() == MemberStatus.DELETED) {
            throw new ConflictException("삭제된 계정은 수정할 수 없습니다.");
        }

        Role beforeRole = target.getUserType();
        MemberStatus beforeStatus = target.getStatus();
        Role effectiveRole = request.getUserType() != null ? request.getUserType() : beforeRole;
        MemberStatus effectiveStatus = request.getStatus() != null ? request.getStatus() : beforeStatus;

        // 최후 활성 ADMIN 가드: 이번 변경으로 대상이 활성 ADMIN 자격을 잃는 경우에만 잠금 조회
        boolean wasActiveAdmin = beforeRole == Role.ROLE_ADMIN && beforeStatus == MemberStatus.ACTIVE;
        boolean staysActiveAdmin = effectiveRole == Role.ROLE_ADMIN && effectiveStatus == MemberStatus.ACTIVE;
        if (wasActiveAdmin && !staysActiveAdmin) {
            long remainingActiveAdmins = memberRepository.findActiveAdminIdsForUpdate().stream()
                    .filter(id -> !id.equals(targetId))
                    .count();
            if (remainingActiveAdmins < 1) {
                throw new ConflictException("최소 1명의 활성 관리자가 유지되어야 합니다.");
            }
        }

        if (request.getUserName() != null || request.getEmail() != null) {
            String effectiveUserName = request.getUserName() != null
                    ? normalizeUserName(request.getUserName())
                    : target.getUserName();
            String effectiveEmail = target.getEmail();
            if (request.getEmail() != null) {
                effectiveEmail = normalizeEmail(request.getEmail());
                validateDuplicatedEmail(effectiveEmail, target.getId());
            }
            target.updateInfo(effectiveUserName, effectiveEmail);
        }

        boolean roleChanged = effectiveRole != beforeRole;
        if (roleChanged) {
            target.changeRole(effectiveRole);
        }

        boolean statusChanged = effectiveStatus != beforeStatus;
        if (statusChanged) {
            target.changeStatus(effectiveStatus);
            if (effectiveStatus == MemberStatus.ACTIVE) {
                // 비ACTIVE→ACTIVE 복구는 실패 연쇄 단절 — 리셋 없이는 해제 직후 1회 실패로 재잠금되고,
                // 상태 전이 경합으로 비ACTIVE 계정에 숨어 있던 카운트도 여기서 정리된다.
                target.resetFailedLoginCount();
            }
        }

        // 세션 만료 트리거: ① status 실변경(→ACTIVE 복귀 포함) ② userType 실변경(승격·강등)
        // ③ 요청 status가 LOCKED/DISABLED면 동일값이어도 만료(멱등 재잠금 — 만료 실패 시 운영 복구 경로).
        // 같은 값 재저장(ACTIVE·동일 role)이나 이름·이메일만 변경 시에는 발행하지 않는다(강제 로그아웃 남용 차단).
        boolean idempotentRelock = request.getStatus() == MemberStatus.LOCKED
                || request.getStatus() == MemberStatus.DISABLED;
        if (statusChanged || roleChanged || idempotentRelock) {
            eventPublisher.publishEvent(new AdminSessionRevokeEvent(target.getId()));
        }

        return toResponse(target, true);
    }

    @Transactional
    public AdminMemberResponse updateMyProfileImage(Long adminId, MultipartFile file) {
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

        Member member = memberRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("관리자를 찾을 수 없습니다."));

        try {
            String encoded = Base64.getEncoder().encodeToString(file.getBytes());
            member.changeProfileImage("data:" + contentType + ";base64," + encoded);
        } catch (IOException e) {
            throw new InvalidRequestException("프로필 이미지를 처리할 수 없습니다.");
        }

        return toResponse(member, true);
    }

    @Transactional
    public void resetMyProfileImage(Long adminId) {
        Member member = memberRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("관리자를 찾을 수 없습니다."));

        member.changeProfileImage(null);
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.PASSWORD_CHANGE, targetType = "MEMBER", targetIdExpression = "id")
    public AdminMemberResponse changeMyPassword(Long adminId, AdminMyPasswordChangeRequest request) {
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new InvalidRequestException("새 비밀번호와 비밀번호 확인이 일치하지 않습니다.");
        }

        // 행 잠금 조회 — 자동 잠금(벌크 UPDATE)과 직렬화해, 잠금 전이와 비밀번호 변경의
        // 더티체킹이 서로의 필드를 되쓰는 경합을 차단한다.
        Member member = memberRepository.findByIdForUpdate(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("관리자를 찾을 수 없습니다."));

        if (!passwordEncoder.matches(request.getCurrentPassword(), member.getPwd())) {
            throw new InvalidRequestException("현재 비밀번호가 올바르지 않습니다.");
        }

        member.changePassword(passwordEncoder.encode(request.getNewPassword()), LocalDateTime.now(clock));

        // 커밋 후 대상 계정의 모든 세션 만료(본인 포함 — 재로그인 필요).
        // 변경 직전 이전 비밀번호로 인증을 통과한 세션이 살아남는 경합을 닫는다.
        eventPublisher.publishEvent(new AdminSessionRevokeEvent(member.getId()));

        return toResponse(member, false);
    }

    @Transactional
    public AdminMemberResponse applyDefaultProfileImage(Long adminId, String presetKey) {
        String presetImageUrl = DEFAULT_PROFILE_IMAGE_MAP.get(presetKey);
        if (presetImageUrl == null) {
            throw new InvalidRequestException("선택할 수 없는 기본 프로필 이미지입니다.");
        }

        Member member = memberRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("관리자를 찾을 수 없습니다."));

        member.changeProfileImage(presetImageUrl);

        return toResponse(member, true);
    }

    private void validateAdminTarget(Member member) {
        if (member.getUserType() != Role.ROLE_ADMIN && member.getUserType() != Role.ROLE_MANAGER) {
            throw new ResourceNotFoundException("관리자 대상만 조회할 수 있습니다.");
        }
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
