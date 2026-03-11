package com.cms.admin.member.controller;

import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.AdminSignupRequest;
import com.cms.admin.member.dto.response.AdminSignupResponse;
import com.cms.admin.member.service.AdminMemberService;
import com.cms.common.api.GlobalApiExceptionHandler;
import com.cms.common.exception.DuplicateResourceException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.config.MethodSecurityTestConfig;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @BeforeEach
    void setUp() {
        reset(adminMemberService);
    }

    @TestConfiguration
    static class MockConfig {
        @Bean
        public AdminMemberService adminMemberService() {
            return Mockito.mock(AdminMemberService.class);
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
}