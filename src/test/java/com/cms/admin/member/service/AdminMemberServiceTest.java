package com.cms.admin.member.service;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.AdminSignupRequest;
import com.cms.admin.member.dto.response.AdminSignupResponse;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.common.exception.DuplicateResourceException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.config.auth.AdminSecurityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Date;

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

}