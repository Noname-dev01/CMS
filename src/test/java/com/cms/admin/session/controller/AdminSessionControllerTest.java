package com.cms.admin.session.controller;

import com.cms.admin.menu.service.MenuService;
import com.cms.admin.session.dto.response.SessionExpireResult;
import com.cms.admin.session.dto.response.SessionItemResponse;
import com.cms.admin.session.dto.response.SessionListResponse;
import com.cms.admin.session.dto.response.SessionMemberResponse;
import com.cms.admin.session.service.AdminSessionManageService;
import com.cms.common.api.GlobalApiExceptionHandler;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.config.MethodSecurityTestConfig;
import com.cms.config.auth.AdminSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 세션 관리 API 슬라이스 시험 — 인가(ADMIN 전용)·CSRF·입력 검증(500이 아닌 400)·상태 코드 매핑·현재 세션 ID 전달을 고정한다
 * (PLAN-session-management.md 쟁점 2·3·5, R1-5·R1-9·R1-10). URL 캐치올은 {@code SecurityConfigTest}/통합 시험이 본다.
 */
@WebMvcTest(controllers = AdminSessionController.class)
@Import({
        AdminSessionControllerTest.MockConfig.class,
        MethodSecurityTestConfig.class,
        GlobalApiExceptionHandler.class
})
class AdminSessionControllerTest {

    private static final String HANDLE = "0123456789abcdef0123456789abcdef";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AdminSessionManageService adminSessionManageService;

    @Autowired
    AdminSecurityService adminSecurityService;

    @BeforeEach
    void setUp() {
        reset(adminSessionManageService);
        given(adminSecurityService.getCurrentAdminName()).willReturn("관리자");
        given(adminSecurityService.getCurrentAdminProfileImageUrl()).willReturn(null);
    }

