package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminSecurityServiceTest {

    @Mock
    MemberRepository memberRepository;

    @InjectMocks
    AdminSecurityService adminSecurityService;

    private Member member;
    private CustomUserDetails userDetails;

    @BeforeEach
    void setUp() {
        member = Member.builder()
                .id(1L)
                .userId("admin01")
                .pwd("encoded")
                .userName("홍길동")
                .email("admin01@test.com")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .build();

        userDetails = new CustomUserDetails(member);

        // 인증된 SecurityContext 세팅
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userDetails, "credentials", userDetails.getAuthorities()
        );
        auth.setDetails("details-object");
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        // ThreadLocal 오염 방지
        SecurityContextHolder.clearContext();
    }

    // ===================== refreshAuthentication =====================

    @Test
    @DisplayName("refreshAuthentication: 최신 Member로 principal·authorities가 교체된다")
    void refreshAuthentication_success() {
        Member updatedMember = Member.builder()
                .id(1L)
                .userId("admin01")
                .pwd("encoded")
                .userName("변경된이름")
                .email("new@test.com")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .build();

        given(memberRepository.findById(1L)).willReturn(Optional.of(updatedMember));

        adminSecurityService.refreshAuthentication(1L);

        Authentication result = SecurityContextHolder.getContext().getAuthentication();
        assertThat(result).isNotNull();
        assertThat(result.getPrincipal()).isInstanceOf(CustomUserDetails.class);

        CustomUserDetails refreshed = (CustomUserDetails) result.getPrincipal();
        assertThat(refreshed.getMember().getUserName()).isEqualTo("변경된이름");
        assertThat(refreshed.getMember().getEmail()).isEqualTo("new@test.com");
    }

    @Test
    @DisplayName("refreshAuthentication: 기존 credentials·details가 보존된다")
    void refreshAuthentication_preservesCredentialsAndDetails() {
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        adminSecurityService.refreshAuthentication(1L);

        Authentication result = SecurityContextHolder.getContext().getAuthentication();
        assertThat(result.getCredentials()).isEqualTo("credentials");
        assertThat(result.getDetails()).isEqualTo("details-object");
    }

    @Test
    @DisplayName("refreshAuthentication: adminId가 null이면 repository 미호출 및 컨텍스트 유지")
    void refreshAuthentication_adminIdNull_noOp() {
        adminSecurityService.refreshAuthentication(null);

        verify(memberRepository, never()).findById(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    @DisplayName("refreshAuthentication: 인증 객체가 없으면 repository 미호출")
    void refreshAuthentication_noAuthentication_noOp() {
        SecurityContextHolder.clearContext();

        adminSecurityService.refreshAuthentication(1L);

        verify(memberRepository, never()).findById(any());
    }

    @Test
    @DisplayName("refreshAuthentication: principal이 CustomUserDetails가 아니면 repository 미호출")
    void refreshAuthentication_principalTypeMismatch_noOp() {
        // principal을 String으로 교체
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "anonymous", "credentials"
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        adminSecurityService.refreshAuthentication(1L);

        verify(memberRepository, never()).findById(any());
    }

    @Test
    @DisplayName("refreshAuthentication: 회원 조회 결과가 비면 SecurityContext가 초기화된다")
    void refreshAuthentication_memberNotFound_clearsContext() {
        given(memberRepository.findById(1L)).willReturn(Optional.empty());

        adminSecurityService.refreshAuthentication(1L);

        // 성공 응답은 이미 만들어진 뒤이므로 응답에는 영향 없고,
        // 다음 요청에서 재인증을 유도하기 위해 컨텍스트를 비운다(의도된 동작).
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("refreshAuthentication: repository 예외가 외부로 전파되지 않는다")
    void refreshAuthentication_repositoryException_isolated() {
        given(memberRepository.findById(1L)).willThrow(new RuntimeException("DB 오류"));

        // 예외가 전파되지 않아 이미 성공한 원 요청 결과를 500으로 뒤집지 않는다
        assertThatNoException().isThrownBy(() -> adminSecurityService.refreshAuthentication(1L));
    }
}
