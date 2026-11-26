package com.cms.admin.member.service;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.ProfileImageKind;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.response.AdminMemberResponse;
import com.cms.admin.member.dto.response.ProfileImageContent;
import com.cms.admin.member.dto.response.ProfileImageVisibility;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.FileStorageTransactionSupport;
import com.cms.common.storage.StorageFileNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 관리자 프로필 이미지 — 업로드·기본 이미지 선택·초기화·다운로드.
 * 회원 관리 서비스({@link AdminMemberService})에서 분리했다(SOLID 검토 2번, PLAN-member-profile-image-service.md).
 *
 * <p>잠금 계약: 쓰기 3개는 첫 DB 접근이 회원 행 {@code findByIdForUpdate}다 — 비밀번호 변경·자동 잠금 벌크 UPDATE와
 * 같은 행에서 직렬화된다. 회원 행 하나만 잠그고 다른 잠금은 얻지 않는다. 파일은 {@link FileStorage}의
 * {@code "profile"} 네임스페이스에 두고, 새 파일은 롤백 시·옛 업로드 파일은 커밋 후 지운다
 * ({@link FileStorageTransactionSupport} — 실제 트랜잭션 계약은 {@code MemberProfileImageTransactionIntegrationTest}).
 */
@Service
@RequiredArgsConstructor
public class MemberProfileImageService {

    private static final Map<String, String> DEFAULT_PROFILE_IMAGE_MAP = Map.of(
            "profile-default", "/img/undraw_profile.svg",
            "profile-1", "/img/undraw_profile_1.svg",
            "profile-2", "/img/undraw_profile_2.svg",
            "profile-3", "/img/undraw_profile_3.svg"
    );

    /** FileStorage 물리 네임스페이스 — 공지 첨부파일과 다른 하위 디렉터리(쟁점 2). */
    private static final String PROFILE_IMAGE_NAMESPACE = "profile";

    private static final long MAX_UPLOAD_FILE_SIZE = 2 * 1024 * 1024;

    private final MemberRepository memberRepository;
    private final FileStorage fileStorage;
    private final Clock clock;

    /**
     * 프로필 이미지 업로드. 화이트리스트·헤더 우선 크기·애니메이션·포맷-MIME 일치 검증을
     * 통과한 뒤(ProfileImageValidator) FileStorage의 "profile" 네임스페이스에 저장한다.
     *
     * <p>동시 변경(교체·초기화·프리셋 전환) 경합을 직렬화하기 위해 {@code findByIdForUpdate}로
     * 행을 잠근다 — {@code AdminMemberService.changeMyPassword()}와 동일한 이유(자동 잠금 벌크 UPDATE와의 경합
     * 차단)로 이미 있는 패턴을 재사용한다. 구 이미지가 UPLOADED였다면 커밋 후 삭제한다.
     */
    @Transactional
    public AdminMemberResponse updateMyProfileImage(Long adminId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("업로드할 이미지 파일을 선택해주세요.");
        }
        if (file.getSize() > MAX_UPLOAD_FILE_SIZE) {
            throw new InvalidRequestException("프로필 이미지는 2MB 이하만 업로드할 수 있습니다.");
        }

