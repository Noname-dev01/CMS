package com.cms.admin.menu.controller;

import com.cms.admin.menu.MenuAccessRole;
import com.cms.admin.menu.dto.request.MenuCreateRequest;
import com.cms.admin.menu.dto.request.MenuMoveRequest;
import com.cms.admin.menu.dto.request.MenuOrderRequest;
import com.cms.admin.menu.dto.request.MenuOrderScope;
import com.cms.admin.menu.dto.request.MenuUpdateRequest;
import com.cms.admin.menu.dto.response.MenuMoveResponse;
import com.cms.admin.menu.dto.response.MenuOrderResponse;
import com.cms.admin.menu.dto.response.MenuResponse;
import com.cms.admin.menu.dto.response.MenuTreeResponse;
import com.cms.admin.menu.service.MenuService;
import com.cms.common.api.GlobalApiExceptionHandler;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.config.MethodSecurityTestConfig;
import com.cms.config.auth.AdminSecurityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

    @Test
    @DisplayName("메뉴 조회 경로 변수가 숫자가 아니면 400 INVALID_REQUEST (이전엔 500으로 오분류됨, 감사 M-03)")
    @WithMockUser(roles = "ADMIN")
    void getMenu_pathVariableTypeMismatch_returns400() throws Exception {
        mockMvc.perform(get("/admin/api/menus/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(menuService);
    }

    // ===================== accessRole =====================

    @Test
    @DisplayName("메뉴 생성 시 accessRole이 서비스로 전달되고 응답에 포함된다")
    @WithMockUser(roles = "ADMIN")
    void createMenu_accessRoleRoundTrip() throws Exception {
        MenuCreateRequest request = MenuCreateRequest.builder()
                .menuName("메뉴 관리")
                .menuUrl("/admin/menu/manage")
                .accessRole(MenuAccessRole.ADMIN)
                .build();
        MenuResponse response = MenuResponse.builder()
                .menuNo(1L)
                .menuName("메뉴 관리")
                .accessRole(MenuAccessRole.ADMIN)
                .useYn(true)
                .build();

        ArgumentCaptor<MenuCreateRequest> captor = ArgumentCaptor.forClass(MenuCreateRequest.class);
        given(menuService.createMenu(captor.capture())).willReturn(response);

        mockMvc.perform(post("/admin/api/menus")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessRole").value("ADMIN"));

        assertEquals(MenuAccessRole.ADMIN, captor.getValue().getAccessRole());
    }

    @Test
    @DisplayName("허용되지 않은 accessRole 값은 400 (enum 바인딩 실패)")
    @WithMockUser(roles = "ADMIN")
    void createMenu_invalidAccessRole_badRequest() throws Exception {
        String body = "{\"menuName\":\"메뉴\",\"accessRole\":\"MANAGER\"}";

        mockMvc.perform(post("/admin/api/menus")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(menuService);
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

    // ===================== reorderMenus (PUT /order) =====================

    private static String orderBody(String upMenuNo, String scope, String menuNos) {
        return "{\"upMenuNo\":" + upMenuNo + ",\"scope\":" + scope + ",\"menuNos\":" + menuNos + "}";
    }

    @Test
    @DisplayName("형제 순서 재조정 성공: 요청이 서비스로 전달되고 적용된 순서가 응답된다")
    @WithMockUser(roles = "ADMIN")
    void reorderMenus_success() throws Exception {
        given(menuService.reorderMenus(any())).willReturn(MenuOrderResponse.builder()
                .upMenuNo(3L)
                .menus(List.of(
                        MenuOrderResponse.Item.builder().menuNo(5L).ord(0).build(),
                        MenuOrderResponse.Item.builder().menuNo(4L).ord(1).build()))
                .build());

        mockMvc.perform(put("/admin/api/menus/order")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("3", "\"ACTIVE\"", "[5,4]")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.upMenuNo").value(3))
                .andExpect(jsonPath("$.menus[0].menuNo").value(5))
                .andExpect(jsonPath("$.menus[0].ord").value(0))
                .andExpect(jsonPath("$.menus[1].menuNo").value(4));

        ArgumentCaptor<MenuOrderRequest> captor = ArgumentCaptor.forClass(MenuOrderRequest.class);
        verify(menuService).reorderMenus(captor.capture());
        assertEquals(3L, captor.getValue().getUpMenuNo());
        assertEquals(MenuOrderScope.ACTIVE, captor.getValue().getScope());
        assertEquals(List.of(5L, 4L), captor.getValue().getMenuNos());
    }

    @Test
    @DisplayName("최상위 재조정은 upMenuNo=null로 전달된다")
    @WithMockUser(roles = "ADMIN")
    void reorderMenus_rootParentNull() throws Exception {
        given(menuService.reorderMenus(any())).willReturn(MenuOrderResponse.builder().menus(List.of()).build());

        mockMvc.perform(put("/admin/api/menus/order")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("null", "\"ALL\"", "[1,2]")))
                .andExpect(status().isOk());

        ArgumentCaptor<MenuOrderRequest> captor = ArgumentCaptor.forClass(MenuOrderRequest.class);
        verify(menuService).reorderMenus(captor.capture());
        assertNull(captor.getValue().getUpMenuNo());
        assertEquals(MenuOrderScope.ALL, captor.getValue().getScope());
    }

    @Test
    @DisplayName("빈 목록·null 원소·scope 누락은 400 VALIDATION_ERROR이고 서비스를 호출하지 않는다")
    @WithMockUser(roles = "ADMIN")
    void reorderMenus_validationFail() throws Exception {
        for (String body : List.of(
                orderBody("null", "\"ALL\"", "[]"),          // 빈 목록
                orderBody("null", "\"ALL\"", "[1,null]"),    // null 원소
                "{\"upMenuNo\":null,\"menuNos\":[1,2]}"      // scope 누락
        )) {
            mockMvc.perform(put("/admin/api/menus/order")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        verifyNoInteractions(menuService);
    }

    @Test
    @DisplayName("1000개를 넘는 목록은 400 VALIDATION_ERROR, 1000개는 허용")
    @WithMockUser(roles = "ADMIN")
    void reorderMenus_sizeLimit() throws Exception {
        given(menuService.reorderMenus(any())).willReturn(MenuOrderResponse.builder().menus(List.of()).build());
        String thousand = IntStream.rangeClosed(1, 1000)
                .mapToObj(String::valueOf).collect(Collectors.joining(",", "[", "]"));
        String thousandOne = IntStream.rangeClosed(1, 1001)
                .mapToObj(String::valueOf).collect(Collectors.joining(",", "[", "]"));

        mockMvc.perform(put("/admin/api/menus/order").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("null", "\"ALL\"", thousand)))
                .andExpect(status().isOk());
        mockMvc.perform(put("/admin/api/menus/order").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("null", "\"ALL\"", thousandOne)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("알 수 없는 scope 값은 400이고 서비스를 호출하지 않는다")
    @WithMockUser(roles = "ADMIN")
    void reorderMenus_unknownScope_400() throws Exception {
        mockMvc.perform(put("/admin/api/menus/order")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("null", "\"SOME\"", "[1]")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(menuService);
    }

    @Test
    @DisplayName("형제 구성이 달라졌으면 409로 응답한다")
    @WithMockUser(roles = "ADMIN")
    void reorderMenus_conflict() throws Exception {
        given(menuService.reorderMenus(any())).willThrow(new ConflictException("형제 메뉴 구성이 변경되었습니다."));

        mockMvc.perform(put("/admin/api/menus/order")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("null", "\"ALL\"", "[1,2]")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("부모 메뉴가 없으면 404로 응답한다")
    @WithMockUser(roles = "ADMIN")
    void reorderMenus_parentNotFound() throws Exception {
        given(menuService.reorderMenus(any())).willThrow(new ResourceNotFoundException("부모 메뉴를 찾을 수 없습니다."));

        mockMvc.perform(put("/admin/api/menus/order")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("99", "\"ALL\"", "[1]")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("인증 없이 순서 재조정 시 401")
    void reorderMenus_unauthenticated() throws Exception {
        mockMvc.perform(put("/admin/api/menus/order")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("null", "\"ALL\"", "[1]")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("MANAGER·USER는 순서 재조정 시 403(ADMIN 전용)")
    void reorderMenus_nonAdminForbidden() throws Exception {
        for (String role : List.of("MANAGER", "USER")) {
            mockMvc.perform(put("/admin/api/menus/order")
                            .with(user("u").roles(role))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderBody("null", "\"ALL\"", "[1]")))
                    .andExpect(status().isForbidden());
        }

        verifyNoInteractions(menuService);
    }

    @Test
    @DisplayName("CSRF 토큰 없는 PUT은 403이고 서비스를 호출하지 않는다")
    @WithMockUser(roles = "ADMIN")
    void reorderMenus_withoutCsrf_403() throws Exception {
        mockMvc.perform(put("/admin/api/menus/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("null", "\"ALL\"", "[1]")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(menuService);
    }

    @Test
    @DisplayName("PUT /order는 /{id} 경로 변수에 흡수되지 않고, PATCH /order는 id 타입 불일치 400(재조정 아님)")
    @WithMockUser(roles = "ADMIN")
    void reorderMenus_pathDoesNotCollideWithIdRoutes() throws Exception {
        given(menuService.reorderMenus(any())).willReturn(MenuOrderResponse.builder().menus(List.of()).build());

        mockMvc.perform(put("/admin/api/menus/order").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("null", "\"ALL\"", "[1]")))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/admin/api/menus/order").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ===================== moveMenu (PATCH /{id}/parent) =====================

    private MenuMoveResponse moveResponse(Long menuNo, Long upMenuNo, int ord, String... warnings) {
        return MenuMoveResponse.builder().menuNo(menuNo).upMenuNo(upMenuNo).ord(ord).warnings(List.of(warnings)).build();
    }

    @Test
    @DisplayName("부모 이동 성공: 정수 upMenuNo가 서비스로 전달되고 warnings가 응답된다")
    @WithMockUser(roles = "ADMIN")
    void moveMenu_success() throws Exception {
        given(menuService.moveMenu(eq(11L), any())).willReturn(
                moveResponse(11L, 20L, 5, "상위 메뉴가 관리자 전용이라 MANAGER에게는 이 메뉴가 사이드바에 표시되지 않습니다."));

        mockMvc.perform(patch("/admin/api/menus/11/parent")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upMenuNo\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menuNo").value(11))
                .andExpect(jsonPath("$.upMenuNo").value(20))
                .andExpect(jsonPath("$.ord").value(5))
                .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("MANAGER")));

        ArgumentCaptor<MenuMoveRequest> captor = ArgumentCaptor.forClass(MenuMoveRequest.class);
        verify(menuService).moveMenu(eq(11L), captor.capture());
        assertEquals(20L, captor.getValue().resolvedUpMenuNo());
    }

    @Test
    @DisplayName("명시적 JSON null은 최상위 승격으로 서비스에 전달되고, 응답에는 upMenuNo 키가 존재하며 값이 null이다(전역 non_null 무시)")
    @WithMockUser(roles = "ADMIN")
    void moveMenu_explicitNull_promotesAndResponseKeepsNullKey() throws Exception {
        given(menuService.moveMenu(eq(11L), any())).willReturn(moveResponse(11L, null, 5));

        String body = mockMvc.perform(patch("/admin/api/menus/11/parent")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upMenuNo\":null}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        ArgumentCaptor<MenuMoveRequest> captor = ArgumentCaptor.forClass(MenuMoveRequest.class);
        verify(menuService).moveMenu(eq(11L), captor.capture());
        assertNull(captor.getValue().resolvedUpMenuNo());

        JsonNode json = objectMapper.readTree(body);
        assertTrue(json.has("upMenuNo"), "최상위 결과에서도 upMenuNo 키가 생략되면 안 된다: " + body);
        assertTrue(json.get("upMenuNo").isNull());
        assertTrue(json.get("warnings").isArray());
        assertEquals(0, json.get("warnings").size());
    }

    @Test
    @DisplayName("필드 없음·문자열·소수·불리언·배열·객체·범위 초과 정수는 400 VALIDATION_ERROR이고 서비스를 호출하지 않는다(실수 입력이 승격·이동이 되면 안 됨)")
    @WithMockUser(roles = "ADMIN")
    void moveMenu_invalidUpMenuNo_400_neverCallsService() throws Exception {
        for (String body : List.of(
                "{}",                                        // 필드 없음 — 승격으로 오인 금지
                "{\"upMenuNo\":\"\"}",                       // Jackson 기본 변환이 null로 바꾸는 값
                "{\"upMenuNo\":\" \"}",
                "{\"upMenuNo\":\"null\"}",
                "{\"upMenuNo\":\"10\"}",                     // 숫자 문자열
                "{\"upMenuNo\":10.9}",                       // 기본 변환이 10으로 자르는 값
                "{\"upMenuNo\":10.0}",
                "{\"upMenuNo\":true}",
                "{\"upMenuNo\":[]}",
                "{\"upMenuNo\":{}}",
                "{\"upMenuNo\":99999999999999999999}"        // long 범위 초과
        )) {
            mockMvc.perform(patch("/admin/api/menus/11/parent")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        verifyNoInteractions(menuService);
    }

    @Test
    @DisplayName("이동 불가는 400, 대상·부모 없음은 404, 동시 변경 충돌은 409로 응답한다")
    @WithMockUser(roles = "ADMIN")
    void moveMenu_serviceErrors_mapToStatusCodes() throws Exception {
        given(menuService.moveMenu(eq(1L), any())).willThrow(new InvalidRequestException("하위 메뉴가 있는 메뉴는 이동할 수 없습니다."));
        given(menuService.moveMenu(eq(2L), any())).willThrow(new ResourceNotFoundException("메뉴를 찾을 수 없습니다."));
        given(menuService.moveMenu(eq(3L), any())).willThrow(
                new org.springframework.dao.CannotAcquireLockException("deadlock"));

        mockMvc.perform(patch("/admin/api/menus/1/parent").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upMenuNo\":20}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(patch("/admin/api/menus/2/parent").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upMenuNo\":20}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/admin/api/menus/3/parent").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upMenuNo\":20}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
    }

    @Test
    @DisplayName("인증 없이 부모 이동 시 401")
    void moveMenu_unauthenticated_401() throws Exception {
        mockMvc.perform(patch("/admin/api/menus/11/parent")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upMenuNo\":20}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("MANAGER·USER는 부모 이동 시 403(ADMIN 전용)")
    void moveMenu_nonAdminForbidden() throws Exception {
        for (String role : List.of("MANAGER", "USER")) {
            mockMvc.perform(patch("/admin/api/menus/11/parent")
                            .with(user("u").roles(role))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"upMenuNo\":20}"))
                    .andExpect(status().isForbidden());
        }

        verifyNoInteractions(menuService);
    }

    @Test
    @DisplayName("CSRF 토큰 없는 PATCH는 403이고 서비스를 호출하지 않는다")
    @WithMockUser(roles = "ADMIN")
    void moveMenu_withoutCsrf_403() throws Exception {
        mockMvc.perform(patch("/admin/api/menus/11/parent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upMenuNo\":20}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(menuService);
    }

    @Test
    @DisplayName("id가 숫자가 아니면 400 INVALID_REQUEST, PATCH /{id}와 PATCH /{id}/parent는 서로 다른 핸들러로 간다")
    @WithMockUser(roles = "ADMIN")
    void moveMenu_pathVariableAndRouteSeparation() throws Exception {
        mockMvc.perform(patch("/admin/api/menus/abc/parent").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upMenuNo\":20}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        given(menuService.updateMenu(eq(11L), any())).willReturn(menuResponse());
        mockMvc.perform(patch("/admin/api/menus/11").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuName\":\"이름\"}"))
                .andExpect(status().isOk());
        verify(menuService).updateMenu(eq(11L), any());
        verify(menuService, org.mockito.Mockito.never()).moveMenu(anyLong(), any());
    }
}
