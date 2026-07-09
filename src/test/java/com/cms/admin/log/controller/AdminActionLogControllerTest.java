package com.cms.admin.log.controller;

import com.cms.admin.log.domain.AdminActionResult;
import com.cms.admin.log.dto.request.AdminActionLogSearchRequest;
import com.cms.admin.log.dto.response.AdminActionLogPageResponse;
import com.cms.admin.log.service.AdminActionLogQueryService;
import com.cms.admin.menu.service.MenuService;
import com.cms.common.api.GlobalApiExceptionHandler;
import com.cms.config.MethodSecurityTestConfig;
import com.cms.config.auth.AdminSecurityService;
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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminActionLogController.class)
@Import({
        AdminActionLogControllerTest.MockConfig.class,
        MethodSecurityTestConfig.class,
        GlobalApiExceptionHandler.class
})
class AdminActionLogControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AdminActionLogQueryService adminActionLogQueryService;

    @Autowired
    AdminSecurityService adminSecurityService;

    @BeforeEach
    void setUp() {
        reset(adminActionLogQueryService);
        // AdminViewAdvice가 com.cms.admin 전체에 적용되므로 기본 스텁이 필요하다.
        given(adminSecurityService.getCurrentAdminName()).willReturn("관리자");
        given(adminSecurityService.getCurrentAdminProfileImageUrl()).willReturn(null);
    }

    @TestConfiguration
    static class MockConfig {
        @Bean
        public AdminActionLogQueryService adminActionLogQueryService() {
            return Mockito.mock(AdminActionLogQueryService.class);
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

    private AdminActionLogPageResponse emptyPageResponse() {
        return AdminActionLogPageResponse.builder()
                .content(List.of())
                .page(0)
                .size(20)
                .totalElements(0)
                .totalPages(0)
                .last(true)
                .build();
    }

    @Test
    @DisplayName("미인증이면 401")
    void 미인증이면_401() throws Exception {
        mockMvc.perform(get("/admin/api/logs")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ROLE_MANAGER면 403 ACCESS_DENIED")
    @WithMockUser(roles = "MANAGER")
    void ROLE_MANAGER면_403_ACCESS_DENIED() throws Exception {
        mockMvc.perform(get("/admin/api/logs")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("ROLE_ADMIN이면 200이고 서비스에 위임된다")
    @WithMockUser(roles = "ADMIN")
    void ROLE_ADMIN이면_200이고_서비스에_위임된다() throws Exception {
        given(adminActionLogQueryService.searchActionLogs(any(), any())).willReturn(emptyPageResponse());

        mockMvc.perform(get("/admin/api/logs")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        Mockito.verify(adminActionLogQueryService).searchActionLogs(any(), any());
    }

    @Test
    @DisplayName("검색 파라미터가 DTO에 바인딩되어 서비스로 전달된다")
    @WithMockUser(roles = "ADMIN")
    void 검색_파라미터가_DTO에_바인딩되어_서비스로_전달된다() throws Exception {
        given(adminActionLogQueryService.searchActionLogs(any(), any())).willReturn(emptyPageResponse());

        ArgumentCaptor<AdminActionLogSearchRequest> captor =
                ArgumentCaptor.forClass(AdminActionLogSearchRequest.class);

        mockMvc.perform(get("/admin/api/logs")
                        .param("actionUserId", "admin01")
                        .param("actionType", "ADMIN_CREATE")
                        .param("actionResult", "SUCCESS")
                        .param("from", "2026-01-01")
                        .param("to", "2026-03-01")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        Mockito.verify(adminActionLogQueryService).searchActionLogs(captor.capture(), any());
        AdminActionLogSearchRequest req = captor.getValue();
        assertThat(req.getActionUserId()).isEqualTo("admin01");
        assertThat(req.getActionType()).isEqualTo("ADMIN_CREATE");
        assertThat(req.getActionResult()).isEqualTo(AdminActionResult.SUCCESS);
        assertThat(req.getFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(req.getTo()).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    @DisplayName("기간 역전이면 400 VALIDATION_ERROR — 파생 필드명 prefix 없이 메시지만 노출")
    @WithMockUser(roles = "ADMIN")
    void 기간_역전이면_400_VALIDATION_ERROR() throws Exception {
        mockMvc.perform(get("/admin/api/logs")
                        .param("from", "2026-03-01")
                        .param("to", "2026-01-01")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("조회 시작일이 종료일보다 늦을 수 없습니다."));
    }

    @Test
    @DisplayName("기간이 3개월을 초과하면 400 VALIDATION_ERROR — 파생 필드명 prefix 없이 메시지만 노출")
    @WithMockUser(roles = "ADMIN")
    void 기간_3개월_초과면_400_VALIDATION_ERROR() throws Exception {
        // from=2026-01-01 기준 3개월 마지막 포함일은 2026-03-31, to=2026-04-01은 종료일 포함 조회상 3개월 초과
        mockMvc.perform(get("/admin/api/logs")
                        .param("from", "2026-01-01")
                        .param("to", "2026-04-01")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("조회 기간은 3개월을 초과할 수 없습니다."));
    }

    @Test
    @DisplayName("기간이 정확히 3개월이면 200 — 경계 통과")
    @WithMockUser(roles = "ADMIN")
    void 기간_정확히_3개월이면_200() throws Exception {
        given(adminActionLogQueryService.searchActionLogs(any(), any())).willReturn(emptyPageResponse());

        // from=2026-01-01, to=2026-03-31 → 종료일 포함 조회상 정확히 3개월(Jan+Feb+Mar)
        mockMvc.perform(get("/admin/api/logs")
                        .param("from", "2026-01-01")
                        .param("to", "2026-03-31")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("실제 선언 필드 검증 실패 시 '필드명: 메시지' prefix가 유지된다")
    @WithMockUser(roles = "ADMIN")
    void 선언_필드_검증_실패_시_prefix_유지() throws Exception {
        // actionUserId @Size(max=50) — 51자 입력 → 선언 필드이므로 "actionUserId: " prefix 유지
        String longUserId = "a".repeat(51);
        mockMvc.perform(get("/admin/api/logs")
                        .param("actionUserId", longUserId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message", startsWith("actionUserId: ")));
    }

    @Test
    @DisplayName("기간 미지정이면 200 — 기본값 주입은 서비스 책임")
    @WithMockUser(roles = "ADMIN")
    void 기간_미지정이면_200() throws Exception {
        given(adminActionLogQueryService.searchActionLogs(any(), any())).willReturn(emptyPageResponse());

        mockMvc.perform(get("/admin/api/logs")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
