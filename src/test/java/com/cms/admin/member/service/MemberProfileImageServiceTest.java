package com.cms.admin.member.service;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.ProfileImageKind;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.response.AdminMemberResponse;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.common.storage.FileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 프로필 이미지 서비스 단위 시험(PLAN-profile-image-storage.md). 2026-11-26 서비스 분리 때
 * {@code AdminMemberServiceTest}에서 단언을 바꾸지 않고 옮겼다(PLAN-member-profile-image-service.md 결정 5-a).
 */
@ExtendWith(MockitoExtension.class)
class MemberProfileImageServiceTest {

    @Mock
    MemberRepository memberRepository;

    @Mock
    FileStorage fileStorage;

    /** 서비스와 같은 KST 고정 시계 — LocalDateTime.now() 직접 호출 금지 계약 유지 */
    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-07-17T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    @InjectMocks
    MemberProfileImageService profileImageService;

    /**
     * updateMyProfileImage 등이 FileStorageTransactionSupport(TransactionSynchronizationManager
     * 사용)를 호출한다 — 실제 트랜잭션 없이 이를 호출 가능하게 하려면 동기화를 수동 활성화해야
     * 한다(NoticeAttachmentServiceTest와 동일 패턴).
     */
    @BeforeEach
    void initSynchronization() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization();
        }
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private byte[] pngBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private Member adminMember() {
        return Member.builder()
                .id(1L)
                .userId("admin01")
                .pwd("encoded")
                .userName("홍길동")
                .email("admin01@test.com")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .resetToken(null)
                .build();
    }


    @Test
    @DisplayName("프로필 이미지 업로드 성공 — FileStorage에 profile 네임스페이스로 저장되고 kind가 UPLOADED로 바뀐다")
    void updateMyProfileImage_success() throws IOException {
        Member member = adminMember(); // kind=NONE(기본값)
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", pngBytes(10, 10));

        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));
        given(fileStorage.store(any(byte[].class), eq("avatar.png"), eq("profile")))
                .willReturn("2026/08/10/uuid.png");

        AdminMemberResponse response = profileImageService.updateMyProfileImage(1L, file);

        assertEquals(ProfileImageKind.UPLOADED, member.getProfileImageKind());
        assertEquals("2026/08/10/uuid.png", member.getProfileImageUrl());
        assertEquals("image/png", member.getProfileImageContentType());
        assertTrue(response.getProfileImageUrl().startsWith("/admin/api/members/me/profile-image?v="));
    }

    @Test
    @DisplayName("프로필 이미지 교체 시 구 이미지가 UPLOADED였다면 커밋 후 삭제가 등록된다")
    void updateMyProfileImage_replacesExisting_registersOldFileCleanup() throws IOException {
        Member member = Member.builder()
                .id(1L).userId("admin01").pwd("x").userName("홍길동").email("a@test.com")
                .userType(Role.ROLE_ADMIN).status(MemberStatus.ACTIVE)
                .profileImageKind(ProfileImageKind.UPLOADED)
                .profileImageUrl("2026/08/01/old.png")
                .profileImageContentType("image/png")
                .build();
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", pngBytes(10, 10));

        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));
        given(fileStorage.store(any(byte[].class), eq("avatar.png"), eq("profile")))
                .willReturn("2026/08/10/new.png");

        profileImageService.updateMyProfileImage(1L, file);

        assertEquals("2026/08/10/new.png", member.getProfileImageUrl());
        // afterCommit 콜백이 등록됐다는 것은 실제 커밋 없이는 직접 검증하기 어렵다 — 여기서는
        // 최소한 새 파일 저장이 정상 반영됐는지만 확인한다(실제 커밋 후 삭제 동작은
        // MemberProfileImageTransactionIntegrationTest에서 실 트랜잭션으로 검증).
    }

    @Test
    @DisplayName("2MB 초과 파일은 400")
    void updateMyProfileImage_tooLarge_rejected() {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[3 * 1024 * 1024]);

        InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                () -> profileImageService.updateMyProfileImage(1L, file));

        assertEquals("프로필 이미지는 2MB 이하만 업로드할 수 있습니다.", exception.getMessage());
        verify(memberRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("화이트리스트 밖 MIME(webp)은 400")
    void updateMyProfileImage_webp_rejected() {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.webp", "image/webp", new byte[]{1, 2, 3});

        assertThrows(InvalidRequestException.class, () -> profileImageService.updateMyProfileImage(1L, file));
        verify(memberRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("빈 파일은 400")
    void updateMyProfileImage_emptyFile_rejected() {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[0]);

        assertThrows(InvalidRequestException.class, () -> profileImageService.updateMyProfileImage(1L, file));
    }

    @Test
    @DisplayName("프로필 이미지 초기화 — 기존 값이 UPLOADED였다면 커밋 후 파일 삭제가 등록되고 kind는 NONE이 된다")
    void resetMyProfileImage_uploaded_resetsToNone() {
        Member member = Member.builder()
                .id(1L).userId("admin01").pwd("x").userName("홍길동").email("a@test.com")
                .userType(Role.ROLE_ADMIN).status(MemberStatus.ACTIVE)
                .profileImageKind(ProfileImageKind.UPLOADED)
                .profileImageUrl("2026/08/01/old.png")
                .profileImageContentType("image/png")
                .build();
        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));

        profileImageService.resetMyProfileImage(1L);

        assertEquals(ProfileImageKind.NONE, member.getProfileImageKind());
        assertNull(member.getProfileImageUrl());
        assertNull(member.getProfileImageContentType());
    }

    @Test
    @DisplayName("프로필 이미지 초기화 — 기존 값이 PRESET이었다면 FileStorage를 호출하지 않는다")
    void resetMyProfileImage_preset_doesNotTouchFileStorage() {
        Member member = Member.builder()
                .id(1L).userId("admin01").pwd("x").userName("홍길동").email("a@test.com")
                .userType(Role.ROLE_ADMIN).status(MemberStatus.ACTIVE)
                .profileImageKind(ProfileImageKind.PRESET)
                .profileImageUrl("/img/undraw_profile_1.svg")
                .build();
        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));

        profileImageService.resetMyProfileImage(1L);

        assertEquals(ProfileImageKind.NONE, member.getProfileImageKind());
        verify(fileStorage, never()).delete(anyString(), anyString());
    }

    @Test
    @DisplayName("기본 프로필 이미지(프리셋) 선택 성공")
    void applyDefaultProfileImage_success() {
        Member member = adminMember();
        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));

        AdminMemberResponse response = profileImageService.applyDefaultProfileImage(1L, "profile-1");

        assertEquals(ProfileImageKind.PRESET, member.getProfileImageKind());
        assertEquals("/img/undraw_profile_1.svg", member.getProfileImageUrl());
        assertEquals("/img/undraw_profile_1.svg", response.getProfileImageUrl());
    }

    @Test
    @DisplayName("존재하지 않는 프리셋 키는 400")
    void applyDefaultProfileImage_invalidPreset_rejected() {
        assertThrows(InvalidRequestException.class,
                () -> profileImageService.applyDefaultProfileImage(1L, "not-a-preset"));
        verify(memberRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("내 프로필 이미지 다운로드 — kind가 UPLOADED가 아니면 404")
    void getMyProfileImageContent_notUploaded_notFound() {
        Member member = adminMember(); // kind=NONE
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        assertThrows(ResourceNotFoundException.class, () -> profileImageService.getMyProfileImageContent(1L));
        verify(fileStorage, never()).load(anyString(), anyString());
    }

    @Test
    @DisplayName("내 프로필 이미지 다운로드 성공")
    void getMyProfileImageContent_success() {
        Member member = Member.builder()
                .id(1L).userId("admin01").pwd("x").userName("홍길동").email("a@test.com")
                .userType(Role.ROLE_ADMIN).status(MemberStatus.ACTIVE)
                .profileImageKind(ProfileImageKind.UPLOADED)
                .profileImageUrl("2026/08/01/uuid.png")
                .profileImageContentType("image/png")
                .build();
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(fileStorage.load("2026/08/01/uuid.png", "profile")).willReturn(new byte[]{9, 9, 9});

        var content = profileImageService.getMyProfileImageContent(1L);

        assertArrayEquals(new byte[]{9, 9, 9}, content.content());
        assertEquals("image/png", content.contentType());
    }

    @Test
    @DisplayName("타 관리자 프로필 이미지 다운로드 — ROLE_USER 대상은 404")
    void getProfileImageContent_nonAdminTarget_notFound() {
        Member userMember = Member.builder()
                .id(2L).userId("user01").pwd("x").userName("일반회원").email("u@test.com")
                .userType(Role.ROLE_USER).status(MemberStatus.ACTIVE)
                .profileImageKind(ProfileImageKind.UPLOADED)
                .profileImageUrl("2026/08/01/uuid.png")
                .profileImageContentType("image/png")
                .build();
        given(memberRepository.findById(2L)).willReturn(Optional.of(userMember));

        assertThrows(ResourceNotFoundException.class, () -> profileImageService.getProfileImageContent(2L));
        verify(fileStorage, never()).load(anyString(), anyString());
    }

    @Test
    @DisplayName("다운로드 시점 재검증 — content_type이 화이트리스트 밖이면(오염된 값) 404")
    void getMyProfileImageContent_contentTypeOutsideWhitelist_notFound() {
        Member member = Member.builder()
                .id(1L).userId("admin01").pwd("x").userName("홍길동").email("a@test.com")
                .userType(Role.ROLE_ADMIN).status(MemberStatus.ACTIVE)
                .profileImageKind(ProfileImageKind.UPLOADED)
                .profileImageUrl("2026/08/01/uuid.svg")
                .profileImageContentType("image/svg+xml") // DB 직접 조작 등으로 오염된 값 가정
                .build();
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        assertThrows(ResourceNotFoundException.class, () -> profileImageService.getMyProfileImageContent(1L));
        verify(fileStorage, never()).load(anyString(), anyString());
    }
}
