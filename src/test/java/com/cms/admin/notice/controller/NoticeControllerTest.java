package com.cms.admin.notice.controller;

import com.cms.admin.notice.dto.request.NoticeCreateRequest;
import com.cms.admin.notice.dto.request.NoticeUpdateRequest;
import com.cms.admin.notice.dto.response.NoticePageResponse;
import com.cms.admin.notice.dto.response.NoticeResponse;
import com.cms.admin.notice.dto.response.NoticeSummaryResponse;
import com.cms.admin.notice.service.NoticeService;
import com.cms.admin.menu.service.MenuService;
import com.cms.common.api.GlobalApiExceptionHandler;
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
import org.springframework.security.access.AccessDeniedException;
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

@WebMvcTest(controllers = NoticeController.class)
@Import({
        NoticeControllerTest.MockConfig.class,
        MethodSecurityTestConfig.class,
        GlobalApiExceptionHandler.class
})
class NoticeControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NoticeService noticeService;

    @Autowired
    AdminSecurityService adminSecurityService;

    @BeforeEach
    void setUp() {
        reset(noticeService, adminSecurityService);
        given(adminSecurityService.getCurrentAdminName()).willReturn("관리자");
        given(adminSecurityService.getCurrentAdminProfileImageUrl()).willReturn(null);
    }

    @TestConfiguration
    static class MockConfig {
        @Bean
        public NoticeService noticeService() {
            return Mockito.mock(NoticeService.class);
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

    private NoticeCreateRequest createRequest() {
        return NoticeCreateRequest.builder()
                .title("공지 제목")
                .content("공지 본문")
                .build();
    }

    private NoticeResponse noticeResponse() {
        return NoticeResponse.builder()
                .id(1L)
                .title("공지 제목")
                .content("공지 본문")
                .useYn(true)
                .authorId("admin01")
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .build();
    }

    private NoticePageResponse pageResponse() {
        return NoticePageResponse.builder()
                .content(List.of(NoticeSummaryResponse.builder().id(1L).title("공지 제목").useYn(true).authorId("admin01").build()))
                .page(0)
                .size(20)
                .totalElements(1)
                .totalPages(1)
                .last(true)
                .build();
    }

    // ===================== getNotices =====================

    @Test
    @DisplayName("목록 조회 성공 (ADMIN)")
    @WithMockUser(roles = "ADMIN")
    void getNotices_success_admin() throws Exception {
        given(noticeService.getNotices(any(), any())).willReturn(pageResponse());

        mockMvc.perform(get("/admin/api/notices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("공지 제목"));
    }

    @Test
    @DisplayName("목록 조회 성공 (MANAGER)")
    @WithMockUser(roles = "MANAGER")
    void getNotices_success_manager() throws Exception {
        given(noticeService.getNotices(any(), any())).willReturn(pageResponse());

        mockMvc.perform(get("/admin/api/notices"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("keyword 200자 초과 시 400 VALIDATION_ERROR")
    @WithMockUser(roles = "ADMIN")
    void getNotices_keywordTooLong_validationError() throws Exception {
        mockMvc.perform(get("/admin/api/notices").param("keyword", "가".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(noticeService);
    }

    @Test
    @DisplayName("인증 없이 목록 조회 시 401")
    void getNotices_unauthenticated() throws Exception {
        mockMvc.perform(get("/admin/api/notices"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("USER는 목록 조회 시 403")
    @WithMockUser(roles = "USER")
    void getNotices_userForbidden() throws Exception {
        mockMvc.perform(get("/admin/api/notices"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(noticeService);
    }

    // ===================== getNotice =====================

    @Test
    @DisplayName("상세 조회 성공")
    @WithMockUser(roles = "ADMIN")
    void getNotice_success() throws Exception {
        given(noticeService.getNotice(1L)).willReturn(noticeResponse());

        mockMvc.perform(get("/admin/api/notices/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("공지 본문"));
    }

    @Test
    @DisplayName("존재하지 않는 공지 조회 시 404")
    @WithMockUser(roles = "ADMIN")
    void getNotice_notFound() throws Exception {
        given(noticeService.getNotice(99L)).willThrow(new ResourceNotFoundException("공지사항을 찾을 수 없습니다."));

        mockMvc.perform(get("/admin/api/notices/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // ===================== createNotice =====================

    @Test
    @DisplayName("생성 성공 (201 Created + Location, ADMIN)")
    @WithMockUser(roles = "ADMIN")
    void createNotice_success_admin() throws Exception {
        given(noticeService.createNotice(any())).willReturn(noticeResponse());

        mockMvc.perform(post("/admin/api/notices")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/admin/api/notices/1")))
                .andExpect(jsonPath("$.title").value("공지 제목"));
    }

    @Test
    @DisplayName("생성 성공 (201 Created, MANAGER)")
    @WithMockUser(roles = "MANAGER")
    void createNotice_success_manager() throws Exception {
        given(noticeService.createNotice(any())).willReturn(noticeResponse());

        mockMvc.perform(post("/admin/api/notices")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("제목 누락 시 400 VALIDATION_ERROR")
    @WithMockUser(roles = "ADMIN")
    void createNotice_missingTitle_validationError() throws Exception {
        NoticeCreateRequest badRequest = NoticeCreateRequest.builder().content("본문").build();

        mockMvc.perform(post("/admin/api/notices")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(noticeService);
    }

    @Test
    @DisplayName("author 인증 정보 없으면 403 ACCESS_DENIED")
    @WithMockUser(roles = "ADMIN")
    void createNotice_accessDenied() throws Exception {
        given(noticeService.createNotice(any())).willThrow(new AccessDeniedException("인증 정보를 확인할 수 없습니다."));

        mockMvc.perform(post("/admin/api/notices")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("스크립트 태그가 포함된 title/content도 서버는 원본 그대로 저장·응답한다 (이스케이프는 렌더링 시점 클라이언트 책임)")
    @WithMockUser(roles = "ADMIN")
    void createNotice_xssPayload_roundTrip() throws Exception {
        String payloadTitle = "<script>alert('xss')</script>";
        String payloadContent = "<img src=x onerror=alert('xss')>";
        NoticeResponse response = NoticeResponse.builder()
                .id(1L).title(payloadTitle).content(payloadContent).useYn(true).authorId("admin01")
                .createDate(LocalDateTime.now()).updateDate(LocalDateTime.now())
                .build();
        given(noticeService.createNotice(any())).willReturn(response);

        NoticeCreateRequest request = NoticeCreateRequest.builder().title(payloadTitle).content(payloadContent).build();

        mockMvc.perform(post("/admin/api/notices")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value(payloadTitle))
                .andExpect(jsonPath("$.content").value(payloadContent));
    }

    @Test
    @DisplayName("인증 없이 생성 시 401")
    void createNotice_unauthenticated() throws Exception {
        mockMvc.perform(post("/admin/api/notices")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("USER는 생성 시 403")
    @WithMockUser(roles = "USER")
    void createNotice_userForbidden() throws Exception {
        mockMvc.perform(post("/admin/api/notices")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(noticeService);
    }

    // ===================== updateNotice =====================

    @Test
    @DisplayName("수정 성공 (200, ADMIN)")
    @WithMockUser(roles = "ADMIN")
    void updateNotice_success_admin() throws Exception {
        NoticeUpdateRequest request = NoticeUpdateRequest.builder().title("변경된 제목").build();
        given(noticeService.updateNotice(anyLong(), any())).willReturn(noticeResponse());

        mockMvc.perform(patch("/admin/api/notices/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("수정 성공 (200, MANAGER)")
    @WithMockUser(roles = "MANAGER")
    void updateNotice_success_manager() throws Exception {
        NoticeUpdateRequest request = NoticeUpdateRequest.builder().title("변경된 제목").build();
        given(noticeService.updateNotice(anyLong(), any())).willReturn(noticeResponse());

        mockMvc.perform(patch("/admin/api/notices/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("전체 필드 null인 PATCH는 400 INVALID_REQUEST")
    @WithMockUser(roles = "ADMIN")
    void updateNotice_allFieldsNull_invalidRequest() throws Exception {
        given(noticeService.updateNotice(anyLong(), any()))
                .willThrow(new InvalidRequestException("변경할 필드가 없습니다."));

        mockMvc.perform(patch("/admin/api/notices/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("존재하지 않는 공지 수정 시 404")
    @WithMockUser(roles = "ADMIN")
    void updateNotice_notFound() throws Exception {
        given(noticeService.updateNotice(anyLong(), any()))
                .willThrow(new ResourceNotFoundException("공지사항을 찾을 수 없습니다."));

        NoticeUpdateRequest request = NoticeUpdateRequest.builder().title("변경").build();

        mockMvc.perform(patch("/admin/api/notices/99")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("USER는 수정 시 403")
    @WithMockUser(roles = "USER")
    void updateNotice_userForbidden() throws Exception {
        NoticeUpdateRequest request = NoticeUpdateRequest.builder().title("변경").build();

        mockMvc.perform(patch("/admin/api/notices/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(noticeService);
    }

    // ===================== deleteNotice =====================

    @Test
    @DisplayName("삭제 성공 (204 No Content, ADMIN)")
    @WithMockUser(roles = "ADMIN")
    void deleteNotice_success_admin() throws Exception {
        given(noticeService.deleteNotice(1L)).willReturn(noticeResponse());

        mockMvc.perform(delete("/admin/api/notices/1").with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("삭제 성공 (204 No Content, MANAGER)")
    @WithMockUser(roles = "MANAGER")
    void deleteNotice_success_manager() throws Exception {
        given(noticeService.deleteNotice(1L)).willReturn(noticeResponse());

        mockMvc.perform(delete("/admin/api/notices/1").with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("존재하지 않는 공지 삭제 시 404")
    @WithMockUser(roles = "ADMIN")
    void deleteNotice_notFound() throws Exception {
        given(noticeService.deleteNotice(99L)).willThrow(new ResourceNotFoundException("공지사항을 찾을 수 없습니다."));

        mockMvc.perform(delete("/admin/api/notices/99").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("인증 없이 삭제 시 401")
    void deleteNotice_unauthenticated() throws Exception {
        mockMvc.perform(delete("/admin/api/notices/1").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("USER는 삭제 시 403")
    @WithMockUser(roles = "USER")
    void deleteNotice_userForbidden() throws Exception {
        mockMvc.perform(delete("/admin/api/notices/1").with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(noticeService);
    }
}
