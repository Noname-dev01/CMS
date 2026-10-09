package com.cms.admin.board.controller;

import com.cms.admin.board.dto.request.PostCreateRequest;
import com.cms.admin.board.dto.request.PostUpdateRequest;
import com.cms.admin.board.dto.response.PostPageResponse;
import com.cms.admin.board.dto.response.PostResponse;
import com.cms.admin.board.dto.response.PostSummaryResponse;
import com.cms.admin.board.service.PostService;
import com.cms.admin.menu.service.MenuService;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.PermissionCache;
import com.cms.common.api.GlobalApiExceptionHandler;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.config.MethodSecurityTestConfig;
import com.cms.config.PermissionTestConfig;
import com.cms.config.WithManager;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 게시글 관리 API 슬라이스 시험. 옛 {@code NoticeControllerTest}를 이식했다(공지가 공지 게시판으로 흡수됨, PLAN-notice-to-board.md) — 상태 코드·검증 오류·
 * 인증·역할 계약은 같고, 게시글은 판정 키가 (회원, 게시판, 동작)이라 게시판별 거부(다른 게시판 403)가 더해진다. {@code @WithManager}는
 * {@link PermissionTestConfig#SEED_BOARD_ID} 게시판의 4동작이 허용된 주체다.
 */
@WebMvcTest(controllers = PostController.class)
@Import({
        PostControllerTest.MockConfig.class,
        MethodSecurityTestConfig.class,
        GlobalApiExceptionHandler.class
})
class PostControllerTest {

    private static final long BOARD = PermissionTestConfig.SEED_BOARD_ID;
    private static final String BASE = "/admin/api/boards/" + BOARD + "/posts";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PostService postService;

    @Autowired
    AdminSecurityService adminSecurityService;

    @Autowired
    PermissionCache permissionCache;

    @BeforeEach
    void setUp() {
        reset(postService, adminSecurityService);
        given(adminSecurityService.getCurrentAdminName()).willReturn("관리자");
        given(adminSecurityService.getCurrentAdminProfileImageUrl()).willReturn(null);
    }

    @TestConfiguration
    static class MockConfig {
        @Bean
        public PostService postService() {
            return Mockito.mock(PostService.class);
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

    private PostCreateRequest createRequest() {
        return PostCreateRequest.builder().title("글 제목").content("글 본문").build();
    }

    private PostResponse postResponse() {
        return PostResponse.builder()
                .id(1L).boardId(BOARD).title("글 제목").content("글 본문").useYn(true).authorId("admin01")
                .createDate(LocalDateTime.now()).updateDate(LocalDateTime.now())
                .build();
    }

    private PostPageResponse pageResponse() {
        return PostPageResponse.builder()
                .content(List.of(PostSummaryResponse.builder().id(1L).title("글 제목").useYn(true).authorId("admin01").build()))
                .page(0).size(20).totalElements(1).totalPages(1).last(true)
                .build();
    }

    // ===================== getPosts =====================

    @Test
    @DisplayName("목록 조회 성공 (ADMIN)")
    @WithMockUser(roles = "ADMIN")
    void getPosts_success_admin() throws Exception {
        given(postService.getPosts(anyLong(), any(), any())).willReturn(pageResponse());

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("글 제목"));
    }

    @Test
    @DisplayName("목록 조회 성공 (게시판 READ가 있는 MANAGER)")
    @WithManager
    void getPosts_success_manager() throws Exception {
        given(postService.getPosts(anyLong(), any(), any())).willReturn(pageResponse());

        mockMvc.perform(get(BASE)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("다른 게시판은 권한이 없는 MANAGER에게 403 ACCESS_DENIED — 게시판별 위임")
    @WithManager
    void getPosts_otherBoard_forbidden() throws Exception {
        mockMvc.perform(get("/admin/api/boards/" + (BOARD + 1) + "/posts"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(postService);
    }

    @Test
    @DisplayName("keyword 200자 초과 시 400 VALIDATION_ERROR")
    @WithMockUser(roles = "ADMIN")
    void getPosts_keywordTooLong_validationError() throws Exception {
        mockMvc.perform(get(BASE).param("keyword", "가".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(postService);
    }

    @Test
    @DisplayName("인증 없이 목록 조회 시 401")
    void getPosts_unauthenticated() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("USER는 목록 조회 시 403")
    @WithMockUser(roles = "USER")
    void getPosts_userForbidden() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isForbidden());

        verifyNoInteractions(postService);
    }

    // ===================== getPost =====================

    @Test
    @DisplayName("상세 조회 성공")
    @WithMockUser(roles = "ADMIN")
    void getPost_success() throws Exception {
        given(postService.getPost(BOARD, 1L)).willReturn(postResponse());

        mockMvc.perform(get(BASE + "/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("글 본문"));
    }

    @Test
    @DisplayName("존재하지 않는 게시글 조회 시 404")
    @WithMockUser(roles = "ADMIN")
    void getPost_notFound() throws Exception {
        given(postService.getPost(BOARD, 99L)).willThrow(new ResourceNotFoundException("게시글을 찾을 수 없습니다."));

        mockMvc.perform(get(BASE + "/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // ===================== createPost =====================

    @Test
    @DisplayName("생성 성공 (201 Created + Location, ADMIN)")
    @WithMockUser(roles = "ADMIN")
    void createPost_success_admin() throws Exception {
        given(postService.createPost(anyLong(), any())).willReturn(postResponse());

        mockMvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString(BASE + "/1")))
                .andExpect(jsonPath("$.title").value("글 제목"));
    }

    @Test
    @DisplayName("생성 성공 (201 Created, CREATE까지 가진 MANAGER)")
    @WithManager
    void createPost_success_manager() throws Exception {
        given(postService.createPost(anyLong(), any())).willReturn(postResponse());

        mockMvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("제목 누락 시 400 VALIDATION_ERROR")
    @WithMockUser(roles = "ADMIN")
    void createPost_missingTitle_validationError() throws Exception {
        PostCreateRequest badRequest = PostCreateRequest.builder().content("본문").build();

        mockMvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(postService);
    }

    @Test
    @DisplayName("author 인증 정보 없으면 403 ACCESS_DENIED")
    @WithMockUser(roles = "ADMIN")
    void createPost_accessDenied() throws Exception {
        given(postService.createPost(anyLong(), any())).willThrow(new AccessDeniedException("인증 정보를 확인할 수 없습니다."));

        mockMvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("스크립트 태그가 포함된 title/content도 컨트롤러는 서비스가 돌려준 값을 그대로 응답한다 (정리는 서비스·출력 경로 책임, 이스케이프는 렌더링 클라이언트 책임)")
    @WithMockUser(roles = "ADMIN")
    void createPost_xssPayload_roundTrip() throws Exception {
        String payloadTitle = "<script>alert('xss')</script>";
        String payloadContent = "<img src=x onerror=alert('xss')>";
        PostResponse response = PostResponse.builder()
                .id(1L).boardId(BOARD).title(payloadTitle).content(payloadContent).useYn(true).authorId("admin01")
                .createDate(LocalDateTime.now()).updateDate(LocalDateTime.now())
                .build();
        given(postService.createPost(anyLong(), any())).willReturn(response);

        PostCreateRequest request = PostCreateRequest.builder().title(payloadTitle).content(payloadContent).build();

        mockMvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value(payloadTitle))
                .andExpect(jsonPath("$.content").value(payloadContent));
    }

    @Test
    @DisplayName("인증 없이 생성 시 401")
    void createPost_unauthenticated() throws Exception {
        mockMvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("USER는 생성 시 403")
    @WithMockUser(roles = "USER")
    void createPost_userForbidden() throws Exception {
        mockMvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(postService);
    }

    // ===================== updatePost =====================

    @Test
    @DisplayName("수정 성공 (200, ADMIN)")
    @WithMockUser(roles = "ADMIN")
    void updatePost_success_admin() throws Exception {
        given(postService.updatePost(anyLong(), anyLong(), any())).willReturn(postResponse());

        mockMvc.perform(patch(BASE + "/1").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(PostUpdateRequest.builder().title("변경된 제목").build())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("수정 성공 (200, MANAGER)")
    @WithManager
    void updatePost_success_manager() throws Exception {
        given(postService.updatePost(anyLong(), anyLong(), any())).willReturn(postResponse());

        mockMvc.perform(patch(BASE + "/1").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(PostUpdateRequest.builder().title("변경된 제목").build())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("전체 필드 null인 PATCH는 400 INVALID_REQUEST")
    @WithMockUser(roles = "ADMIN")
    void updatePost_allFieldsNull_invalidRequest() throws Exception {
        given(postService.updatePost(anyLong(), anyLong(), any())).willThrow(new InvalidRequestException("변경할 필드가 없습니다."));

        mockMvc.perform(patch(BASE + "/1").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("존재하지 않는 게시글 수정 시 404")
    @WithMockUser(roles = "ADMIN")
    void updatePost_notFound() throws Exception {
        given(postService.updatePost(anyLong(), anyLong(), any())).willThrow(new ResourceNotFoundException("게시글을 찾을 수 없습니다."));

        mockMvc.perform(patch(BASE + "/99").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(PostUpdateRequest.builder().title("변경").build())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("USER는 수정 시 403")
    @WithMockUser(roles = "USER")
    void updatePost_userForbidden() throws Exception {
        mockMvc.perform(patch(BASE + "/1").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(PostUpdateRequest.builder().title("변경").build())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(postService);
    }

    // ===================== deletePost =====================

    @Test
    @DisplayName("삭제 성공 (204 No Content, ADMIN)")
    @WithMockUser(roles = "ADMIN")
    void deletePost_success_admin() throws Exception {
        given(postService.deletePost(BOARD, 1L)).willReturn(postResponse());

        mockMvc.perform(delete(BASE + "/1").with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("삭제 성공 (204 No Content, MANAGER)")
    @WithManager
    void deletePost_success_manager() throws Exception {
        given(postService.deletePost(BOARD, 1L)).willReturn(postResponse());

        mockMvc.perform(delete(BASE + "/1").with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("존재하지 않는 게시글 삭제 시 404")
    @WithMockUser(roles = "ADMIN")
    void deletePost_notFound() throws Exception {
        given(postService.deletePost(BOARD, 99L)).willThrow(new ResourceNotFoundException("게시글을 찾을 수 없습니다."));

        mockMvc.perform(delete(BASE + "/99").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("인증 없이 삭제 시 401")
    void deletePost_unauthenticated() throws Exception {
        mockMvc.perform(delete(BASE + "/1").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("USER는 삭제 시 403")
    @WithMockUser(roles = "USER")
    void deletePost_userForbidden() throws Exception {
        mockMvc.perform(delete(BASE + "/1").with(csrf())).andExpect(status().isForbidden());

        verifyNoInteractions(postService);
    }

    @Test
    @DisplayName("READ만 가진 MANAGER의 생성은 메서드 계층에서 403 ACCESS_DENIED이고 서비스는 호출되지 않는다 — message는 영문 Access Denied(화면이 403에 고정 한국어 문구를 쓰는 이유)")
    @WithManager
    void createPost_managerWithReadOnly_forbidden() throws Exception {
        given(permissionCache.snapshot()).willReturn(PermissionTestConfig.snapshotWith(PermissionAction.READ));
        try {
            mockMvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"제목\",\"content\":\"본문\",\"useYn\":true}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                    // 메서드 계층 403은 GlobalApiExceptionHandler가 Spring Security 기본 메시지를 그대로 내보낸다(필터 계층은 고정 한국어 문구)
                    .andExpect(jsonPath("$.message").value("Access Denied"));
            verifyNoInteractions(postService);
        } finally {
            given(permissionCache.snapshot()).willReturn(PermissionTestConfig.seedSnapshot());
        }
    }
}