    @TestConfiguration
    static class MockConfig {
        @Bean
        public AdminSessionManageService adminSessionManageService() {
            return Mockito.mock(AdminSessionManageService.class);
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

    // ===================== 인가·CSRF =====================

    @Test
    @DisplayName("미인증이면 401")
    void unauthenticated_401() throws Exception {
        mockMvc.perform(get("/admin/api/sessions").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ROLE_MANAGER는 유효한 CSRF·정상 인자로도 세 엔드포인트 모두 403 ACCESS_DENIED이고 서비스는 호출되지 않는다")
    @WithMockUser(roles = "MANAGER")
    void manager_forbiddenOnAllEndpoints() throws Exception {
        mockMvc.perform(get("/admin/api/sessions").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(delete("/admin/api/sessions/{handle}", HANDLE).with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(delete("/admin/api/sessions").param("memberId", "5").with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(adminSessionManageService);
    }

    @Test
    @DisplayName("ADMIN이어도 CSRF 토큰이 없으면 두 DELETE 모두 403이고 서비스는 호출되지 않는다")
    @WithMockUser(roles = "ADMIN")
    void admin_withoutCsrf_forbidden() throws Exception {
        mockMvc.perform(delete("/admin/api/sessions/{handle}", HANDLE).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/api/sessions").param("memberId", "5").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminSessionManageService);
    }

    // ===================== 목록 =====================

    @Test
    @DisplayName("ADMIN 목록 200 — 응답은 핸들·회원 정보만 담고 서비스에 현재 세션 ID가 전달된다")
    @WithMockUser(roles = "ADMIN")
    void admin_list_200() throws Exception {
        MockHttpSession session = new MockHttpSession(null, "raw-session-id-for-test-9f3a");
        SessionListResponse body = new SessionListResponse(1, List.of(new SessionMemberResponse(
                1L, "admin", "관리자", "ROLE_ADMIN", 1, LocalDateTime.of(2026, 10, 10, 19, 0, 0),
                List.of(new SessionItemResponse(HANDLE, LocalDateTime.of(2026, 10, 10, 19, 0, 0), true)))));
        given(adminSessionManageService.getSessions(session.getId())).willReturn(body);

        mockMvc.perform(get("/admin/api/sessions").session(session).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSessions").value(1))
                .andExpect(jsonPath("$.members[0].memberId").value(1))
                .andExpect(jsonPath("$.members[0].role").value("ROLE_ADMIN"))
                .andExpect(jsonPath("$.members[0].sessions[0].handle").value(HANDLE))
                .andExpect(jsonPath("$.members[0].sessions[0].current").value(true))
                .andExpect(jsonPath("$.members[0].lastRequestAt").value("2026-10-10T19:00:00"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(session.getId()))));
    }

    // ===================== 단일 만료 =====================

    @Test
    @DisplayName("단일 만료 204 — 핸들과 요청 진입 시점의 세션 ID가 서비스에 전달된다")
    @WithMockUser(roles = "ADMIN")
    void expireOne_204() throws Exception {
        MockHttpSession session = new MockHttpSession(null, "raw-session-id-for-test-9f3a");
        given(adminSessionManageService.expireSession(HANDLE, session.getId())).willReturn(new SessionExpireResult(2L, 1, false));

        mockMvc.perform(delete("/admin/api/sessions/{handle}", HANDLE).session(session).with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(adminSessionManageService).expireSession(HANDLE, session.getId());
    }

    @Test
    @DisplayName("단일 만료: 형식이 틀린 핸들(대문자·짧음·길이 초과·비hex)은 서비스 호출 전에 400 INVALID_REQUEST — 입력값을 응답 메시지에 싣지 않는다")
    @WithMockUser(roles = "ADMIN")
    void expireOne_invalidHandle_400() throws Exception {
        for (String bad : new String[]{"0123456789ABCDEF0123456789ABCDEF", "abc", HANDLE + "0", "g123456789abcdef0123456789abcdef"}) {
            mockMvc.perform(delete("/admin/api/sessions/{handle}", bad).with(csrf()).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(bad))));
        }

        verifyNoInteractions(adminSessionManageService);
    }

    @Test
    @DisplayName("단일 만료: 서비스의 404는 RESOURCE_NOT_FOUND, 현재 세션 409는 RESOURCE_CONFLICT로 매핑된다")
    @WithMockUser(roles = "ADMIN")
    void expireOne_mapsServiceExceptions() throws Exception {
        given(adminSessionManageService.expireSession(eq(HANDLE), any())).willThrow(new ResourceNotFoundException("세션을 찾을 수 없습니다."));
        mockMvc.perform(delete("/admin/api/sessions/{handle}", HANDLE).with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        given(adminSessionManageService.expireSession(eq(HANDLE), any())).willThrow(new ConflictException("현재 사용 중인 세션은 만료할 수 없습니다."));
        mockMvc.perform(delete("/admin/api/sessions/{handle}", HANDLE).with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
    }

    // ===================== 회원 단위 만료 =====================

    @Test
    @DisplayName("회원 단위 만료 200 — memberId와 현재 세션 ID가 서비스에 전달되고 expiredCount를 돌려준다")
    @WithMockUser(roles = "ADMIN")
    void expireMember_200() throws Exception {
        MockHttpSession session = new MockHttpSession(null, "raw-session-id-for-test-9f3a");
        given(adminSessionManageService.expireMemberSessions(5L, session.getId())).willReturn(new SessionExpireResult(5L, 3, true));

        mockMvc.perform(delete("/admin/api/sessions").param("memberId", "5").session(session).with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiredCount").value(3));
    }

    @Test
    @DisplayName("회원 단위 만료: memberId 누락·빈 값·0·음수는 500이 아니라 400 INVALID_REQUEST, 문자열·범위 초과는 400이며 서비스는 호출되지 않는다")
    @WithMockUser(roles = "ADMIN")
    void expireMember_invalidMemberId_400() throws Exception {
        // 누락 — 전역 핸들러에 MissingServletRequestParameterException 처리가 없어 required=true였다면 500이 된다
        mockMvc.perform(delete("/admin/api/sessions").with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        for (String bad : new String[]{"0", "-1"}) {
            mockMvc.perform(delete("/admin/api/sessions").param("memberId", bad).with(csrf()).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        // 빈 값은 Long 변환에서 null로 바뀌어 누락과 같이 처리된다
        mockMvc.perform(delete("/admin/api/sessions").param("memberId", "").with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        // 문자열·Long 범위 초과 — 타입 변환 실패(기존 핸들러), 응답에 입력값을 싣지 않는다
        for (String bad : new String[]{"abc", "99999999999999999999"}) {
            mockMvc.perform(delete("/admin/api/sessions").param("memberId", bad).with(csrf()).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(bad))));
        }

        verify(adminSessionManageService, never()).expireMemberSessions(anyLong(), anyString());
        verifyNoInteractions(adminSessionManageService);
    }
}
