package com.cms.admin.menu.controller;

import com.cms.admin.menu.dto.request.MenuCreateRequest;
import com.cms.admin.menu.dto.request.MenuUpdateRequest;
import com.cms.admin.menu.dto.response.MenuResponse;
import com.cms.admin.menu.dto.response.MenuTreeResponse;
import com.cms.admin.menu.service.MenuService;
import com.cms.common.api.GlobalApiExceptionHandler;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MenuController.class)
@Import({
        MenuControllerTest.MockConfig.class,
        MethodSecurityTestConfig.class,
        GlobalApiExceptionHandler.class
})
class MenuControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    MenuService menuService;

    @Autowired
    AdminSecurityService adminSecurityService;

    @BeforeEach
    void setUp() {
        reset(menuService, adminSecurityService);
        given(adminSecurityService.getCurrentAdminName()).willReturn("관리자");
        given(adminSecurityService.getCurrentAdminProfileImageUrl()).willReturn(null);
    }

    @TestConfiguration
    static class MockConfig {
        @Bean
        public MenuService menuService() {
            return Mockito.mock(MenuService.class);
        }

        @Bean
        public AdminSecurityService adminSecurityService() {
            return Mockito.mock(AdminSecurityService.class);
        }
    }

    private MenuCreateRequest createRequest() {
        return MenuCreateRequest.builder()
                .menuName("회원 관리")
                .menuUrl("/admin/member/manage")
                .build();
    }

    private MenuResponse menuResponse() {
        return MenuResponse.builder()
                .menuNo(1L)
                .menuName("회원 관리")
                .menuUrl("/admin/member/manage")
                .useYn(true)
                .ord(0)
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .build();
    }

    // ===================== getMenuTree =====================

    @Test
    @DisplayName("메뉴 트리 조회 성공 (ADMIN)")
    @WithMockUser(roles = "ADMIN")
    void getMenuTree_success() throws Exception {
        MenuTreeResponse node = MenuTreeResponse.builder()
                .id("1")
                .text("회원 관리")
                .children(List.of())
                .build();
        given(menuService.getMenuTree("true")).willReturn(List.of(node));

        mockMvc.perform(get("/admin/api/menus/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("1"))
                .andExpect(jsonPath("$[0].text").value("회원 관리"));
    }

    @Test
    @DisplayName("허용되지 않은 useYn 값은 400 INVALID_REQUEST")
    @WithMockUser(roles = "ADMIN")
    void getMenuTree_invalidFilter() throws Exception {
        given(menuService.getMenuTree("false")).willThrow(new InvalidRequestException("useYn 파라미터는 true 또는 all만 허용됩니다."));

        mockMvc.perform(get("/admin/api/menus/tree").param("useYn", "false"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("인증 없이 트리 조회하면 401")
    void getMenuTree_unauthenticated() throws Exception {
        mockMvc.perform(get("/admin/api/menus/tree"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("MANAGER는 트리 조회 시 403")
    @WithMockUser(roles = "MANAGER")
    void getMenuTree_managerForbidden() throws Exception {
        mockMvc.perform(get("/admin/api/menus/tree"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(menuService);
    }

    @Test
    @DisplayName("USER는 트리 조회 시 403")
    @WithMockUser(roles = "USER")
    void getMenuTree_userForbidden() throws Exception {
        mockMvc.perform(get("/admin/api/menus/tree"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(menuService);
    }

    // ===================== getMenu =====================

    @Test
    @DisplayName("메뉴 단건 조회 성공")
    @WithMockUser(roles = "ADMIN")
    void getMenu_success() throws Exception {
        given(menuService.getMenu(1L)).willReturn(menuResponse());

        mockMvc.perform(get("/admin/api/menus/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menuNo").value(1))
                .andExpect(jsonPath("$.menuName").value("회원 관리"));
    }

    @Test
    @DisplayName("존재하지 않는 메뉴 조회 시 404")
    @WithMockUser(roles = "ADMIN")
    void getMenu_notFound() throws Exception {
        given(menuService.getMenu(99L)).willThrow(new ResourceNotFoundException("메뉴를 찾을 수 없습니다."));

        mockMvc.perform(get("/admin/api/menus/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // ===================== createMenu =====================

    @Test
    @DisplayName("메뉴 생성 성공 (201 Created + Location)")
    @WithMockUser(roles = "ADMIN")
    void createMenu_success() throws Exception {
        given(menuService.createMenu(any())).willReturn(menuResponse());

        mockMvc.perform(post("/admin/api/menus")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/admin/api/menus/1")))
                .andExpect(jsonPath("$.menuName").value("회원 관리"));
    }

    @Test
    @DisplayName("메뉴명 누락 시 400 VALIDATION_ERROR")
    @WithMockUser(roles = "ADMIN")
    void createMenu_validationFail() throws Exception {
        MenuCreateRequest badRequest = MenuCreateRequest.builder().menuName("").build();

        mockMvc.perform(post("/admin/api/menus")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(menuService);
    }

    @Test
    @DisplayName("비활성 부모 아래 활성 메뉴 생성 시 400 INVALID_REQUEST")
    @WithMockUser(roles = "ADMIN")
    void createMenu_activeUnderInactiveParent() throws Exception {
        given(menuService.createMenu(any()))
                .willThrow(new InvalidRequestException("비활성 부모 메뉴 아래에는 활성 메뉴를 생성할 수 없습니다."));

        mockMvc.perform(post("/admin/api/menus")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("존재하지 않는 부모 지정 생성 시 404")
    @WithMockUser(roles = "ADMIN")
    void createMenu_parentNotFound() throws Exception {
        given(menuService.createMenu(any())).willThrow(new ResourceNotFoundException("부모 메뉴를 찾을 수 없습니다."));

        mockMvc.perform(post("/admin/api/menus")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("인증 없이 메뉴 생성 시 401")
    void createMenu_unauthenticated() throws Exception {
        mockMvc.perform(post("/admin/api/menus")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("MANAGER는 메뉴 생성 시 403")
    @WithMockUser(roles = "MANAGER")
    void createMenu_managerForbidden() throws Exception {
        mockMvc.perform(post("/admin/api/menus")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(menuService);
    }

    // ===================== updateMenu =====================

    @Test
    @DisplayName("메뉴 수정 성공")
    @WithMockUser(roles = "ADMIN")
    void updateMenu_success() throws Exception {
        MenuUpdateRequest request = MenuUpdateRequest.builder().menuName("변경된 이름").build();
        given(menuService.updateMenu(anyLong(), any())).willReturn(menuResponse());

        mockMvc.perform(patch("/admin/api/menus/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menuNo").value(1));
    }

    @Test
    @DisplayName("PATCH menuName 빈 문자열은 400 VALIDATION_ERROR")
    @WithMockUser(roles = "ADMIN")
    void updateMenu_emptyName() throws Exception {
        mockMvc.perform(patch("/admin/api/menus/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(menuService);
    }

    @Test
    @DisplayName("PATCH menuName 100자 초과는 400 VALIDATION_ERROR")
    @WithMockUser(roles = "ADMIN")
    void updateMenu_nameTooLong() throws Exception {
        MenuUpdateRequest request = MenuUpdateRequest.builder().menuName("가".repeat(101)).build();

        mockMvc.perform(patch("/admin/api/menus/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(menuService);
    }

    @Test
    @DisplayName("PATCH menuName 공백만 입력은 400 VALIDATION_ERROR")
    @WithMockUser(roles = "ADMIN")
    void updateMenu_blankName() throws Exception {
        mockMvc.perform(patch("/admin/api/menus/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuName\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(menuService);
    }

    @Test
    @DisplayName("PATCH로 비활성 부모 아래 재활성화 시도 시 400 INVALID_REQUEST")
    @WithMockUser(roles = "ADMIN")
    void updateMenu_reactivateUnderInactiveParent() throws Exception {
        given(menuService.updateMenu(anyLong(), any()))
                .willThrow(new InvalidRequestException("비활성 부모 메뉴 아래로는 재활성화할 수 없습니다."));

        MenuUpdateRequest request = MenuUpdateRequest.builder().useYn(true).build();

        mockMvc.perform(patch("/admin/api/menus/2")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("존재하지 않는 메뉴 수정 시 404")
    @WithMockUser(roles = "ADMIN")
    void updateMenu_notFound() throws Exception {
        given(menuService.updateMenu(anyLong(), any())).willThrow(new ResourceNotFoundException("메뉴를 찾을 수 없습니다."));

        MenuUpdateRequest request = MenuUpdateRequest.builder().menuName("변경").build();

        mockMvc.perform(patch("/admin/api/menus/99")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("MANAGER는 메뉴 수정 시 403")
    @WithMockUser(roles = "MANAGER")
    void updateMenu_managerForbidden() throws Exception {
        MenuUpdateRequest request = MenuUpdateRequest.builder().menuName("변경").build();

        mockMvc.perform(patch("/admin/api/menus/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(menuService);
    }

    // ===================== deactivateMenu =====================

    @Test
    @DisplayName("메뉴 비활성화 성공 (204 No Content)")
    @WithMockUser(roles = "ADMIN")
    void deactivateMenu_success() throws Exception {
        given(menuService.deactivateMenu(1L)).willReturn(menuResponse());

        mockMvc.perform(delete("/admin/api/menus/1").with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("활성 하위 메뉴 보유 메뉴 비활성화 시 409 RESOURCE_CONFLICT")
    @WithMockUser(roles = "ADMIN")
    void deactivateMenu_conflict() throws Exception {
        given(menuService.deactivateMenu(1L)).willThrow(new ConflictException("활성 하위 메뉴가 있어 비활성화할 수 없습니다."));

        mockMvc.perform(delete("/admin/api/menus/1").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
    }

    @Test
    @DisplayName("존재하지 않는 메뉴 비활성화 시 404")
    @WithMockUser(roles = "ADMIN")
    void deactivateMenu_notFound() throws Exception {
        given(menuService.deactivateMenu(99L)).willThrow(new ResourceNotFoundException("메뉴를 찾을 수 없습니다."));

        mockMvc.perform(delete("/admin/api/menus/99").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("인증 없이 메뉴 비활성화 시 401")
    void deactivateMenu_unauthenticated() throws Exception {
        mockMvc.perform(delete("/admin/api/menus/1").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("USER는 메뉴 비활성화 시 403")
    @WithMockUser(roles = "USER")
    void deactivateMenu_userForbidden() throws Exception {
        mockMvc.perform(delete("/admin/api/menus/1").with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(menuService);
    }
}
