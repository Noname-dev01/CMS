package com.cms.admin.notification.controller;

import com.cms.admin.menu.service.MenuService;
import com.cms.admin.notification.domain.NotificationType;
import com.cms.admin.notification.dto.NotificationPageResponse;
import com.cms.admin.notification.dto.NotificationReadResponse;
import com.cms.admin.notification.dto.NotificationResponse;
import com.cms.admin.notification.service.NotificationService;
import com.cms.common.api.GlobalApiExceptionHandler;
import com.cms.common.exception.ResourceNotFoundException;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = NotificationController.class)
@Import({
        NotificationControllerTest.MockConfig.class,
        MethodSecurityTestConfig.class,
        GlobalApiExceptionHandler.class
})
class NotificationControllerTest {

    private static final long ME = 7L;

    @Autowired MockMvc mockMvc;
    @Autowired NotificationService notificationService;
    @Autowired AdminSecurityService adminSecurityService;

    @BeforeEach
    void setUp() {
        reset(notificationService, adminSecurityService);
        given(adminSecurityService.getCurrentAdminId()).willReturn(ME);
        given(adminSecurityService.getCurrentAdminName()).willReturn("관리자");
        given(adminSecurityService.getCurrentAdminProfileImageUrl()).willReturn(null);
    }

    @TestConfiguration
    static class MockConfig {
        @Bean NotificationService notificationService() { return Mockito.mock(NotificationService.class); }
        @Bean AdminSecurityService adminSecurityService() { return Mockito.mock(AdminSecurityService.class); }
        @Bean MenuService menuService() { return Mockito.mock(MenuService.class); }
    }

    private static NotificationResponse item(long id) {
        return new NotificationResponse(id, NotificationType.PERMISSION, "권한이 변경되었습니다", null, false, LocalDateTime.of(2026, 10, 5, 12, 0));
    }

    @Test
    @DisplayName("ADMIN은 목록을 조회하고 본인 ID·ADMIN 여부·커서·size가 서비스에 전달된다")
    @WithMockUser(roles = "ADMIN")
    void admin_list_passesPrincipalAndFlags() throws Exception {
        given(notificationService.list(ME, true, 55L, 10))
                .willReturn(new NotificationPageResponse(List.of(item(54)), 3, true));

        mockMvc.perform(get("/admin/api/members/me/notifications").param("beforeId", "55").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content[0].id").value(54))
                .andExpect(jsonPath("$.content[0].read").value(false))
                .andExpect(jsonPath("$.content[0].memberId").doesNotExist())
                .andExpect(jsonPath("$.unreadCount").value(3))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    @DisplayName("MANAGER는 includeAdminOnly=false로 조회한다(D11)")
    @WithMockUser(roles = "MANAGER")
    void manager_list_isNotAdmin() throws Exception {
        given(notificationService.list(ME, false, null, null))
                .willReturn(new NotificationPageResponse(List.of(), 0, false));

        mockMvc.perform(get("/admin/api/members/me/notifications")).andExpect(status().isOk());

        verify(notificationService).list(ME, false, null, null);
    }

    @Test
    @DisplayName("미읽음 수 조회")
    @WithMockUser(roles = "MANAGER")
    void unreadCount_ok() throws Exception {
        given(notificationService.unreadCount(ME, false)).willReturn(4L);

        mockMvc.perform(get("/admin/api/members/me/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(4));
    }

    @Test
    @DisplayName("단건 읽음은 {read:true}를 받아 처리하고 항목과 미읽음 수를 돌려준다")
    @WithMockUser(roles = "ADMIN")
    void markRead_ok() throws Exception {
        given(notificationService.markRead(ME, true, 5L)).willReturn(NotificationReadResponse.single(item(5), 1));

        mockMvc.perform(patch("/admin/api/members/me/notifications/5").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"read\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notification.id").value(5))
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.updated").doesNotExist());
    }

    @Test
    @DisplayName("전체 읽음은 갱신 건수와 미읽음 수를 돌려준다")
    @WithMockUser(roles = "ADMIN")
    void markAllRead_ok() throws Exception {
        given(notificationService.markAllRead(ME, true)).willReturn(NotificationReadResponse.all(3, 0));

        mockMvc.perform(patch("/admin/api/members/me/notifications").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"read\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(3))
                .andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.notification").doesNotExist());
    }

    @Test
    @DisplayName("read:false(읽음 취소)·read 누락은 400이고 서비스를 호출하지 않는다")
    @WithMockUser(roles = "ADMIN")
    void readFalseOrMissing_returns400() throws Exception {
        mockMvc.perform(patch("/admin/api/members/me/notifications/5").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"read\": false}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/admin/api/members/me/notifications").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("없는·남의 알림은 404(존재 숨김)")
    @WithMockUser(roles = "MANAGER")
    void markRead_notFound_returns404() throws Exception {
        given(notificationService.markRead(eq(ME), eq(false), eq(9L)))
                .willThrow(new ResourceNotFoundException("알림을 찾을 수 없습니다."));

        mockMvc.perform(patch("/admin/api/members/me/notifications/9").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"read\": true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CSRF 토큰이 없는 PATCH는 403이고 서비스를 호출하지 않는다")
    @WithMockUser(roles = "ADMIN")
    void patchWithoutCsrf_forbidden() throws Exception {
        mockMvc.perform(patch("/admin/api/members/me/notifications/5")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"read\": true}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("ROLE_USER는 403 JSON이고 서비스를 호출하지 않는다")
    @WithMockUser(roles = "USER")
    void user_forbidden() throws Exception {
        mockMvc.perform(get("/admin/api/members/me/notifications"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        verifyNoInteractions(notificationService);
    }
}
