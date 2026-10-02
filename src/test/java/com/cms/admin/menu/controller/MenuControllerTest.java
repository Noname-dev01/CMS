package com.cms.admin.menu.controller;

import com.cms.admin.menu.MenuAccessRole;
import com.cms.admin.menu.dto.request.MenuCreateRequest;
import com.cms.admin.menu.dto.request.MenuStructureRequest;
import com.cms.admin.menu.dto.request.MenuUpdateRequest;
import com.cms.admin.menu.dto.response.MenuDeleteResult;
import com.cms.admin.menu.dto.response.MenuResponse;
import com.cms.admin.menu.dto.response.MenuStructureResponse;
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

    // ===================== deleteMenu (영구삭제) =====================

    @Test
    @DisplayName("메뉴 영구삭제 성공 (204 No Content)")
    @WithMockUser(roles = "ADMIN")
    void deleteMenu_success() throws Exception {
        given(menuService.deleteMenu(1L)).willReturn(new MenuDeleteResult(1L, "메뉴", "/menu"));

        mockMvc.perform(delete("/admin/api/menus/1").with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("활성 메뉴이거나 하위 메뉴가 있어 영구삭제할 수 없으면 409 RESOURCE_CONFLICT")
    @WithMockUser(roles = "ADMIN")
    void deleteMenu_conflict() throws Exception {
        given(menuService.deleteMenu(1L)).willThrow(new ConflictException("활성 메뉴는 영구삭제할 수 없습니다. 먼저 비활성화해주세요."));

        mockMvc.perform(delete("/admin/api/menus/1").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
    }

    @Test
    @DisplayName("존재하지 않는 메뉴 영구삭제 시 404")
    @WithMockUser(roles = "ADMIN")
    void deleteMenu_notFound() throws Exception {
        given(menuService.deleteMenu(99L)).willThrow(new ResourceNotFoundException("메뉴를 찾을 수 없습니다."));

        mockMvc.perform(delete("/admin/api/menus/99").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("인증 없이 메뉴 영구삭제 시 401")
    void deleteMenu_unauthenticated() throws Exception {
        mockMvc.perform(delete("/admin/api/menus/1").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("USER는 메뉴 영구삭제 시 403")
    @WithMockUser(roles = "USER")
    void deleteMenu_userForbidden() throws Exception {
        mockMvc.perform(delete("/admin/api/menus/1").with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(menuService);
    }

    @Test
    @DisplayName("생성·수정 요청 본문에 낡은 ord가 있어도 무시되고 에러가 아니다(순서는 구조 반영 API로만 바뀐다)")
    @WithMockUser(roles = "ADMIN")
    void createAndUpdate_ignoreLegacyOrdField() throws Exception {
        given(menuService.createMenu(any())).willReturn(menuResponse());
        given(menuService.updateMenu(eq(1L), any())).willReturn(menuResponse());

        mockMvc.perform(post("/admin/api/menus").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuName\":\"메뉴\",\"ord\":999}"))
                .andExpect(status().isCreated());
        mockMvc.perform(patch("/admin/api/menus/1").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuName\":\"메뉴\",\"ord\":999}"))
                .andExpect(status().isOk());
    }

    // ===================== applyStructure (PUT /structure) =====================

    private static String structureItem(String menuNo, String baseUp, String baseOrd, String up) {
        StringBuilder sb = new StringBuilder("{");
        if (menuNo != null) sb.append("\"menuNo\":").append(menuNo).append(",");
        if (baseUp != null) sb.append("\"baseUpMenuNo\":").append(baseUp).append(",");
        if (baseOrd != null) sb.append("\"baseOrd\":").append(baseOrd).append(",");
        if (up != null) sb.append("\"upMenuNo\":").append(up).append(",");
        if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        return sb.append("}").toString();
    }

    private static String structureBody(String... items) {
        return "{\"menus\":[" + String.join(",", items) + "]}";
    }

    @Test
    @DisplayName("구조 반영 성공: 최상위(null)·ord null이 명시적으로 전달되고 changed가 응답된다")
    @WithMockUser(roles = "ADMIN")
    void applyStructure_success() throws Exception {
        given(menuService.applyStructure(any())).willReturn(MenuStructureResponse.builder().changed(2).build());

        mockMvc.perform(put("/admin/api/menus/structure")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(structureBody(
                                structureItem("1", "null", "0", "null"),
                                structureItem("5", "3", "null", "1"))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.changed").value(2));

        ArgumentCaptor<MenuStructureRequest> captor = ArgumentCaptor.forClass(MenuStructureRequest.class);
        verify(menuService).applyStructure(captor.capture());
        List<MenuStructureRequest.Item> items = captor.getValue().getMenus();
        assertEquals(2, items.size());
        assertNull(items.get(0).resolvedBaseUpMenuNo());
        assertEquals(0, items.get(0).resolvedBaseOrd());
        assertNull(items.get(0).resolvedUpMenuNo());
        assertEquals(3L, items.get(1).resolvedBaseUpMenuNo());
        assertNull(items.get(1).resolvedBaseOrd());
        assertEquals(1L, items.get(1).resolvedUpMenuNo());
    }

    @Test
    @DisplayName("빈 목록·menus 누락·필수 필드 누락은 400 VALIDATION_ERROR이고 서비스를 호출하지 않는다")
    @WithMockUser(roles = "ADMIN")
    void applyStructure_validationFail() throws Exception {
        for (String body : List.of(
                "{\"menus\":[]}",
                "{}",
                "{\"menus\":[null]}",
                structureBody(structureItem(null, "null", "0", "null")),
                structureBody(structureItem("1", null, "0", "null")),
                structureBody(structureItem("1", "null", null, "null")),
                structureBody(structureItem("1", "null", "0", null)))) {
            mockMvc.perform(put("/admin/api/menus/structure")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        verifyNoInteractions(menuService);
    }

    @Test
    @DisplayName("정수·null이 아닌 토큰(문자열·소수·불리언)은 400이고 최상위나 다른 부모로 둔갑하지 않는다")
    @WithMockUser(roles = "ADMIN")
    void applyStructure_badTokens_400() throws Exception {
        for (String bad : List.of("\"\"", "\"null\"", "\"10\"", "10.9", "10.0", "true", "[]", "{}")) {
            mockMvc.perform(put("/admin/api/menus/structure")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(structureBody(structureItem("1", "null", "0", bad))))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(put("/admin/api/menus/structure")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(structureBody(structureItem("1", bad, "0", "null"))))
                    .andExpect(status().isBadRequest());
        }
        // menuNo도 같은 규칙: 소수가 기존 메뉴 번호로 잘리거나 문자열이 숫자로 변환되면 엉뚱한 메뉴가 옮겨진다
        for (String bad : List.of("1.9", "\"1\"", "\"\"", "true", "null", "[]", "{}")) {
            mockMvc.perform(put("/admin/api/menus/structure")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(structureBody(structureItem(bad, "null", "0", "null"))))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(put("/admin/api/menus/structure")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(structureBody(structureItem("1", "null", "99999999999", "null"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(menuService);
    }

    @Test
    @DisplayName("구조 위반은 400, 낡은 초안은 409로 응답한다")
    @WithMockUser(roles = "ADMIN")
    void applyStructure_serviceErrors() throws Exception {
        given(menuService.applyStructure(any()))
                .willThrow(new InvalidRequestException("메뉴 구조에 순환이 있습니다."))
                .willThrow(new ConflictException("메뉴 구조가 변경되었습니다."));
        String body = structureBody(structureItem("1", "null", "0", "null"));

        mockMvc.perform(put("/admin/api/menus/structure").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/admin/api/menus/structure").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("인증 없이 구조 반영 시 401")
    void applyStructure_unauthenticated() throws Exception {
        mockMvc.perform(put("/admin/api/menus/structure")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(structureBody(structureItem("1", "null", "0", "null"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("MANAGER·USER는 구조 반영 시 403(ADMIN 전용)")
    void applyStructure_nonAdminForbidden() throws Exception {
        for (String role : List.of("MANAGER", "USER")) {
            mockMvc.perform(put("/admin/api/menus/structure")
                            .with(user("u").roles(role))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(structureBody(structureItem("1", "null", "0", "null"))))
                    .andExpect(status().isForbidden());
        }

        verifyNoInteractions(menuService);
    }

    @Test
    @DisplayName("CSRF 토큰 없는 구조 반영 PUT은 403이고 서비스를 호출하지 않는다")
    @WithMockUser(roles = "ADMIN")
    void applyStructure_withoutCsrf_403() throws Exception {
        mockMvc.perform(put("/admin/api/menus/structure")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(structureBody(structureItem("1", "null", "0", "null"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(menuService);
    }

}
