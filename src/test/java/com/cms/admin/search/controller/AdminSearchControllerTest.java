package com.cms.admin.search.controller;

import com.cms.admin.menu.service.MenuService;
import com.cms.admin.search.dto.AdminSearchResponse;
import com.cms.admin.search.dto.AdminSearchResponse.MemberItem;
import com.cms.admin.search.dto.AdminSearchResponse.NoticeItem;
import com.cms.admin.search.dto.AdminSearchResponse.Section;
import com.cms.admin.search.service.AdminSearchService;
import com.cms.common.api.GlobalApiExceptionHandler;
import com.cms.config.MethodSecurityTestConfig;
import com.cms.config.auth.AdminSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminSearchController.class)
@Import({
        AdminSearchControllerTest.MockConfig.class,
        MethodSecurityTestConfig.class,
        GlobalApiExceptionHandler.class
})
class AdminSearchControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AdminSearchService adminSearchService;

    @Autowired
    AdminSecurityService adminSecurityService;

    @BeforeEach
    void setUp() {
        reset(adminSearchService, adminSecurityService);
        given(adminSecurityService.getCurrentAdminName()).willReturn("관리자");
        given(adminSecurityService.getCurrentAdminProfileImageUrl()).willReturn(null);
    }

    @TestConfiguration
    static class MockConfig {
        @Bean
        public AdminSearchService adminSearchService() {
            return Mockito.mock(AdminSearchService.class);
        }

        @Bean
        public AdminSecurityService adminSecurityService() {
            return Mockito.mock(AdminSecurityService.class);
        }

        @Bean
        public MenuService menuService() {
            return Mockito.mock(MenuService.class);
        }
    }

    @Test
    @DisplayName("ADMIN은 모든 섹션이 담긴 검색 결과를 받는다 — 관리자 항목에 이메일 키가 없다")
    @WithMockUser(roles = "ADMIN")
    void admin_getsAllSections() throws Exception {
        AdminSearchResponse response = AdminSearchResponse.builder()
                .keyword("공지")
                .notices(new Section<>(1, List.of(new NoticeItem(31L, "공지 제목", true, LocalDateTime.of(2026, 10, 1, 10, 0)))))
                .members(new Section<>(1, List.of(new MemberItem(9L, "mgr01", "김관리", "ROLE_MANAGER", "ACTIVE"))))
                .build();
        given(adminSearchService.search(eq("공지"), any())).willReturn(response);

        mockMvc.perform(get("/admin/api/search-results").param("keyword", "공지"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.keyword").value("공지"))
                .andExpect(jsonPath("$.notices.total").value(1))
                .andExpect(jsonPath("$.notices.items[0].id").value(31))
                .andExpect(jsonPath("$.members.items[0].userId").value("mgr01"))
                .andExpect(jsonPath("$.members.items[0].email").doesNotExist());
    }

    @Test
    @DisplayName("권한이 없는 섹션은 빈 배열이 아니라 응답 키 자체가 없다")
    @WithMockUser(roles = "MANAGER")
    void manager_omittedSectionsHaveNoKey() throws Exception {
        given(adminSearchService.search(eq("공지"), any()))
                .willReturn(AdminSearchResponse.builder().keyword("공지").menus(new Section<>(0, List.of())).build());

        mockMvc.perform(get("/admin/api/search-results").param("keyword", "공지"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus.total").value(0))
                .andExpect(jsonPath("$.notices").doesNotExist())
                .andExpect(jsonPath("$.members").doesNotExist());
    }

    @Test
    @DisplayName("ROLE_USER는 403 JSON이고 서비스를 호출하지 않는다")
    @WithMockUser(roles = "USER")
    void user_forbidden() throws Exception {
        mockMvc.perform(get("/admin/api/search-results").param("keyword", "공지"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        verifyNoInteractions(adminSearchService);
    }

    @Test
    @DisplayName("검색어가 100자를 넘으면 400 VALIDATION_ERROR이고 서비스를 호출하지 않는다")
    @WithMockUser(roles = "ADMIN")
    void tooLongKeyword_returns400() throws Exception {
        mockMvc.perform(get("/admin/api/search-results").param("keyword", "가".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(adminSearchService);
    }

    @Test
    @DisplayName("검색어가 정확히 100자면 통과한다")
    @WithMockUser(roles = "ADMIN")
    void maxLengthKeyword_ok() throws Exception {
        String keyword = "가".repeat(100);
        given(adminSearchService.search(eq(keyword), any())).willReturn(AdminSearchResponse.builder().keyword(keyword).build());

        mockMvc.perform(get("/admin/api/search-results").param("keyword", keyword))
                .andExpect(status().isOk());

        verify(adminSearchService).search(eq(keyword), any());
    }
}
