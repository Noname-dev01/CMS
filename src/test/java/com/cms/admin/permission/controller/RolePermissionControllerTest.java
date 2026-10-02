package com.cms.admin.permission.controller;

import com.cms.admin.member.domain.Role;
import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.FeatureKind;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.dto.request.RolePermissionUpdateRequest;
import com.cms.admin.permission.dto.response.RolePermissionMatrixResponse;
import com.cms.admin.permission.service.RolePermissionService;
import com.cms.admin.permission.service.RolePermissionUpdateResult;
import com.cms.common.api.GlobalApiExceptionHandler;
import com.cms.common.exception.ConflictException;
import com.cms.config.MethodSecurityTestConfig;
import com.cms.config.auth.AdminSecurityService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RolePermissionController.class)
@Import({
        RolePermissionControllerTest.MockConfig.class,
        MethodSecurityTestConfig.class,
        GlobalApiExceptionHandler.class
})
class RolePermissionControllerTest {

    private static final String URL = "/admin/api/roles/ROLE_MANAGER/permissions";
    private static final String VALID_BODY = "{\"version\":3,\"grants\":[{\"feature\":\"NOTICE\",\"action\":\"READ\"}]}";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RolePermissionService rolePermissionService;

    @Autowired
    AdminSecurityService adminSecurityService;

    @BeforeEach
    void setUp() {
        reset(rolePermissionService, adminSecurityService);
    }

    @TestConfiguration
    static class MockConfig {
        @Bean
        public RolePermissionService rolePermissionService() {
            return Mockito.mock(RolePermissionService.class);
        }

        @Bean
        public com.cms.admin.menu.service.MenuService menuService() {
            return Mockito.mock(com.cms.admin.menu.service.MenuService.class);
        }

        // AdminSidebarAdvice(@ControllerAdvice)가 슬라이스 컨텍스트에 포함되므로 의존 빈이 필요하다.
        @Bean
        public AdminSecurityService adminSecurityService() {
            return Mockito.mock(AdminSecurityService.class);
        }
    }

    private RolePermissionMatrixResponse matrix() {
        return new RolePermissionMatrixResponse("ROLE_MANAGER", 3L,
                List.of(new RolePermissionMatrixResponse.ActionColumn(PermissionAction.READ, "조회")),
                List.of(new RolePermissionMatrixResponse.FeatureRow(AdminFeature.NOTICE, "공지사항", FeatureKind.DELEGABLE,
                        List.of(PermissionAction.READ), List.of(PermissionAction.READ))));
    }

    // ── 인가 ──────────────────────────────────────────────

    @Test
    @DisplayName("MANAGER는 GET·PUT 모두 403 JSON ACCESS_DENIED이고 서비스는 호출되지 않는다")
    @WithMockUser(roles = "MANAGER")
    void manager_forbidden() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(put(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(rolePermissionService);
    }

    @Test
    @DisplayName("익명은 401")
    void anonymous_unauthorized() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(rolePermissionService);
    }

    @Test
    @DisplayName("CSRF 토큰 없는 PUT은 403이고 서비스는 호출되지 않는다")
    @WithMockUser(roles = "ADMIN")
    void put_withoutCsrf_forbidden() throws Exception {
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(rolePermissionService);
    }

    // ── 정상 ──────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN 조회: 200과 매트릭스 JSON 형태")
    @WithMockUser(roles = "ADMIN")
    void get_ok() throws Exception {
        given(rolePermissionService.getMatrix(Role.ROLE_MANAGER)).willReturn(matrix());

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ROLE_MANAGER"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.actions[0].action").value("READ"))
                .andExpect(jsonPath("$.actions[0].label").value("조회"))
                .andExpect(jsonPath("$.features[0].feature").value("NOTICE"))
                .andExpect(jsonPath("$.features[0].kind").value("DELEGABLE"))
                .andExpect(jsonPath("$.features[0].supportedActions[0]").value("READ"))
                .andExpect(jsonPath("$.features[0].grantedActions[0]").value("READ"));
    }

    @Test
    @DisplayName("ADMIN 저장: 200과 변경 후 매트릭스, 서비스에 역할·요청이 전달된다")
    @WithMockUser(roles = "ADMIN")
    void put_ok() throws Exception {
        given(rolePermissionService.replace(eq(Role.ROLE_MANAGER), any(RolePermissionUpdateRequest.class)))
                .willReturn(new RolePermissionUpdateResult(matrix(), "라벨"));

        mockMvc.perform(put(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.auditLabel").doesNotExist());

        verify(rolePermissionService).replace(eq(Role.ROLE_MANAGER), any(RolePermissionUpdateRequest.class));
    }

    // ── 입력 검증 ──────────────────────────────────────────

    @Test
    @DisplayName("version·grants·원소·feature·action 누락은 400 VALIDATION_ERROR")
    @WithMockUser(roles = "ADMIN")
    void put_missingFields_validationError() throws Exception {
        List<String> bodies = List.of(
                "{\"grants\":[]}",
                "{\"version\":3}",
                "{\"version\":3,\"grants\":[null]}",
                "{\"version\":3,\"grants\":[{\"action\":\"READ\"}]}",
                "{\"version\":3,\"grants\":[{\"feature\":\"NOTICE\"}]}");
        for (String body : bodies) {
            mockMvc.perform(put(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(rolePermissionService);
    }

    @Test
    @DisplayName("미정의 feature·action 값과 깨진 JSON은 400 JSON_PARSE_ERROR")
    @WithMockUser(roles = "ADMIN")
    void put_unknownEnumOrBrokenJson_parseError() throws Exception {
        List<String> bodies = List.of(
                "{\"version\":3,\"grants\":[{\"feature\":\"NOPE\",\"action\":\"READ\"}]}",
                "{\"version\":3,\"grants\":[{\"feature\":\"NOTICE\",\"action\":\"NOPE\"}]}",
                "{\"version\":");
        for (String body : bodies) {
            mockMvc.perform(put(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("JSON_PARSE_ERROR"));
        }
        verifyNoInteractions(rolePermissionService);
    }

    @Test
    @DisplayName("enum에 없는 {role}은 400 INVALID_REQUEST이고 message에 입력 원문이 반영되지 않는다")
    @WithMockUser(roles = "ADMIN")
    void unknownRole_invalidRequest_withoutEcho() throws Exception {
        mockMvc.perform(get("/admin/api/roles/ROLE_X/permissions"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ROLE_X"))))
                // 오류 응답의 path에는 요청 URI가 들어가는 기존 계약이다
                .andExpect(jsonPath("$.path").value("/admin/api/roles/ROLE_X/permissions"));

        verifyNoInteractions(rolePermissionService);
    }

    @Test
    @DisplayName("서비스의 ConflictException은 409 RESOURCE_CONFLICT")
    @WithMockUser(roles = "ADMIN")
    void put_conflict() throws Exception {
        given(rolePermissionService.replace(any(), any())).willThrow(new ConflictException("충돌"));

        mockMvc.perform(put(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
    }
}