        String contentType = file.getContentType();
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new InvalidRequestException("프로필 이미지를 처리할 수 없습니다.");
        }
        ProfileImageValidator.validate(content, contentType);

        Member member = memberRepository.findByIdForUpdate(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("관리자를 찾을 수 없습니다."));

        ProfileImageKind previousKind = member.getProfileImageKind();
        String previousStorageKey = member.getProfileImageUrl();

        String storageKey = fileStorage.store(content, file.getOriginalFilename(), PROFILE_IMAGE_NAMESPACE);
        FileStorageTransactionSupport.deleteOnRollback(fileStorage, storageKey, PROFILE_IMAGE_NAMESPACE,
                "memberId=" + adminId);

        member.changeUploadedProfileImage(storageKey, contentType, LocalDateTime.now(clock));

        if (previousKind == ProfileImageKind.UPLOADED) {
            FileStorageTransactionSupport.deleteAfterCommit(fileStorage, previousStorageKey, PROFILE_IMAGE_NAMESPACE,
                    "memberId=" + adminId);
        }

        return AdminMemberResponse.from(member, ProfileImageVisibility.SELF);
    }

    @Transactional
    public void resetMyProfileImage(Long adminId) {
        Member member = memberRepository.findByIdForUpdate(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("관리자를 찾을 수 없습니다."));

        ProfileImageKind previousKind = member.getProfileImageKind();
        String previousStorageKey = member.getProfileImageUrl();

        member.resetProfileImage(LocalDateTime.now(clock));

        if (previousKind == ProfileImageKind.UPLOADED) {
            FileStorageTransactionSupport.deleteAfterCommit(fileStorage, previousStorageKey, PROFILE_IMAGE_NAMESPACE,
                    "memberId=" + adminId);
        }
    }

    @Transactional
    public AdminMemberResponse applyDefaultProfileImage(Long adminId, String presetKey) {
        String presetImageUrl = DEFAULT_PROFILE_IMAGE_MAP.get(presetKey);
        if (presetImageUrl == null) {
            throw new InvalidRequestException("선택할 수 없는 기본 프로필 이미지입니다.");
        }

        Member member = memberRepository.findByIdForUpdate(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("관리자를 찾을 수 없습니다."));

        ProfileImageKind previousKind = member.getProfileImageKind();
        String previousStorageKey = member.getProfileImageUrl();

        member.changePresetProfileImage(presetImageUrl, LocalDateTime.now(clock));

        if (previousKind == ProfileImageKind.UPLOADED) {
            FileStorageTransactionSupport.deleteAfterCommit(fileStorage, previousStorageKey, PROFILE_IMAGE_NAMESPACE,
                    "memberId=" + adminId);
        }

        return AdminMemberResponse.from(member, ProfileImageVisibility.SELF);
    }

    /** 본인 프로필 이미지 다운로드(GET .../me/profile-image) 전용. */
    @Transactional(readOnly = true)
    public ProfileImageContent getMyProfileImageContent(Long adminId) {
        Member member = memberRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("관리자를 찾을 수 없습니다."));
        return loadProfileImageContent(member);
    }

    /**
     * 타 관리자 프로필 이미지 다운로드(GET .../{id}/profile-image) 전용 — {@code AdminMemberService.getAdminMember}와
     * 동일하게 ROLE_USER 대상은 404 처리한다(쟁점 11).
     */
    @Transactional(readOnly = true)
    public ProfileImageContent getProfileImageContent(Long targetId) {
        Member member = memberRepository.findById(targetId)
                .orElseThrow(() -> new ResourceNotFoundException("관리자를 찾을 수 없습니다."));
        validateAdminTarget(member);
        return loadProfileImageContent(member);
    }

    private ProfileImageContent loadProfileImageContent(Member member) {
        if (member.getProfileImageKind() != ProfileImageKind.UPLOADED) {
            throw new ResourceNotFoundException("프로필 이미지를 찾을 수 없습니다.");
        }
        String contentType = member.getProfileImageContentType();
        if (contentType == null || !ProfileImageValidator.ALLOWED_CONTENT_TYPES.contains(contentType)) {
            // DB 직접 조작 등으로 오염된 값 — 화이트리스트 밖이면 파일에 접근하지 않고 404.
            throw new ResourceNotFoundException("프로필 이미지를 찾을 수 없습니다.");
        }
        try {
            byte[] content = fileStorage.load(member.getProfileImageUrl(), PROFILE_IMAGE_NAMESPACE);
            return new ProfileImageContent(content, contentType);
        } catch (StorageFileNotFoundException e) {
            throw new ResourceNotFoundException("프로필 이미지를 찾을 수 없습니다.");
        }
    }

    /**
     * {@code AdminMemberService.validateAdminTarget}과 같은 조건·문구다 — 도메인을 바꾸지 않으려 복제했다
     * (PLAN-member-profile-image-service.md 결정 2). 관리자 역할이 늘면 두 곳을 함께 고친다.
     */
    private void validateAdminTarget(Member member) {
        if (member.getUserType() != Role.ROLE_ADMIN && member.getUserType() != Role.ROLE_MANAGER) {
            throw new ResourceNotFoundException("관리자 대상만 조회할 수 있습니다.");
        }
    }
}
