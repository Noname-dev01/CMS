package com.cms.admin.notice.controller;

import com.cms.admin.menu.service.MenuService;
import com.cms.admin.notice.dto.response.NoticeAttachmentDownload;
import com.cms.admin.notice.dto.response.NoticeAttachmentResponse;
import com.cms.admin.notice.service.NoticeAttachmentService;
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
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = NoticeAttachmentController.class)
@Import({
        NoticeAttachmentControllerTest.MockConfig.class,
        MethodSecurityTestConfig.class,
        GlobalApiExceptionHandler.class
})
class NoticeAttachmentControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    NoticeAttachmentService noticeAttachmentService;

    @Autowired
    AdminSecurityService adminSecurityService;

    @BeforeEach
    void setUp() {
        reset(noticeAttachmentService, adminSecurityService);
        given(adminSecurityService.getCurrentAdminName()).willReturn("관리자");
        given(adminSecurityService.getCurrentAdminProfileImageUrl()).willReturn(null);
    }

    @TestConfiguration
    static class MockConfig {
        @Bean
        public NoticeAttachmentService noticeAttachmentService() {
            return Mockito.mock(NoticeAttachmentService.class);
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

    private NoticeAttachmentResponse attachmentResponse() {
        return NoticeAttachmentResponse.builder()
                .id(10L).originalFilename("report.pdf").contentType("application/pdf")
                .fileSize(100L).createDate(LocalDateTime.now())
                .build();
    }

    // ===================== upload =====================

    @Test
    @DisplayName("업로드 성공 (201 Created + Location, ADMIN)")
    @WithMockUser(roles = "ADMIN")
    void upload_success_admin() throws Exception {
        given(noticeAttachmentService.upload(anyLong(), any())).willReturn(attachmentResponse());
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "content".getBytes());

        mockMvc.perform(multipart("/admin/api/notices/1/attachments").file(file).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/admin/api/notices/1/attachments/10")))
                .andExpect(jsonPath("$.originalFilename").value("report.pdf"));
    }

    @Test
    @DisplayName("업로드 성공 (201 Created, MANAGER)")
    @WithMockUser(roles = "MANAGER")
    void upload_success_manager() throws Exception {
        given(noticeAttachmentService.upload(anyLong(), any())).willReturn(attachmentResponse());
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "content".getBytes());

        mockMvc.perform(multipart("/admin/api/notices/1/attachments").file(file).with(csrf()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("허용되지 않는 확장자면 400 INVALID_REQUEST")
    @WithMockUser(roles = "ADMIN")
    void upload_invalidExtension_badRequest() throws Exception {
        given(noticeAttachmentService.upload(anyLong(), any()))
                .willThrow(new InvalidRequestException("허용되지 않는 파일 형식입니다: .exe"));
        MockMultipartFile file = new MockMultipartFile("file", "malware.exe", "application/octet-stream", "x".getBytes());

        mockMvc.perform(multipart("/admin/api/notices/1/attachments").file(file).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("존재하지 않는 공지에 업로드 시 404")
    @WithMockUser(roles = "ADMIN")
    void upload_noticeNotFound() throws Exception {
        given(noticeAttachmentService.upload(anyLong(), any()))
                .willThrow(new ResourceNotFoundException("공지사항을 찾을 수 없습니다."));
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "x".getBytes());

        mockMvc.perform(multipart("/admin/api/notices/99/attachments").file(file).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("첨부 5개 초과 시 409 RESOURCE_CONFLICT")
    @WithMockUser(roles = "ADMIN")
    void upload_countExceeded_conflict() throws Exception {
        given(noticeAttachmentService.upload(anyLong(), any()))
                .willThrow(new ConflictException("공지사항당 첨부파일은 최대 5개까지 업로드할 수 있습니다."));
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "x".getBytes());

        mockMvc.perform(multipart("/admin/api/notices/1/attachments").file(file).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
    }

    @Test
    @DisplayName("file 파트 자체가 없으면 400 INVALID_REQUEST (500 아님)")
    @WithMockUser(roles = "ADMIN")
    void upload_missingFilePart_badRequest() throws Exception {
        mockMvc.perform(multipart("/admin/api/notices/1/attachments").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(noticeAttachmentService);
    }

    @Test
    @DisplayName("인증 없이 업로드 시 401")
    void upload_unauthenticated() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "x".getBytes());

        mockMvc.perform(multipart("/admin/api/notices/1/attachments").file(file).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("USER는 업로드 시 403")
    @WithMockUser(roles = "USER")
    void upload_userForbidden() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "x".getBytes());

        mockMvc.perform(multipart("/admin/api/notices/1/attachments").file(file).with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(noticeAttachmentService);
    }

    // ===================== list =====================

    @Test
    @DisplayName("목록 조회 성공")
    @WithMockUser(roles = "ADMIN")
    void list_success() throws Exception {
        given(noticeAttachmentService.list(1L)).willReturn(List.of(attachmentResponse()));

        mockMvc.perform(get("/admin/api/notices/1/attachments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].originalFilename").value("report.pdf"));
    }

    @Test
    @DisplayName("목록 조회 — 존재하지 않는 공지면 404")
    @WithMockUser(roles = "ADMIN")
    void list_noticeNotFound() throws Exception {
        given(noticeAttachmentService.list(99L)).willThrow(new ResourceNotFoundException("공지사항을 찾을 수 없습니다."));

        mockMvc.perform(get("/admin/api/notices/99/attachments"))
                .andExpect(status().isNotFound());
    }

    // ===================== content(다운로드) =====================

    @Test
    @DisplayName("다운로드 성공 — octet-stream·attachment·nosniff 헤더가 함께 반환된다")
    @WithMockUser(roles = "ADMIN")
    void content_success() throws Exception {
        given(noticeAttachmentService.download(1L, 10L))
                .willReturn(new NoticeAttachmentDownload("report.pdf", "content".getBytes()));

        mockMvc.perform(get("/admin/api/notices/1/attachments/10/content"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .andExpect(header().string("Content-Disposition", containsString("report.pdf")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @DisplayName("다른 notice의 attachmentId로 접근 시 404 (IDOR 차단)")
    @WithMockUser(roles = "ADMIN")
    void content_wrongNotice_notFound() throws Exception {
        given(noticeAttachmentService.download(2L, 10L))
                .willThrow(new ResourceNotFoundException("첨부파일을 찾을 수 없습니다."));

        mockMvc.perform(get("/admin/api/notices/2/attachments/10/content"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // ===================== delete =====================

    @Test
    @DisplayName("삭제 성공 (204 No Content, ADMIN)")
    @WithMockUser(roles = "ADMIN")
    void delete_success_admin() throws Exception {
        given(noticeAttachmentService.delete(1L, 10L)).willReturn(attachmentResponse());

        mockMvc.perform(delete("/admin/api/notices/1/attachments/10").with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("삭제 성공 (204 No Content, MANAGER)")
    @WithMockUser(roles = "MANAGER")
    void delete_success_manager() throws Exception {
        given(noticeAttachmentService.delete(1L, 10L)).willReturn(attachmentResponse());

        mockMvc.perform(delete("/admin/api/notices/1/attachments/10").with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("존재하지 않는 첨부 삭제 시 404")
    @WithMockUser(roles = "ADMIN")
    void delete_notFound() throws Exception {
        given(noticeAttachmentService.delete(1L, 99L)).willThrow(new ResourceNotFoundException("첨부파일을 찾을 수 없습니다."));

        mockMvc.perform(delete("/admin/api/notices/1/attachments/99").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("인증 없이 삭제 시 401")
    void delete_unauthenticated() throws Exception {
        mockMvc.perform(delete("/admin/api/notices/1/attachments/10").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("USER는 삭제 시 403")
    @WithMockUser(roles = "USER")
    void delete_userForbidden() throws Exception {
        mockMvc.perform(delete("/admin/api/notices/1/attachments/10").with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(noticeAttachmentService);
    }
}
