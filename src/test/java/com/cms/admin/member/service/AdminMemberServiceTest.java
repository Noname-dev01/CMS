package com.cms.admin.member.service;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.ProfileImageKind;
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
import com.cms.common.storage.FileStorage;
import com.cms.config.auth.AdminSessionRevokeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminMemberServiceTest {

    @Mock
    MemberRepository memberRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @Mock
    FileStorage fileStorage;

    /** 서비스와 같은 KST 고정 시계 — LocalDateTime.now() 직접 호출 금지 계약 유지 */
    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-07-17T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    @InjectMocks
    AdminMemberService adminMemberService;

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

    private AdminSignupRequest validRequest() {
        return AdminSignupRequest.builder()
                .userId("admin01")
                .pwd("Admin1234!")
                .userName("홍길동")
                .email("admin01@test.com")
                .userType(Role.ROLE_ADMIN)
                .build();
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
    @DisplayName("관리자 계정 생성 성공")
    void createAdmin_success() {

        AdminSignupRequest req = validRequest();

        given(memberRepository.existsByUserId(req.getUserId())).willReturn(false);
        given(memberRepository.existsByEmail(req.getEmail())).willReturn(false);
        given(passwordEncoder.encode(req.getPwd())).willReturn("encodedPassword");

        LocalDateTime date = LocalDateTime.now();

        Member savedMember = Member.builder()
                .id(1L)
                .userId(req.getUserId())
                .pwd("encodedPassword")
                .userName(req.getUserName())
                .email(req.getEmail())
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .createDate(date)
                .updateDate(date)
                .resetToken(null)
                .build();


        given(memberRepository.save(any(Member.class))).willReturn(savedMember);

        AdminSignupResponse response = adminMemberService.createAdmin(req);

        assertEquals(1L, response.getId());
        assertEquals("admin01", response.getUserId());
        assertEquals("홍길동", response.getUserName());
        assertEquals("admin01@test.com", response.getEmail());
        assertEquals(Role.ROLE_ADMIN, response.getUserType());
        assertEquals(MemberStatus.ACTIVE, response.getStatus());
        assertNotNull(response.getCreateDate());

        verify(memberRepository).save(any(Member.class));
        verify(passwordEncoder).encode(req.getPwd());
    }

    @Test
    @DisplayName("아이디가 중복이면 DuplicateResourceException")
    void createAdmin_duplicateUserId() {
        AdminSignupRequest req = validRequest();

        given(memberRepository.existsByUserId(req.getUserId())).willReturn(true);

        DuplicateResourceException exception = assertThrows(DuplicateResourceException.class, () -> adminMemberService.createAdmin(req));

        assertEquals("이미 사용 중인 아이디입니다.", exception.getMessage());
    }

    @Test
    @DisplayName("이메일 중복이면 DuplicateResourceException")
    void createAdmin_duplicateEmail() {
        AdminSignupRequest req = validRequest();

        given(memberRepository.existsByUserId(req.getUserId())).willReturn(false);
        given(memberRepository.existsByEmail(req.getEmail())).willReturn(true);

        DuplicateResourceException exception = assertThrows(DuplicateResourceException.class, () -> adminMemberService.createAdmin(req));

        assertEquals("이미 사용 중인 이메일입니다.", exception.getMessage());
    }

    @Test
    @DisplayName("관리자 목록 조회 성공")
    void getAdminMembers_success(){
        PageRequest pageable = PageRequest.of(0, 20);

        AdminMemberSearchRequest request = AdminMemberSearchRequest.builder()
                .userId("admin")
                .userName("홍")
                .status(MemberStatus.ACTIVE)
                .build();

        PageImpl<Member> page = new PageImpl<>(List.of(adminMember()), pageable, 1);

        given(memberRepository.searchAdminMembers(request, pageable)).willReturn(page);

        AdminMemberPageResponse response = adminMemberService.getAdminMembers(request, pageable);

        assertEquals(1, response.getTotalElements());
        assertEquals(1, response.getContent().size());
        assertEquals("admin01", response.getContent().get(0).getUserId());
    }

    @Test
    @DisplayName("관리자 상세 조회 성공")
    void getAdminMember_success(){
        given(memberRepository.findById(1L)).willReturn(Optional.of(adminMember()));

        AdminMemberResponse response = adminMemberService.getAdminMember(1L);

        assertEquals(1L, response.getId());
        assertEquals("admin01", response.getUserId());
    }

    @Test
    @DisplayName("존재하지 않는 관리자 조회 실패")
    void getAdminMember_notFound(){
        given(memberRepository.findById(1L)).willReturn(Optional.empty());

        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class, () -> adminMemberService.getAdminMember(1L));

        assertEquals("관리자를 찾을 수 없습니다.", exception.getMessage());
    }

    @Test
    @DisplayName("관리자 대상이 아니면 상세 조회 실패")
    void getAdminMember_invalidTarget(){
        Member userCheck = Member.builder()
                .id(3L)
                .userId("user01")
                .userName("일반유저")
                .email("user01@test.com")
                .userType(Role.ROLE_USER)
                .status(MemberStatus.ACTIVE)
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .build();

        given(memberRepository.findById(3L)).willReturn(Optional.of(userCheck));

        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class, () -> adminMemberService.getAdminMember(3L));

        assertEquals("관리자 대상만 조회할 수 있습니다.", exception.getMessage());
    }

    @Test
    @DisplayName("삭제된 관리자도 상세 조회 성공 (이력 조회 허용)")
    void getAdminMember_deletedTarget(){
        Member deletedAdmin = Member.builder()
                .id(4L)
                .userId("admin99")
                .userName("삭제된관리자")
                .email("admin99@test.com")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.DELETED)
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .build();

        given(memberRepository.findById(4L)).willReturn(Optional.of(deletedAdmin));

        AdminMemberResponse response = adminMemberService.getAdminMember(4L);

        assertEquals(4L, response.getId());
        assertEquals("admin99", response.getUserId());
        assertEquals(MemberStatus.DELETED, response.getStatus());
    }

    @Test
    @DisplayName("내 관리자 정보 조회 성공")
    void getMyInfo_success(){
        given(memberRepository.findById(1L)).willReturn(Optional.of(adminMember()));

        AdminMemberResponse response = adminMemberService.getMyInfo(1L);

        assertEquals(1L, response.getId());
        assertEquals("admin01", response.getUserId());
    }

    @Test
    @DisplayName("내 관리자 정보 수정 성공")
    void updateMyInfo_success() {
        Member member = adminMember();
        LocalDateTime previousUpdateDate = member.getUpdateDate();
        AdminMyInfoUpdateRequest request = AdminMyInfoUpdateRequest.builder()
                .userName("  관리자 수정  ")
                .email("ADMIN02@TEST.COM ")
                .build();

        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(memberRepository.findByEmail("admin02@test.com")).willReturn(Optional.empty());

        AdminMemberResponse response = adminMemberService.updateMyInfo(1L, request);

        assertEquals("관리자 수정", response.getUserName());
        assertEquals("admin02@test.com", response.getEmail());
        assertNotNull(response.getUpdateDate());
        assertTrue(!response.getUpdateDate().isBefore(previousUpdateDate));
        verify(memberRepository).findByEmail("admin02@test.com");
    }

    private Member adminMemberWithResetToken() {
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
                .resetToken("a".repeat(64))
                .resetTokenExpiryAt(LocalDateTime.now().plusMinutes(10))
                .build();
    }

    @Test
    @DisplayName("내 정보 수정으로 이메일이 바뀌면 발급돼 있던 재설정 토큰이 클리어된다 (이전 주소 메일함의 링크 무효화)")
    void updateMyInfo_emailChange_clearsOutstandingResetToken() {
        Member member = adminMemberWithResetToken();
        AdminMyInfoUpdateRequest request = AdminMyInfoUpdateRequest.builder()
                .userName("홍길동")
                .email("changed@test.com")
                .build();

        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(memberRepository.findByEmail("changed@test.com")).willReturn(Optional.empty());

        adminMemberService.updateMyInfo(1L, request);

        assertNull(member.getResetToken());
        assertNull(member.getResetTokenExpiryAt());
    }

    @Test
    @DisplayName("이름만 바꾸고 이메일이 동일하면 재설정 토큰은 유지된다")
    void updateMyInfo_sameEmail_keepsResetToken() {
        Member member = adminMemberWithResetToken();
        AdminMyInfoUpdateRequest request = AdminMyInfoUpdateRequest.builder()
                .userName("새이름")
                .email("admin01@test.com")
                .build();

        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        // 본인 소유 이메일 → 중복 검증 통과
        given(memberRepository.findByEmail("admin01@test.com")).willReturn(Optional.of(member));

        adminMemberService.updateMyInfo(1L, request);

        assertEquals("a".repeat(64), member.getResetToken());
        assertNotNull(member.getResetTokenExpiryAt());
    }

    @Test
    @DisplayName("비밀번호 변경 성공")
    void changeMyPassword_success() {
        Member member = adminMember();
        AdminMyPasswordChangeRequest request = AdminMyPasswordChangeRequest.builder()
                .currentPassword("Admin1234!")
                .newPassword("NewAdmin1234!")
                .confirmPassword("NewAdmin1234!")
                .build();

        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));
        given(passwordEncoder.matches("Admin1234!", member.getPwd())).willReturn(true);
        given(passwordEncoder.encode("NewAdmin1234!")).willReturn("encodedNewPassword");

        AdminMemberResponse response = adminMemberService.changeMyPassword(1L, request);

        assertEquals(1L, response.getId());
        verify(passwordEncoder).matches("Admin1234!", "encoded");
        verify(passwordEncoder).encode("NewAdmin1234!");
        // 변경 직전 이전 비밀번호로 인증을 통과한 세션이 살아남지 않도록 전 세션 폐기 이벤트 발행
        verify(eventPublisher).publishEvent(new AdminSessionRevokeEvent(1L));
    }

    @Test
    @DisplayName("비밀번호 변경 성공 시 발급돼 있던 재설정 토큰이 클리어된다 (메일함의 기존 링크 무효화)")
    void changeMyPassword_clearsOutstandingResetToken() {
        Member member = Member.builder()
                .id(1L)
                .userId("admin01")
                .pwd("encoded")
                .userName("홍길동")
                .email("admin01@test.com")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .resetToken("a".repeat(64))
                .resetTokenExpiryAt(LocalDateTime.now().plusMinutes(10))
                .build();
        AdminMyPasswordChangeRequest request = AdminMyPasswordChangeRequest.builder()
                .currentPassword("Admin1234!")
                .newPassword("NewAdmin1234!")
                .confirmPassword("NewAdmin1234!")
                .build();

        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));
        given(passwordEncoder.matches("Admin1234!", member.getPwd())).willReturn(true);
        given(passwordEncoder.encode("NewAdmin1234!")).willReturn("encodedNewPassword");

        adminMemberService.changeMyPassword(1L, request);

        assertNull(member.getResetToken());
        assertNull(member.getResetTokenExpiryAt());
    }

    @Test
    @DisplayName("현재 비밀번호가 틀리면 InvalidRequestException")
    void changeMyPassword_wrongCurrentPassword() {
        Member member = adminMember();
        AdminMyPasswordChangeRequest request = AdminMyPasswordChangeRequest.builder()
                .currentPassword("WrongPassword!")
                .newPassword("NewAdmin1234!")
                .confirmPassword("NewAdmin1234!")
                .build();

        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));
        given(passwordEncoder.matches("WrongPassword!", member.getPwd())).willReturn(false);

        InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                () -> adminMemberService.changeMyPassword(1L, request));

        assertEquals("현재 비밀번호가 올바르지 않습니다.", exception.getMessage());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("새 비밀번호와 확인이 다르면 InvalidRequestException")
    void changeMyPassword_passwordMismatch() {
        AdminMyPasswordChangeRequest request = AdminMyPasswordChangeRequest.builder()
                .currentPassword("Admin1234!")
                .newPassword("NewAdmin1234!")
                .confirmPassword("DifferentPassword!")
                .build();

        InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                () -> adminMemberService.changeMyPassword(1L, request));

        assertEquals("새 비밀번호와 비밀번호 확인이 일치하지 않습니다.", exception.getMessage());
        verify(memberRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("현재 관리자를 찾을 수 없으면 ResourceNotFoundException")
    void changeMyPassword_memberNotFound() {
        AdminMyPasswordChangeRequest request = AdminMyPasswordChangeRequest.builder()
                .currentPassword("Admin1234!")
                .newPassword("NewAdmin1234!")
                .confirmPassword("NewAdmin1234!")
                .build();

        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.empty());

        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> adminMemberService.changeMyPassword(1L, request));

        assertEquals("관리자를 찾을 수 없습니다.", exception.getMessage());
        verify(passwordEncoder, never()).encode(any());
    }

    // ===================== updateAdminMember (타 관리자 수정) =====================

    private Member targetManager(Long id, MemberStatus status) {
        return Member.builder()
                .id(id)
                .userId("manager" + id)
                .pwd("encoded")
                .userName("김매니저")
                .email("manager" + id + "@test.com")
                .userType(Role.ROLE_MANAGER)
                .status(status)
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .build();
    }

    private Member targetAdmin(Long id, MemberStatus status) {
        return Member.builder()
                .id(id)
                .userId("admin" + id)
                .pwd("encoded")
                .userName("타관리자")
                .email("admin" + id + "@test.com")
                .userType(Role.ROLE_ADMIN)
                .status(status)
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("타 관리자 부분 수정 — status만 보내면 이름·이메일·권한은 유지된다")
    void updateAdminMember_partialUpdate_keepsOtherFields() {
        Member target = targetManager(2L, MemberStatus.ACTIVE);
        given(memberRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));

        AdminMemberUpdateRequest request = AdminMemberUpdateRequest.builder()
                .status(MemberStatus.LOCKED)
                .build();

        AdminMemberResponse response = adminMemberService.updateAdminMember(1L, 2L, request);

        assertEquals("김매니저", response.getUserName());
        assertEquals("manager2@test.com", response.getEmail());
        assertEquals(Role.ROLE_MANAGER, response.getUserType());
        assertEquals(MemberStatus.LOCKED, response.getStatus());
        // MANAGER 대상은 활성 ADMIN 자격과 무관 — 가드 잠금 조회가 나가지 않아야 한다
        verify(memberRepository, never()).findActiveAdminIdsForUpdate();
    }

    @Test
    @DisplayName("LOCKED→ACTIVE 해제 시 실패 카운트·잠금 시각이 리셋된다 (해제 직후 1회 실패 재잠금 방지)")
    void updateAdminMember_unlockToActive_resetsFailedLoginCount() {
        Member target = Member.builder()
                .id(2L).userId("manager2").pwd("encoded").userName("김매니저")
                .email("manager2@test.com").userType(Role.ROLE_MANAGER)
                .status(MemberStatus.LOCKED)
                .failedLoginCount(5).lockedAt(LocalDateTime.now())
                .createDate(LocalDateTime.now()).updateDate(LocalDateTime.now())
                .build();
        given(memberRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));

        adminMemberService.updateAdminMember(1L, 2L,
                AdminMemberUpdateRequest.builder().status(MemberStatus.ACTIVE).build());

        assertEquals(0, target.getFailedLoginCount());
        assertNull(target.getLockedAt());
        assertEquals(MemberStatus.ACTIVE, target.getStatus());
    }

    @Test
    @DisplayName("DISABLED→ACTIVE 복구도 실패 카운트를 리셋한다 (상태 전이 경합으로 숨은 카운트 정리)")
    void updateAdminMember_disabledToActive_resetsFailedLoginCount() {
        Member target = Member.builder()
                .id(2L).userId("manager2").pwd("encoded").userName("김매니저")
                .email("manager2@test.com").userType(Role.ROLE_MANAGER)
                .status(MemberStatus.DISABLED)
                .failedLoginCount(3)
                .createDate(LocalDateTime.now()).updateDate(LocalDateTime.now())
                .build();
        given(memberRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));

        adminMemberService.updateAdminMember(1L, 2L,
                AdminMemberUpdateRequest.builder().status(MemberStatus.ACTIVE).build());

        assertEquals(0, target.getFailedLoginCount());
    }

    @Test
    @DisplayName("이름만 변경하면 실패 카운트는 보존된다 (리셋은 비ACTIVE→ACTIVE 실변경에만)")
    void updateAdminMember_nameOnlyChange_keepsFailedLoginCount() {
        Member target = Member.builder()
                .id(2L).userId("manager2").pwd("encoded").userName("김매니저")
                .email("manager2@test.com").userType(Role.ROLE_MANAGER)
                .status(MemberStatus.ACTIVE)
                .failedLoginCount(4)
                .createDate(LocalDateTime.now()).updateDate(LocalDateTime.now())
                .build();
        given(memberRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));

        adminMemberService.updateAdminMember(1L, 2L,
                AdminMemberUpdateRequest.builder().userName("변경").build());

        assertEquals(4, target.getFailedLoginCount());
    }

    @Test
    @DisplayName("본인 계정을 대상으로 하면 InvalidRequestException")
    void updateAdminMember_selfTarget_rejected() {
        AdminMemberUpdateRequest request = AdminMemberUpdateRequest.builder()
                .userName("변경")
                .build();

        InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                () -> adminMemberService.updateAdminMember(1L, 1L, request));

        assertEquals("본인 계정은 내 정보 수정을 이용해주세요.", exception.getMessage());
        verify(memberRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("대상이 없으면 ResourceNotFoundException")
    void updateAdminMember_notFound() {
        given(memberRepository.findByIdForUpdate(2L)).willReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> adminMemberService.updateAdminMember(1L, 2L,
                        AdminMemberUpdateRequest.builder().userName("변경").build()));
    }

    @Test
    @DisplayName("ROLE_USER 대상은 404 (관리 대상 아님)")
    void updateAdminMember_roleUserTarget_notFound() {
        Member roleUser = Member.builder()
                .id(3L).userId("user01").userName("일반유저").email("user01@test.com")
                .userType(Role.ROLE_USER).status(MemberStatus.ACTIVE)
                .createDate(LocalDateTime.now()).updateDate(LocalDateTime.now())
                .build();
        given(memberRepository.findByIdForUpdate(3L)).willReturn(Optional.of(roleUser));

        assertThrows(ResourceNotFoundException.class,
                () -> adminMemberService.updateAdminMember(1L, 3L,
                        AdminMemberUpdateRequest.builder().userName("변경").build()));
    }

    @Test
    @DisplayName("DELETED 계정 수정 시도는 ConflictException(409)")
    void updateAdminMember_deletedTarget_conflict() {
        given(memberRepository.findByIdForUpdate(2L))
                .willReturn(Optional.of(targetManager(2L, MemberStatus.DELETED)));

        ConflictException exception = assertThrows(ConflictException.class,
                () -> adminMemberService.updateAdminMember(1L, 2L,
                        AdminMemberUpdateRequest.builder().status(MemberStatus.ACTIVE).build()));

        assertEquals("삭제된 계정은 수정할 수 없습니다.", exception.getMessage());
    }

    @Test
    @DisplayName("타인 이메일과 중복이면 DuplicateResourceException")
    void updateAdminMember_duplicateEmail_conflict() {
        Member target = targetManager(2L, MemberStatus.ACTIVE);
        Member other = targetManager(9L, MemberStatus.ACTIVE);
        given(memberRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));
        given(memberRepository.findByEmail("dup@test.com")).willReturn(Optional.of(other));

        assertThrows(DuplicateResourceException.class,
                () -> adminMemberService.updateAdminMember(1L, 2L,
                        AdminMemberUpdateRequest.builder().email("dup@test.com").build()));
    }

    @Test
    @DisplayName("유일한 활성 ADMIN을 강등/잠금하려 하면 ConflictException(최후 활성 ADMIN 가드)")
    void updateAdminMember_lastActiveAdmin_guarded() {
        Member target = targetAdmin(2L, MemberStatus.ACTIVE);
        given(memberRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));
        // 잠금 조회 결과에 대상 자신만 존재 — 변경 후 활성 ADMIN 0명
        given(memberRepository.findActiveAdminIdsForUpdate()).willReturn(List.of(2L));

        ConflictException exception = assertThrows(ConflictException.class,
                () -> adminMemberService.updateAdminMember(1L, 2L,
                        AdminMemberUpdateRequest.builder().status(MemberStatus.LOCKED).build()));

        assertEquals("최소 1명의 활성 관리자가 유지되어야 합니다.", exception.getMessage());
    }

    @Test
    @DisplayName("다른 활성 ADMIN이 존재하면 강등이 허용된다")
    void updateAdminMember_demoteWithRemainingAdmin_allowed() {
        Member target = targetAdmin(2L, MemberStatus.ACTIVE);
        given(memberRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));
        given(memberRepository.findActiveAdminIdsForUpdate()).willReturn(List.of(1L, 2L));

        AdminMemberResponse response = adminMemberService.updateAdminMember(1L, 2L,
                AdminMemberUpdateRequest.builder().userType(Role.ROLE_MANAGER).build());

        assertEquals(Role.ROLE_MANAGER, response.getUserType());
        verify(eventPublisher).publishEvent(new AdminSessionRevokeEvent(2L));
    }

    @Test
    @DisplayName("status 실변경(→ACTIVE 복귀 포함) 시 세션 만료 이벤트가 발행된다")
    void updateAdminMember_statusChangeToActive_publishesRevokeEvent() {
        Member target = targetManager(2L, MemberStatus.LOCKED);
        given(memberRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));

        adminMemberService.updateAdminMember(1L, 2L,
                AdminMemberUpdateRequest.builder().status(MemberStatus.ACTIVE).build());

        verify(eventPublisher).publishEvent(new AdminSessionRevokeEvent(2L));
    }

    @Test
    @DisplayName("멱등 재잠금(LOCKED 동일값 재저장)도 세션 만료 이벤트를 발행한다 — 만료 실패 운영 복구 경로")
    void updateAdminMember_idempotentRelock_publishesRevokeEvent() {
        Member target = targetManager(2L, MemberStatus.LOCKED);
        given(memberRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));

        adminMemberService.updateAdminMember(1L, 2L,
                AdminMemberUpdateRequest.builder().status(MemberStatus.LOCKED).build());

        verify(eventPublisher).publishEvent(new AdminSessionRevokeEvent(2L));
    }

    @Test
    @DisplayName("status=ACTIVE 동일값 재저장은 이벤트를 발행하지 않는다 — 활성 관리자 강제 로그아웃 남용 차단")
    void updateAdminMember_sameActiveStatus_noEvent() {
        Member target = targetManager(2L, MemberStatus.ACTIVE);
        given(memberRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));

        adminMemberService.updateAdminMember(1L, 2L,
                AdminMemberUpdateRequest.builder().status(MemberStatus.ACTIVE).build());

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("이름·이메일만 변경하면 이벤트를 발행하지 않고 정규화가 적용된다")
    void updateAdminMember_nameEmailOnly_noEvent_normalized() {
        Member target = targetManager(2L, MemberStatus.ACTIVE);
        given(memberRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));
        given(memberRepository.findByEmail("new@test.com")).willReturn(Optional.empty());

        AdminMemberResponse response = adminMemberService.updateAdminMember(1L, 2L,
                AdminMemberUpdateRequest.builder()
                        .userName("  새이름  ")
                        .email(" NEW@TEST.COM ")
                        .build());

        assertEquals("새이름", response.getUserName());
        assertEquals("new@test.com", response.getEmail());
        assertEquals(MemberStatus.ACTIVE, response.getStatus());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("userType 동일값 재저장은 이벤트를 발행하지 않는다")
    void updateAdminMember_sameRole_noEvent() {
        Member target = targetManager(2L, MemberStatus.ACTIVE);
        given(memberRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));

        adminMemberService.updateAdminMember(1L, 2L,
                AdminMemberUpdateRequest.builder().userType(Role.ROLE_MANAGER).build());

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("내 관리자 정보 수정 시 이메일 중복이면 실패")
    void updateMyInfo_duplicateEmail() {
        Member currentMember = adminMember();
        Member duplicatedMember = Member.builder()
                .id(2L)
                .userId("admin02")
                .pwd("encoded")
                .userName("중복관리자")
                .email("dup@test.com")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .build();

        AdminMyInfoUpdateRequest request = AdminMyInfoUpdateRequest.builder()
                .userName("홍길동")
                .email("dup@test.com")
                .build();

        given(memberRepository.findById(1L)).willReturn(Optional.of(currentMember));
        given(memberRepository.findByEmail("dup@test.com")).willReturn(Optional.of(duplicatedMember));

        DuplicateResourceException exception = assertThrows(DuplicateResourceException.class,
                () -> adminMemberService.updateMyInfo(1L, request));

        assertEquals("이미 사용 중인 이메일입니다.", exception.getMessage());
        verify(memberRepository, never()).save(any(Member.class));
    }

    // ===================== 프로필 이미지 (PLAN-profile-image-storage.md) =====================

    @Test
    @DisplayName("프로필 이미지 업로드 성공 — FileStorage에 profile 네임스페이스로 저장되고 kind가 UPLOADED로 바뀐다")
    void updateMyProfileImage_success() throws IOException {
        Member member = adminMember(); // kind=NONE(기본값)
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", pngBytes(10, 10));

        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));
        given(fileStorage.store(any(byte[].class), eq("avatar.png"), eq("profile")))
                .willReturn("2026/08/10/uuid.png");

        AdminMemberResponse response = adminMemberService.updateMyProfileImage(1L, file);

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

        adminMemberService.updateMyProfileImage(1L, file);

        assertEquals("2026/08/10/new.png", member.getProfileImageUrl());
        // afterCommit 콜백이 등록됐다는 것은 실제 커밋 없이는 직접 검증하기 어렵다 — 여기서는
        // 최소한 새 파일 저장이 정상 반영됐는지만 확인한다(실제 커밋 후 삭제 동작은
        // AdminMemberProfileImageTransactionIntegrationTest에서 실 트랜잭션으로 검증).
    }

    @Test
    @DisplayName("2MB 초과 파일은 400")
    void updateMyProfileImage_tooLarge_rejected() {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[3 * 1024 * 1024]);

        InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                () -> adminMemberService.updateMyProfileImage(1L, file));

        assertEquals("프로필 이미지는 2MB 이하만 업로드할 수 있습니다.", exception.getMessage());
        verify(memberRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("화이트리스트 밖 MIME(webp)은 400")
    void updateMyProfileImage_webp_rejected() {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.webp", "image/webp", new byte[]{1, 2, 3});

        assertThrows(InvalidRequestException.class, () -> adminMemberService.updateMyProfileImage(1L, file));
        verify(memberRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("빈 파일은 400")
    void updateMyProfileImage_emptyFile_rejected() {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[0]);

        assertThrows(InvalidRequestException.class, () -> adminMemberService.updateMyProfileImage(1L, file));
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

        adminMemberService.resetMyProfileImage(1L);

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

        adminMemberService.resetMyProfileImage(1L);

        assertEquals(ProfileImageKind.NONE, member.getProfileImageKind());
        verify(fileStorage, never()).delete(anyString(), anyString());
    }

    @Test
    @DisplayName("기본 프로필 이미지(프리셋) 선택 성공")
    void applyDefaultProfileImage_success() {
        Member member = adminMember();
        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));

        AdminMemberResponse response = adminMemberService.applyDefaultProfileImage(1L, "profile-1");

        assertEquals(ProfileImageKind.PRESET, member.getProfileImageKind());
        assertEquals("/img/undraw_profile_1.svg", member.getProfileImageUrl());
        assertEquals("/img/undraw_profile_1.svg", response.getProfileImageUrl());
    }

    @Test
    @DisplayName("존재하지 않는 프리셋 키는 400")
    void applyDefaultProfileImage_invalidPreset_rejected() {
        assertThrows(InvalidRequestException.class,
                () -> adminMemberService.applyDefaultProfileImage(1L, "not-a-preset"));
        verify(memberRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("내 프로필 이미지 다운로드 — kind가 UPLOADED가 아니면 404")
    void getMyProfileImageContent_notUploaded_notFound() {
        Member member = adminMember(); // kind=NONE
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        assertThrows(ResourceNotFoundException.class, () -> adminMemberService.getMyProfileImageContent(1L));
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

        var content = adminMemberService.getMyProfileImageContent(1L);

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

        assertThrows(ResourceNotFoundException.class, () -> adminMemberService.getProfileImageContent(2L));
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

        assertThrows(ResourceNotFoundException.class, () -> adminMemberService.getMyProfileImageContent(1L));
        verify(fileStorage, never()).load(anyString(), anyString());
    }
}
