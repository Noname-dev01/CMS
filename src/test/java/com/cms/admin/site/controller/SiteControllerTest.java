package com.cms.admin.site.controller;

import com.cms.admin.menu.service.MenuService;
import com.cms.admin.site.dto.request.SiteCreateRequest;
import com.cms.admin.site.dto.request.SiteUpdateRequest;
import com.cms.admin.site.dto.response.SiteDeleteResult;
import com.cms.admin.site.dto.response.SiteResponse;
import com.cms.admin.site.service.SiteService;
import com.cms.common.api.GlobalApiExceptionHandler;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 사이트 관리 API 슬라이스 시험 — ADMIN 전용, 검증, 상태 코드 계약. */
@WebMvcTest(controllers = SiteController.class)
@Import({
        SiteControllerTest.MockConfig.class,
        MethodSecurityTestConfig.class,
        GlobalApiExceptionHandler.class
})
class SiteControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    SiteService siteService;

    @Autowired
    AdminSecurityService adminSecurityService;

    @BeforeEach
    void setUp() {
        reset(siteService, adminSecurityService);
        given(adminSecurityService.getCurrentAdminName()).willReturn("관리자");
    }

    @TestConfiguration
    static class MockConfig {
        @Bean
        public SiteService siteService() {
            return Mockito.mock(SiteService.class);
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

    private SiteResponse response(long id, String key) {
        return SiteResponse.builder().id(id).siteKey(key).siteName("이름").siteType("PUBLIC").allowExternalLink(true)
                .useYn(true).seed(false).menuCount(0L).createDate(LocalDateTime.now()).updateDate(LocalDateTime.now()).build();
    }

    private String createJson(String key, String name) throws Exception {
        return objectMapper.writeValueAsString(SiteCreateRequest.builder().siteKey(key).siteName(name).allowExternalLink(true).build());
    }

    @Test
    @DisplayName("목록 조회 (ADMIN)")
    @WithMockUser(roles = "ADMIN")
    void list() throws Exception {
        given(siteService.getSites()).willReturn(List.of(response(1L, "admin"), response(2L, "main")));

        mockMvc.perform(get("/admin/api/sites"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].siteKey").value("admin"))
                .andExpect(jsonPath("$[1].siteKey").value("main"));
    }

    @Test
    @DisplayName("생성 성공: 201 + Location")
    @WithMockUser(roles = "ADMIN")
    void create_success() throws Exception {
        given(siteService.createSite(any())).willReturn(response(3L, "news"));

        mockMvc.perform(post("/admin/api/sites").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(createJson("news", "소식")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/admin/api/sites/3")))
                .andExpect(jsonPath("$.siteKey").value("news"));
    }

    @Test
    @DisplayName("생성 검증: 키 형식(대문자·밑줄·1자·31자·공백)·이름 누락은 400 VALIDATION_ERROR이고 서비스를 호출하지 않는다")
    @WithMockUser(roles = "ADMIN")
    void create_validation() throws Exception {
        for (String key : List.of("News", "news_1", "a", "x".repeat(31), "-ab", "a b", "")) {
            mockMvc.perform(post("/admin/api/sites").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(createJson(key, "소식")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        mockMvc.perform(post("/admin/api/sites").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(createJson("news", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(siteService);
    }

    @Test
    @DisplayName("생성: 예약어는 서비스의 400 INVALID_REQUEST, 중복 키는 409 RESOURCE_CONFLICT")
    @WithMockUser(roles = "ADMIN")
    void create_reservedAndDuplicate() throws Exception {
        given(siteService.createSite(any()))
                .willThrow(new InvalidRequestException("사용할 수 없는 사이트 키입니다."))
                .willThrow(new ConflictException("이미 사용 중인 사이트 키입니다."));

        mockMvc.perform(post("/admin/api/sites").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(createJson("faq", "예약")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(post("/admin/api/sites").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(createJson("main", "중복")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
    }

    @Test
    @DisplayName("수정: 키·종류 필드가 와도 무시된다(400 아님) — 키는 생성 후 불변")
    @WithMockUser(roles = "ADMIN")
    void update_ignoresKeyAndType() throws Exception {
        given(siteService.updateSite(eq(3L), any())).willReturn(response(3L, "news"));

        mockMvc.perform(patch("/admin/api/sites/3").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteName\":\"새 이름\",\"siteKey\":\"hacked\",\"siteType\":\"ADMIN\"}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<SiteUpdateRequest> captor = org.mockito.ArgumentCaptor.forClass(SiteUpdateRequest.class);
        verify(siteService).updateSite(eq(3L), captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("새 이름", captor.getValue().getSiteName());
    }

    @Test
    @DisplayName("수정·삭제의 서비스 오류 매핑: 404 / 409")
    @WithMockUser(roles = "ADMIN")
    void update_delete_errors() throws Exception {
        given(siteService.updateSite(eq(9L), any())).willThrow(new ResourceNotFoundException("사이트를 찾을 수 없습니다."));
        given(siteService.deleteSite(2L)).willThrow(new ConflictException("기본 사이트는 삭제할 수 없습니다."));

        mockMvc.perform(patch("/admin/api/sites/9").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"siteName\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(delete("/admin/api/sites/2").with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("삭제 성공 204")
    @WithMockUser(roles = "ADMIN")
    void delete_success() throws Exception {
        given(siteService.deleteSite(3L)).willReturn(new SiteDeleteResult(3L, "news"));

        mockMvc.perform(delete("/admin/api/sites/3").with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("MANAGER는 모든 사이트 API가 403이고 서비스를 호출하지 않는다 — 사이트 관리는 ADMIN 전용")
    @WithMockUser(roles = "MANAGER")
    void managerForbidden() throws Exception {
        mockMvc.perform(get("/admin/api/sites")).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/api/sites/1")).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/api/sites").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(createJson("news", "소식")))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/admin/api/sites/3").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/api/sites/3").with(csrf())).andExpect(status().isForbidden());

        verifyNoInteractions(siteService);
    }

    @Test
    @DisplayName("인증 없이 호출하면 401")
    void unauthenticated() throws Exception {
        mockMvc.perform(get("/admin/api/sites")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/api/sites").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(createJson("news", "소식")))
                .andExpect(status().isUnauthorized());
    }
}
