package com.cms.admin.member.controller;

import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.AdminMemberSearchRequest;
import com.cms.admin.member.dto.request.AdminMyInfoUpdateRequest;
import com.cms.admin.member.dto.request.AdminMyPasswordChangeRequest;
import com.cms.admin.member.dto.request.AdminSignupRequest;
import com.cms.admin.member.dto.response.AdminMemberPageResponse;
import com.cms.admin.member.dto.response.AdminMemberResponse;
import com.cms.admin.member.dto.response.AdminSignupResponse;
import com.cms.admin.member.service.AdminMemberService;
import com.cms.common.api.GlobalApiExceptionHandler;
import com.cms.common.exception.DuplicateResourceException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.config.MethodSecurityTestConfig;
import com.cms.config.auth.AdminSecurityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@WebMvcTest(controllers = AdminMemberController.class)
@Import({
        AdminMemberControllerTest.MockConfig.class,
        MethodSecurityTestConfig.class,
        GlobalApiExceptionHandler.class
})
class AdminMemberControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AdminMemberService adminMemberService;

    @Autowired
    AdminSecurityService adminSecurityService;

    @BeforeEach
    void setUp() {
        reset(adminMemberService, adminSecurityService);
        given(adminSecurityService.getCurrentAdminName()).willReturn("관리자");
        given(adminSecurityService.getCurrentAdminProfileImageUrl()).willReturn(null);
    }

    @TestConfiguration
    static class MockConfig {
        @Bean
        public AdminMemberService adminMemberService() {
            return Mockito.mock(AdminMemberService.class);
        }

        @Bean
        public AdminSecurityService adminSecurityService() {
            return Mockito.mock(AdminSecurityService.class);
        }
    }

    private AdminSignupRequest request() {
        return AdminSignupRequest.builder()
                .userId("admin01")
                .pwd("Admin1234!")
                .userName("홍길동")
                .email("admin@test.com")
                .userType(Role.ROLE_ADMIN)
                .build();
    }

    private AdminMemberResponse adminMemberResponse() {
        return AdminMemberResponse.builder()
                .id(1L)
                .userId("admin01")
                .userName("홍길동")
                .email("admin01@test.com")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .createDate(new Date())
                .updateDate(new Date())
                .build();
    }

    private AdminMyInfoUpdateRequest myInfoUpdateRequest() {
        return AdminMyInfoUpdateRequest.builder()
                .userName("홍길동 수정")
                .email("admin02@test.com")
                .build();
    }

    @Test
    @DisplayName("관리자 계정 생성 성공")
    @WithMockUser(roles = "ADMIN")
    void createAdmin_success() throws Exception {

        AdminSignupResponse response = AdminSignupResponse.builder()
                .id(1L)
                .userId("admin01")
                .userName("홍길동")
                .email("admin@test.com")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .createDate(new Date())
                .build();
        given(adminMemberService.createAdmin(any())).willReturn(response);

        mockMvc.perform(post("/admin/api/member")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("admin01"))
                .andExpect(jsonPath("$.userName").value("홍길동"))
                .andExpect(jsonPath("$.email").value("admin@test.com"))
                .andExpect(jsonPath("$.userType").value("ROLE_ADMIN"));
    }

    @Test
    @DisplayName("검증 실패면 400 Error")
    @WithMockUser(roles = "ADMIN")
    void createAdmin_validation_fail() throws Exception {
        AdminSignupRequest badRequest = AdminSignupRequest.builder()
                .userId("")
                .pwd("Admin1234!")
                .userName("홍길동")
                .email("admin01@test.com")
                .userType(Role.ROLE_ADMIN)
                .build();

        mockMvc.perform(post("/admin/api/member")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(badRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("인증 없이 호출하면 401 ERROR")
    void createAdmin_unauthenticated() throws Exception {
        mockMvc.perform(post("/admin/api/member")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("관리자 권한 없이 호출하면 403 ERROR")
    @WithMockUser(roles = "USER")
    void createAdmin_forbidden() throws Exception {
        mockMvc.perform(post("/admin/api/member")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("중복 아이디면 409 ERROR")
    @WithMockUser(roles = "ADMIN")
    void createAdmin_duplicate() throws Exception {
        given(adminMemberService.createAdmin(any())).willThrow(new DuplicateResourceException("이미 사용 중인 아이디입니다."));

        mockMvc.perform(post("/admin/api/member")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.message").value("이미 사용 중인 아이디입니다."));
    }

    @Test
    @DisplayName("비즈니스 요청 오류면 400 ERROR")
    @WithMockUser(roles = "ADMIN")
    void createAdmin_invalidRequest() throws Exception{
        given(adminMemberService.createAdmin(any())).willThrow(new InvalidRequestException("관리자 생성 API에서는 userType은 ROLE_ADMIN만 허용됩니다."));

        mockMvc.perform(post("/admin/api/member")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("관리자 생성 API에서는 userType은 ROLE_ADMIN만 허용됩니다."));
    }

    @Test
    @DisplayName("서버 오류 500 ERROR")
    @WithMockUser(roles = "ADMIN")
    void createAdmin_internal() throws Exception {
        given(adminMemberService.createAdmin(any()))
                .willThrow(new RuntimeException("Server Error"));

        mockMvc.perform(post("/admin/api/member")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    @DisplayName("관리자 목록 조회 성공")
    @WithMockUser(roles = "ADMIN")
    void getAdminMembers_success() throws Exception {
        AdminMemberPageResponse pageResponse = AdminMemberPageResponse.builder()
                .content(List.of(adminMemberResponse()))
                .page(0)
                .size(20)
                .totalElements(1)
                .totalPages(1)
                .last(true)
                .build();

        given(adminMemberService.getAdminMembers(any(), any())).willReturn(pageResponse);

        mockMvc.perform(get("/admin/api/members")
                .param("page", "0")
                .param("size", "20")
                .param("userId", "admin")
                .param("status", "ACTIVE")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].userId").value("admin01"))
                .andExpect(jsonPath("$.content[0].userType").value("ROLE_ADMIN"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("관리자 상세 조회 성공")
    @WithMockUser(roles = "ADMIN")
    void getAdminMember_success() throws Exception {
        given(adminMemberService.getAdminMember(anyLong())).willReturn(adminMemberResponse());

        mockMvc.perform(get("/admin/api/member/1")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.userId").value("admin01"));
    }

    @Test
    @DisplayName("내 관리자 정보 조회 성공")
    @WithMockUser(roles = "ADMIN")
    void getMyInfo_success() throws Exception {
        given(adminMemberService.getMyInfo()).willReturn(adminMemberResponse());

        mockMvc.perform(get("/admin/api/member/info")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("admin01"));
    }

    @Test
    @DisplayName("내 관리자 정보 수정 성공")
    @WithMockUser(roles = "ADMIN")
    void updateMyInfo_success() throws Exception {
        AdminMemberResponse response = AdminMemberResponse.builder()
                .id(1L)
                .userId("admin01")
                .userName("홍길동 수정")
                .email("admin02@test.com")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .createDate(new Date())
                .updateDate(new Date())
                .build();

        given(adminMemberService.updateMyInfo(any())).willReturn(response);

        mockMvc.perform(patch("/admin/api/member/info")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(myInfoUpdateRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userName").value("홍길동 수정"))
                .andExpect(jsonPath("$.email").value("admin02@test.com"));
    }

    @Test
    @DisplayName("내 관리자 정보 수정 요청 검증 실패면 400 ERROR")
    @WithMockUser(roles = "ADMIN")
    void updateMyInfo_validationFail() throws Exception {
        AdminMyInfoUpdateRequest badRequest = AdminMyInfoUpdateRequest.builder()
                .userName("")
                .email("not-email")
                .build();

        mockMvc.perform(patch("/admin/api/member/info")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("내 관리자 정보 수정 시 중복 이메일이면 409 ERROR")
    @WithMockUser(roles = "ADMIN")
    void updateMyInfo_duplicateEmail() throws Exception {
        given(adminMemberService.updateMyInfo(any()))
                .willThrow(new DuplicateResourceException("이미 사용 중인 이메일입니다."));

        mockMvc.perform(patch("/admin/api/member/info")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(myInfoUpdateRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.message").value("이미 사용 중인 이메일입니다."));
    }

    @Test
    @DisplayName("관리자 권한 없이 내 관리자 정보 수정하면 403 ERROR")
    @WithMockUser(roles = "USER")
    void updateMyInfo_forbidden() throws Exception {
        mockMvc.perform(patch("/admin/api/member/info")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(myInfoUpdateRequest())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminMemberService);
    }

    @Test
    @DisplayName("비밀번호 변경 성공")
    @WithMockUser(roles = "ADMIN")
    void changeMyPassword_success() throws Exception {
        AdminMyPasswordChangeRequest request = AdminMyPasswordChangeRequest.builder()
                .currentPassword("Admin1234!")
                .newPassword("NewAdmin1234!")
                .confirmPassword("NewAdmin1234!")
                .build();

        given(adminMemberService.changeMyPassword(any())).willReturn(adminMemberResponse());

        mockMvc.perform(patch("/admin/api/member/info/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("admin01"));
    }

    @Test
    @DisplayName("비밀번호 변경 요청 검증 실패면 400 ERROR")
    @WithMockUser(roles = "ADMIN")
    void changeMyPassword_validationFail() throws Exception {
        AdminMyPasswordChangeRequest badRequest = AdminMyPasswordChangeRequest.builder()
                .currentPassword("")
                .newPassword("NewAdmin1234!")
                .confirmPassword("NewAdmin1234!")
                .build();

        mockMvc.perform(patch("/admin/api/member/info/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(adminMemberService);
    }

    @Test
    @DisplayName("현재 비밀번호 불일치면 400 ERROR")
    @WithMockUser(roles = "ADMIN")
    void changeMyPassword_wrongPassword() throws Exception {
        AdminMyPasswordChangeRequest request = AdminMyPasswordChangeRequest.builder()
                .currentPassword("WrongPassword!")
                .newPassword("NewAdmin1234!")
                .confirmPassword("NewAdmin1234!")
                .build();

        given(adminMemberService.changeMyPassword(any()))
                .willThrow(new InvalidRequestException("현재 비밀번호가 올바르지 않습니다."));

        mockMvc.perform(patch("/admin/api/member/info/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("현재 비밀번호가 올바르지 않습니다."));
    }

    @Test
    @DisplayName("관리자 권한 없이 비밀번호 변경하면 403 ERROR")
    @WithMockUser(roles = "USER")
    void changeMyPassword_forbidden() throws Exception {
        AdminMyPasswordChangeRequest request = AdminMyPasswordChangeRequest.builder()
                .currentPassword("Admin1234!")
                .newPassword("NewAdmin1234!")
                .confirmPassword("NewAdmin1234!")
                .build();

        mockMvc.perform(patch("/admin/api/member/info/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminMemberService);
    }

    @Test
    @DisplayName("인증 없이 관리자 목록 조회하면 401 ERROR")
    void getAdminMembers_unauthenticated() throws Exception {
        mockMvc.perform(get("/admin/api/members")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("관리자 권한이 아니면 관리자 상세 조회 403")
    @WithMockUser(roles = "USER")
    void getAdminMember_forbidden() throws Exception {
        mockMvc.perform(get("/admin/api/member/1")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminMemberService);
    }
}
