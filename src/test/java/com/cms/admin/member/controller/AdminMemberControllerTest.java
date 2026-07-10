package com.cms.admin.member.controller;

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
import com.cms.admin.member.service.AdminMemberService;
import com.cms.admin.menu.service.MenuService;
import com.cms.common.api.GlobalApiExceptionHandler;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.DuplicateResourceException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.config.MethodSecurityTestConfig;
import com.cms.config.auth.AdminSecurityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
        // "me" 엔드포인트의 requireCurrentAdminId() 기본 스텁 — 각 테스트에서 null로 오버라이드 가능
        given(adminSecurityService.getCurrentAdminId()).willReturn(1L);
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

        // AdminSidebarAdvice(@ControllerAdvice)가 슬라이스 컨텍스트에 포함되므로 의존 빈이 필요하다.
        @Bean
        public MenuService menuService() {
            return Mockito.mock(MenuService.class);
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
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .build();
    }

    private AdminMyInfoUpdateRequest myInfoUpdateRequest() {
        return AdminMyInfoUpdateRequest.builder()
                .userName("홍길동 수정")
                .email("admin02@test.com")
                .build();
    }

    // ===================== createAdmin =====================

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
                .createDate(LocalDateTime.now())
                .build();
        given(adminMemberService.createAdmin(any())).willReturn(response);

        mockMvc.perform(post("/admin/api/members")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/admin/api/members/1")))
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

        mockMvc.perform(post("/admin/api/members")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(badRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("인증 없이 호출하면 401 ERROR")
    void createAdmin_unauthenticated() throws Exception {
        mockMvc.perform(post("/admin/api/members")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("관리자 권한 없이 호출하면 403 ERROR")
    @WithMockUser(roles = "USER")
    void createAdmin_forbidden() throws Exception {
        mockMvc.perform(post("/admin/api/members")
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

        mockMvc.perform(post("/admin/api/members")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.message").value("이미 사용 중인 아이디입니다."));
    }

    @Test
    @DisplayName("서비스에서 InvalidRequestException이 발생하면 400 INVALID_REQUEST")
    @WithMockUser(roles = "ADMIN")
    void createAdmin_invalidRequest() throws Exception{
        given(adminMemberService.createAdmin(any())).willThrow(new InvalidRequestException("잘못된 요청입니다."));

        mockMvc.perform(post("/admin/api/members")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));
    }

    @Test
    @DisplayName("userType=ROLE_USER 요청 시 @AllowedRoles 검증 실패 → 400 VALIDATION_ERROR")
    @WithMockUser(roles = "ADMIN")
    void createAdmin_userType_roleUser_returns400() throws Exception {
        AdminSignupRequest roleUserRequest = AdminSignupRequest.builder()
                .userId("admin01")
                .pwd("Admin1234!")
                .userName("홍길동")
                .email("admin@test.com")
                .userType(Role.ROLE_USER)
                .build();

        mockMvc.perform(post("/admin/api/members")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(roleUserRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(adminMemberService);
    }

    @Test
    @DisplayName("userType=ROLE_MANAGER 요청 시 201 Created 성공")
    @WithMockUser(roles = "ADMIN")
    void createAdmin_userType_roleManager_returns201() throws Exception {
        AdminSignupRequest roleManagerRequest = AdminSignupRequest.builder()
                .userId("manager01")
                .pwd("Manager1234!")
                .userName("김매니저")
                .email("manager01@test.com")
                .userType(Role.ROLE_MANAGER)
                .build();

        AdminSignupResponse response = AdminSignupResponse.builder()
                .id(2L)
                .userId("manager01")
                .userName("김매니저")
                .email("manager01@test.com")
                .userType(Role.ROLE_MANAGER)
                .status(MemberStatus.ACTIVE)
                .createDate(LocalDateTime.now())
                .build();
        given(adminMemberService.createAdmin(any())).willReturn(response);

        mockMvc.perform(post("/admin/api/members")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(roleManagerRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value("manager01"))
                .andExpect(jsonPath("$.userType").value("ROLE_MANAGER"));
    }

    @Test
    @DisplayName("서버 오류 500 ERROR")
    @WithMockUser(roles = "ADMIN")
    void createAdmin_internal() throws Exception {
        given(adminMemberService.createAdmin(any()))
                .willThrow(new RuntimeException("Server Error"));

        mockMvc.perform(post("/admin/api/members")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    // ===================== getAdminMembers =====================

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
    @DisplayName("관리자 권한 없이 관리자 목록 조회하면 403 ERROR")
    @WithMockUser(roles = "USER")
    void getAdminMembers_forbidden() throws Exception {
        mockMvc.perform(get("/admin/api/members")
                .accept(MediaType.APPLICATION_JSON))
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
    @DisplayName("검색 userId가 50자 초과면 400 VALIDATION_ERROR")
    @WithMockUser(roles = "ADMIN")
    void getAdminMembers_userId_tooLong_returns400() throws Exception {
        String tooLongUserId = "a".repeat(51);

        mockMvc.perform(get("/admin/api/members")
                        .param("userId", tooLongUserId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(adminMemberService);
    }

    // ===================== getAdminMember =====================

    @Test
    @DisplayName("관리자 상세 조회 성공")
    @WithMockUser(roles = "ADMIN")
    void getAdminMember_success() throws Exception {
        given(adminMemberService.getAdminMember(anyLong())).willReturn(adminMemberResponse());

        mockMvc.perform(get("/admin/api/members/1")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.userId").value("admin01"));
    }

    @Test
    @DisplayName("관리자 권한이 아니면 관리자 상세 조회 403")
    @WithMockUser(roles = "USER")
    void getAdminMember_forbidden() throws Exception {
        mockMvc.perform(get("/admin/api/members/1")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminMemberService);
    }

    // ===================== updateAdminMember (PATCH /members/{id}) =====================

    @Test
    @DisplayName("타 관리자 수정 성공 — 현재 관리자 id와 대상 id가 서비스에 전달된다")
    @WithMockUser(roles = "ADMIN")
    void updateAdminMember_success() throws Exception {
        AdminMemberResponse response = AdminMemberResponse.builder()
                .id(2L)
                .userId("manager01")
                .userName("김매니저")
                .email("manager01@test.com")
                .userType(Role.ROLE_MANAGER)
                .status(MemberStatus.LOCKED)
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .build();
        given(adminMemberService.updateAdminMember(anyLong(), anyLong(), any())).willReturn(response);

        mockMvc.perform(patch("/admin/api/members/2")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.status").value("LOCKED"));

        verify(adminMemberService).updateAdminMember(eq(1L), eq(2L), any(AdminMemberUpdateRequest.class));
    }

    @Test
    @DisplayName("전부 null인 요청은 400 VALIDATION_ERROR — 서비스 미호출")
    @WithMockUser(roles = "ADMIN")
    void updateAdminMember_allNull_returns400() throws Exception {
        mockMvc.perform(patch("/admin/api/members/2")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(adminMemberService);
    }

    @Test
    @DisplayName("status=DELETED 요청은 @AllowedStatuses 검증 실패 → 400")
    @WithMockUser(roles = "ADMIN")
    void updateAdminMember_statusDeleted_returns400() throws Exception {
        mockMvc.perform(patch("/admin/api/members/2")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELETED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(adminMemberService);
    }

    @Test
    @DisplayName("status=PASSWORD_EXPIRED 요청도 400 — 시스템 전이 상태는 수동 지정 불가")
    @WithMockUser(roles = "ADMIN")
    void updateAdminMember_statusPasswordExpired_returns400() throws Exception {
        mockMvc.perform(patch("/admin/api/members/2")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PASSWORD_EXPIRED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(adminMemberService);
    }

    @Test
    @DisplayName("userType=ROLE_USER 요청은 @AllowedRoles 검증 실패 → 400")
    @WithMockUser(roles = "ADMIN")
    void updateAdminMember_roleUser_returns400() throws Exception {
        mockMvc.perform(patch("/admin/api/members/2")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userType\":\"ROLE_USER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(adminMemberService);
    }

    @Test
    @DisplayName("이메일이 빈 문자열/공백뿐이면 400 VALIDATION_ERROR — 빈 이메일 저장 차단 (Codex 리뷰 P2)")
    @WithMockUser(roles = "ADMIN")
    void updateAdminMember_blankEmail_returns400() throws Exception {
        for (String blankEmail : new String[]{"\"\"", "\"   \""}) {
            mockMvc.perform(patch("/admin/api/members/2")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":" + blankEmail + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        verifyNoInteractions(adminMemberService);
    }

    @Test
    @DisplayName("이름이 공백뿐이면 400 VALIDATION_ERROR")
    @WithMockUser(roles = "ADMIN")
    void updateAdminMember_blankUserName_returns400() throws Exception {
        mockMvc.perform(patch("/admin/api/members/2")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(adminMemberService);
    }

    @Test
    @DisplayName("본인 계정 대상이면 서비스가 InvalidRequestException → 400 INVALID_REQUEST")
    @WithMockUser(roles = "ADMIN")
    void updateAdminMember_selfTarget_returns400() throws Exception {
        given(adminMemberService.updateAdminMember(anyLong(), anyLong(), any()))
                .willThrow(new InvalidRequestException("본인 계정은 내 정보 수정을 이용해주세요."));

        mockMvc.perform(patch("/admin/api/members/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"변경\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("본인 계정은 내 정보 수정을 이용해주세요."));
    }

    @Test
    @DisplayName("DELETED 계정·최후 활성 ADMIN 등 상태 충돌은 409 RESOURCE_CONFLICT")
    @WithMockUser(roles = "ADMIN")
    void updateAdminMember_conflict_returns409() throws Exception {
        given(adminMemberService.updateAdminMember(anyLong(), anyLong(), any()))
                .willThrow(new ConflictException("최소 1명의 활성 관리자가 유지되어야 합니다."));

        mockMvc.perform(patch("/admin/api/members/2")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
    }

    @Test
    @DisplayName("비관적 락 실패(락 타임아웃·데드락)는 500이 아닌 409 RESOURCE_CONFLICT로 변환된다")
    @WithMockUser(roles = "ADMIN")
    void updateAdminMember_pessimisticLockFailure_returns409() throws Exception {
        given(adminMemberService.updateAdminMember(anyLong(), anyLong(), any()))
                .willThrow(new org.springframework.dao.CannotAcquireLockException("deadlock detected"));

        mockMvc.perform(patch("/admin/api/members/2")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"))
                .andExpect(jsonPath("$.message").value("동시 변경과 충돌했습니다. 다시 시도해주세요."));
    }

    @Test
    @DisplayName("없는 대상·ROLE_USER 대상이면 404 RESOURCE_NOT_FOUND")
    @WithMockUser(roles = "ADMIN")
    void updateAdminMember_notFound_returns404() throws Exception {
        given(adminMemberService.updateAdminMember(anyLong(), anyLong(), any()))
                .willThrow(new ResourceNotFoundException("관리자를 찾을 수 없습니다."));

        mockMvc.perform(patch("/admin/api/members/99")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"변경\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("MANAGER는 타 관리자 수정을 할 수 없다(403)")
    @WithMockUser(roles = "MANAGER")
    void updateAdminMember_manager_forbidden() throws Exception {
        mockMvc.perform(patch("/admin/api/members/2")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminMemberService);
    }

    @Test
    @DisplayName("인증 없이 타 관리자 수정하면 401")
    void updateAdminMember_unauthenticated() throws Exception {
        mockMvc.perform(patch("/admin/api/members/2")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ===================== getMyInfo =====================

    @Test
    @DisplayName("내 관리자 정보 조회 성공")
    @WithMockUser(roles = "ADMIN")
    void getMyInfo_success() throws Exception {
        given(adminMemberService.getMyInfo(1L)).willReturn(adminMemberResponse());

        mockMvc.perform(get("/admin/api/members/me")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("admin01"));

        verify(adminMemberService).getMyInfo(1L);
    }

    @Test
    @DisplayName("getCurrentAdminId()가 null이면 403 ACCESS_DENIED")
    @WithMockUser(roles = "ADMIN")
    void getMyInfo_adminIdNull_returns403() throws Exception {
        given(adminSecurityService.getCurrentAdminId()).willReturn(null);

        mockMvc.perform(get("/admin/api/members/me")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(adminMemberService);
    }

    // ===================== updateMyInfo =====================

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
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .build();

        given(adminMemberService.updateMyInfo(anyLong(), any())).willReturn(response);

        mockMvc.perform(patch("/admin/api/members/me")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(myInfoUpdateRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userName").value("홍길동 수정"))
                .andExpect(jsonPath("$.email").value("admin02@test.com"));

        // 서비스 성공 후 refresh가 호출되어야 한다
        // DTO는 JSON 역직렬화 후 새 인스턴스이므로 any()로 매칭한다
        InOrder inOrder = Mockito.inOrder(adminMemberService, adminSecurityService);
        inOrder.verify(adminMemberService).updateMyInfo(eq(1L), any(AdminMyInfoUpdateRequest.class));
        inOrder.verify(adminSecurityService).refreshAuthentication(1L);
    }

    @Test
    @DisplayName("내 관리자 정보 수정 요청 검증 실패면 400 ERROR")
    @WithMockUser(roles = "ADMIN")
    void updateMyInfo_validationFail() throws Exception {
        AdminMyInfoUpdateRequest badRequest = AdminMyInfoUpdateRequest.builder()
                .userName("")
                .email("not-email")
                .build();

        mockMvc.perform(patch("/admin/api/members/me")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("내 관리자 정보 수정 시 중복 이메일이면 409 ERROR — 서비스 실패 시 refresh 미호출")
    @WithMockUser(roles = "ADMIN")
    void updateMyInfo_duplicateEmail() throws Exception {
        given(adminMemberService.updateMyInfo(anyLong(), any()))
                .willThrow(new DuplicateResourceException("이미 사용 중인 이메일입니다."));

        mockMvc.perform(patch("/admin/api/members/me")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(myInfoUpdateRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.message").value("이미 사용 중인 이메일입니다."));

        verify(adminSecurityService, Mockito.never()).refreshAuthentication(anyLong());
    }

    @Test
    @DisplayName("관리자 권한 없이 내 관리자 정보 수정하면 403 ERROR")
    @WithMockUser(roles = "USER")
    void updateMyInfo_forbidden() throws Exception {
        mockMvc.perform(patch("/admin/api/members/me")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(myInfoUpdateRequest())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminMemberService);
    }

    // ===================== changeMyPassword =====================

    @Test
    @DisplayName("비밀번호 변경 성공")
    @WithMockUser(roles = "ADMIN")
    void changeMyPassword_success() throws Exception {
        AdminMyPasswordChangeRequest request = AdminMyPasswordChangeRequest.builder()
                .currentPassword("Admin1234!")
                .newPassword("NewAdmin1234!")
                .confirmPassword("NewAdmin1234!")
                .build();

        given(adminMemberService.changeMyPassword(anyLong(), any())).willReturn(adminMemberResponse());

        mockMvc.perform(patch("/admin/api/members/me/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("admin01"));

        // 서비스 성공 후 refresh가 호출되어야 한다
        // DTO는 JSON 역직렬화 후 새 인스턴스이므로 any()로 매칭한다
        InOrder inOrder = Mockito.inOrder(adminMemberService, adminSecurityService);
        inOrder.verify(adminMemberService).changeMyPassword(eq(1L), any(AdminMyPasswordChangeRequest.class));
        inOrder.verify(adminSecurityService).refreshAuthentication(1L);
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

        mockMvc.perform(patch("/admin/api/members/me/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(adminMemberService);
    }

    @Test
    @DisplayName("현재 비밀번호 불일치면 400 ERROR — 서비스 실패 시 refresh 미호출")
    @WithMockUser(roles = "ADMIN")
    void changeMyPassword_wrongPassword() throws Exception {
        AdminMyPasswordChangeRequest request = AdminMyPasswordChangeRequest.builder()
                .currentPassword("WrongPassword!")
                .newPassword("NewAdmin1234!")
                .confirmPassword("NewAdmin1234!")
                .build();

        given(adminMemberService.changeMyPassword(anyLong(), any()))
                .willThrow(new InvalidRequestException("현재 비밀번호가 올바르지 않습니다."));

        mockMvc.perform(patch("/admin/api/members/me/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("현재 비밀번호가 올바르지 않습니다."));

        verify(adminSecurityService, Mockito.never()).refreshAuthentication(anyLong());
    }

    @Test
    @DisplayName("현재 관리자를 찾을 수 없으면 404 ERROR")
    @WithMockUser(roles = "ADMIN")
    void changeMyPassword_memberNotFound() throws Exception {
        AdminMyPasswordChangeRequest request = AdminMyPasswordChangeRequest.builder()
                .currentPassword("Admin1234!")
                .newPassword("NewAdmin1234!")
                .confirmPassword("NewAdmin1234!")
                .build();

        given(adminMemberService.changeMyPassword(anyLong(), any()))
                .willThrow(new ResourceNotFoundException("관리자를 찾을 수 없습니다."));

        mockMvc.perform(patch("/admin/api/members/me/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("관리자를 찾을 수 없습니다."));
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

        mockMvc.perform(patch("/admin/api/members/me/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminMemberService);
    }

    // ===================== profile-image =====================

    @Test
    @DisplayName("기본 프로필 이미지 선택 성공 (PUT JSON)")
    @WithMockUser(roles = "ADMIN")
    void applyDefaultProfileImage_success() throws Exception {
        given(adminMemberService.applyDefaultProfileImage(anyLong(), anyString())).willReturn(adminMemberResponse());

        mockMvc.perform(put("/admin/api/members/me/profile-image")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preset\":\"profile-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("admin01"));

        // 서비스 성공 후 refresh가 호출되어야 한다
        InOrder inOrder = Mockito.inOrder(adminMemberService, adminSecurityService);
        inOrder.verify(adminMemberService).applyDefaultProfileImage(1L, "profile-1");
        inOrder.verify(adminSecurityService).refreshAuthentication(1L);
    }

    @Test
    @DisplayName("기본 프로필 이미지 preset 누락 시 400 ERROR")
    @WithMockUser(roles = "ADMIN")
    void applyDefaultProfileImage_blankPreset() throws Exception {
        mockMvc.perform(put("/admin/api/members/me/profile-image")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preset\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("프로필 이미지 초기화 성공 (DELETE 204)")
    @WithMockUser(roles = "ADMIN")
    void resetMyProfileImage_success() throws Exception {
        mockMvc.perform(delete("/admin/api/members/me/profile-image")
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // 서비스 성공 후 refresh가 호출되어야 한다
        InOrder inOrder = Mockito.inOrder(adminMemberService, adminSecurityService);
        inOrder.verify(adminMemberService).resetMyProfileImage(1L);
        inOrder.verify(adminSecurityService).refreshAuthentication(1L);
    }

    // ===================== MANAGER 권한 — self API 접근 검증 ====================

    @Test
    @DisplayName("MANAGER는 자기 자신 정보 조회(GET /members/me) 가능하다")
    @WithMockUser(roles = "MANAGER")
    void getMyInfo_manager_ok() throws Exception {
        given(adminMemberService.getMyInfo(anyLong())).willReturn(adminMemberResponse());

        mockMvc.perform(get("/admin/api/members/me"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("MANAGER는 자기 자신 정보 수정(PATCH /members/me) 가능하다")
    @WithMockUser(roles = "MANAGER")
    void updateMyInfo_manager_ok() throws Exception {
        given(adminMemberService.updateMyInfo(anyLong(), any())).willReturn(adminMemberResponse());

        mockMvc.perform(patch("/admin/api/members/me")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(myInfoUpdateRequest())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("MANAGER는 비밀번호 변경(PATCH /members/me/password) 가능하다")
    @WithMockUser(roles = "MANAGER")
    void changeMyPassword_manager_ok() throws Exception {
        given(adminMemberService.changeMyPassword(anyLong(), any())).willReturn(adminMemberResponse());

        AdminMyPasswordChangeRequest pwdRequest = AdminMyPasswordChangeRequest.builder()
                .currentPassword("Admin1234!")
                .newPassword("NewAdmin1234!")
                .confirmPassword("NewAdmin1234!")
                .build();

        mockMvc.perform(patch("/admin/api/members/me/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pwdRequest)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("MANAGER는 프로필 이미지 초기화(DELETE /members/me/profile-image) 가능하다")
    @WithMockUser(roles = "MANAGER")
    void resetMyProfileImage_manager_ok() throws Exception {
        mockMvc.perform(delete("/admin/api/members/me/profile-image")
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("MANAGER는 관리자 계정 생성(POST /members)을 할 수 없다(403)")
    @WithMockUser(roles = "MANAGER")
    void createAdmin_manager_forbidden() throws Exception {
        mockMvc.perform(post("/admin/api/members")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminMemberService);
    }

    @Test
    @DisplayName("MANAGER는 관리자 목록 조회(GET /members)를 할 수 없다(403)")
    @WithMockUser(roles = "MANAGER")
    void getAdminMembers_manager_forbidden() throws Exception {
        mockMvc.perform(get("/admin/api/members"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminMemberService);
    }
}
