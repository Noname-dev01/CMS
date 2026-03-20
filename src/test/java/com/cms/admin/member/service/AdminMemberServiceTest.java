package com.cms.admin.member.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminMemberServiceTest {

    @Mock
    MemberRepository memberRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    AdminSecurityService adminSecurityService;

    @InjectMocks
    AdminMemberService adminMemberService;

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
                .createDate(new Date())
                .updateDate(new Date())
                .resetToken(null)
                .build();
    }

    @Test
    @DisplayName("관리자 계정 생성 성공")
    void createAdmin_success() {

        AdminSignupRequest req = validRequest();

        given(adminSecurityService.hasAdminAuthority()).willReturn(true);
        given(memberRepository.existsByUserId(req.getUserId())).willReturn(false);
        given(memberRepository.existsByEmail(req.getEmail())).willReturn(false);
        given(passwordEncoder.encode(req.getPwd())).willReturn("encodedPassword");

        Date date = new Date();

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
    @DisplayName("관리자 권한이 없으면 InvalidRequestException")
    void createAdmin_invalidRole() {
        AdminSignupRequest req = validRequest();

        given(adminSecurityService.hasAdminAuthority()).willReturn(false);

        InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                () -> adminMemberService.createAdmin(req));

        assertEquals("관리자 권한이 없습니다.", exception.getMessage());
    }

    @Test
    @DisplayName("아이디가 중복이면 DuplicateResourceException")
    void createAdmin_duplicateUserId() {
        AdminSignupRequest req = validRequest();

        given(adminSecurityService.hasAdminAuthority()).willReturn(true);
        given(memberRepository.existsByUserId(req.getUserId())).willReturn(true);

        DuplicateResourceException exception = assertThrows(DuplicateResourceException.class, () -> adminMemberService.createAdmin(req));

        assertEquals("이미 사용 중인 아이디입니다.", exception.getMessage());
    }

    @Test
    @DisplayName("이메일 중복이면 DuplicateResourceException")
    void createAdmin_duplicateEmail() {
        AdminSignupRequest req = validRequest();

        given(adminSecurityService.hasAdminAuthority()).willReturn(true);
        given(memberRepository.existsByUserId(req.getUserId())).willReturn(false);
        given(memberRepository.existsByEmail(req.getEmail())).willReturn(true);

        DuplicateResourceException exception = assertThrows(DuplicateResourceException.class, () -> adminMemberService.createAdmin(req));

        assertEquals("이미 사용 중인 이메일입니다.", exception.getMessage());
    }

    @Test
    @DisplayName("관리자 목록 조회 성공")
    void getAdminMembers_success(){
        given(adminSecurityService.hasAdminAuthority()).willReturn(true);

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
    @DisplayName("관리자 권한이 없으면 목록 조회 실패")
    void getAdminMembers_noAuthority(){
        given(adminSecurityService.hasAdminAuthority()).willReturn(false);

        InvalidRequestException exception = assertThrows(InvalidRequestException.class, () ->
                adminMemberService.getAdminMembers(new AdminMemberSearchRequest(), PageRequest.of(0, 20)));

        assertEquals("관리자 권한이 없습니다.", exception.getMessage());
    }

    @Test
    @DisplayName("관리자 상세 조회 성공")
    void getAdminMember_success(){
        given(adminSecurityService.hasAdminAuthority()).willReturn(true);
        given(memberRepository.findById(1L)).willReturn(Optional.of(adminMember()));

        AdminMemberResponse response = adminMemberService.getAdminMember(1L);

        assertEquals(1L, response.getId());
        assertEquals("admin01", response.getUserId());
    }

    @Test
    @DisplayName("존재하지 않는 관리자 조회 실패")
    void getAdminMember_notFound(){
        given(adminSecurityService.hasAdminAuthority()).willReturn(true);
        given(memberRepository.findById(1L)).willReturn(Optional.empty());

        InvalidRequestException exception = assertThrows(InvalidRequestException.class, () -> adminMemberService.getAdminMember(1L));

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
                .createDate(new Date())
                .updateDate(new Date())
                .build();

        given(adminSecurityService.hasAdminAuthority()).willReturn(true);
        given(memberRepository.findById(3L)).willReturn(Optional.of(userCheck));

        InvalidRequestException exception = assertThrows(InvalidRequestException.class, () -> adminMemberService.getAdminMember(3L));

        assertEquals("관리자 대상만 조회할 수 있습니다.", exception.getMessage());
    }

    @Test
    @DisplayName("삭제된 관리자면 상세 조회 실패")
    void getAdminMember_deletedTarget(){
        Member deletedAdmin = Member.builder()
                .id(4L)
                .userId("admin99")
                .userName("삭제된관리자")
                .email("admin99@test.com")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.DELETED)
                .createDate(new Date())
                .updateDate(new Date())
                .build();

        given(adminSecurityService.hasAdminAuthority()).willReturn(true);
        given(memberRepository.findById(4L)).willReturn(Optional.of(deletedAdmin));

        InvalidRequestException exception = assertThrows(InvalidRequestException.class, () -> adminMemberService.getAdminMember(4L));

        assertEquals("삭제된 관리자 정보는 조회할 수 없습니다.", exception.getMessage());
    }

    @Test
    @DisplayName("내 관리자 정보 조회 성공")
    void getMyInfo_success(){
        given(adminSecurityService.hasAdminAuthority()).willReturn(true);
        given(adminSecurityService.getCurrentAdminId()).willReturn(1L);
        given(memberRepository.findById(1L)).willReturn(Optional.of(adminMember()));

        AdminMemberResponse response = adminMemberService.getMyInfo();

        assertEquals(1L, response.getId());
        assertEquals("admin01", response.getUserId());
    }
}