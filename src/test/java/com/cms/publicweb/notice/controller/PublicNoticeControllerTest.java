package com.cms.publicweb.notice.controller;

import com.cms.admin.menu.service.MenuService;
import com.cms.common.api.GlobalApiExceptionHandler;
import com.cms.config.auth.AdminSecurityService;
import com.cms.publicweb.notice.dto.PublicNoticeDetail;
import com.cms.publicweb.notice.dto.PublicNoticeSummary;
import com.cms.publicweb.notice.service.PublicNoticeService;
import com.cms.publicweb.support.PublicWebExceptionAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 이 슬라이스는 @WebMvcTest 기본 보안(미인증 요청은 401)을 그대로 쓴다 — 실제
 * permitAll/denyAll(SecurityConfig) 회귀는 {@code SecurityConfigTest}가 전담한다
 * (AdminMainControllerTest·NoticeControllerTest와 동일한 이 프로젝트의 확립된 패턴).
 * 이 클래스는 컨트롤러 로직(뷰 이름·모델·404 분기·XSS 이스케이프·예외 advice 우선순위)만 검증한다.
 */
@WebMvcTest(controllers = PublicNoticeController.class)
@Import({
        PublicNoticeControllerTest.MockConfig.class,
        PublicWebExceptionAdvice.class,
        GlobalApiExceptionHandler.class
})
class PublicNoticeControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PublicNoticeService publicNoticeService;

    @Autowired
    AdminSecurityService adminSecurityService;

    @BeforeEach
    void setUp() {
        reset(publicNoticeService, adminSecurityService);
        given(adminSecurityService.getCurrentAdminName()).willReturn("관리자");
        given(adminSecurityService.getCurrentAdminProfileImageUrl()).willReturn(null);
    }

    @TestConfiguration
    static class MockConfig {
        @Bean
        public PublicNoticeService publicNoticeService() {
            return Mockito.mock(PublicNoticeService.class);
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

    private PublicNoticeSummary summary(Long id, String title) {
        return PublicNoticeSummary.builder().id(id).title(title).createDate(LocalDateTime.now()).build();
    }

    private PublicNoticeDetail detail(Long id, String title, String content) {
        return PublicNoticeDetail.builder()
                .id(id).title(title).content(content)
                .createDate(LocalDateTime.now()).updateDate(LocalDateTime.now())
                .build();
    }

    // ===================== list =====================

    @Test
    @DisplayName("목록 조회 성공 시 public/notice/list 뷰와 notices 모델을 반환한다")
    @WithMockUser
    void list_success_returnsViewAndModel() throws Exception {
        given(publicNoticeService.getPublishedNotices(0))
                .willReturn(new PageImpl<>(List.of(summary(1L, "공지 제목")), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/notices"))
                .andExpect(status().isOk())
                .andExpect(view().name("public/notice/list"))
                .andExpect(model().attributeExists("notices"))
                .andExpect(content().string(containsString("공지 제목")));
    }

    @Test
    @DisplayName("공지가 없으면 빈 상태 문구가 렌더링된다")
    @WithMockUser
    void list_empty_showsEmptyState() throws Exception {
        given(publicNoticeService.getPublishedNotices(0))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        mockMvc.perform(get("/notices"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("등록된 공지사항이 없습니다")));
    }

    @Test
    @DisplayName("page=abc(비숫자)는 파싱 실패로 0으로 흡수되어 200을 반환한다")
    @WithMockUser
    void list_pageNonNumeric_zeroedAndOk() throws Exception {
        given(publicNoticeService.getPublishedNotices(0)).willReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/notices").param("page", "abc"))
                .andExpect(status().isOk());

        verify(publicNoticeService).getPublishedNotices(0);
    }

    @Test
    @DisplayName("page가 정수 범위를 초과하는 문자열이면 파싱 실패로 0으로 흡수되어 200을 반환한다")
    @WithMockUser
    void list_pageIntegerOverflow_zeroedAndOk() throws Exception {
        given(publicNoticeService.getPublishedNotices(0)).willReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/notices").param("page", "99999999999999"))
                .andExpect(status().isOk());

        verify(publicNoticeService).getPublishedNotices(0);
    }

    @Test
    @DisplayName("size 쿼리 파라미터가 있어도 무시된다 — 컨트롤러 시그니처에 바인딩 대상이 없음")
    @WithMockUser
    void list_sizeParamIgnored() throws Exception {
        given(publicNoticeService.getPublishedNotices(anyInt())).willReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/notices").param("size", "999"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("제목에 스크립트 태그가 있어도 목록에서 이스케이프되어 실행되지 않는다")
    @WithMockUser
    void list_xssPayloadTitle_isEscaped() throws Exception {
        given(publicNoticeService.getPublishedNotices(0))
                .willReturn(new PageImpl<>(List.of(summary(1L, "<script>alert(1)</script>"))));

        mockMvc.perform(get("/notices"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>alert(1)</script>"))))
                .andExpect(content().string(containsString("&lt;script&gt;")));
    }

    // ===================== detail =====================

    @Test
    @DisplayName("상세 조회 성공 시 public/notice/detail 뷰와 notice 모델을 반환한다")
    @WithMockUser
    void detail_success_returnsViewAndModel() throws Exception {
        given(publicNoticeService.findPublishedNotice(1L))
                .willReturn(Optional.of(detail(1L, "공지 제목", "본문 내용")));

        mockMvc.perform(get("/notices/1"))
                .andExpect(status().isOk())
                .andExpect(view().name("public/notice/detail"))
                .andExpect(model().attributeExists("notice"))
                .andExpect(content().string(containsString("본문 내용")));
    }

    @Test
    @DisplayName("미노출·삭제·존재하지 않는 ID는 모두 404 + error/404 뷰(JSON 아님)")
    @WithMockUser
    void detail_notFound_returns404View() throws Exception {
        given(publicNoticeService.findPublishedNotice(99L)).willReturn(Optional.empty());

        mockMvc.perform(get("/notices/99"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    @DisplayName("비숫자 ID(/notices/abc)는 Service를 거치지 않고 404 + error/404 뷰(JSON 아님)")
    @WithMockUser
    void detail_nonNumericId_returns404NotJson() throws Exception {
        mockMvc.perform(get("/notices/abc"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));

        Mockito.verifyNoInteractions(publicNoticeService);
    }

    @Test
    @DisplayName("공개 상세 응답 본문에 작성자 정보가 없다 (DTO 자체에 authorId 필드 없음)")
    @WithMockUser
    void detail_doesNotExposeAuthorInfo() throws Exception {
        given(publicNoticeService.findPublishedNotice(1L))
                .willReturn(Optional.of(detail(1L, "공지 제목", "본문 내용")));

        mockMvc.perform(get("/notices/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("authorId"))))
                .andExpect(content().string(not(containsString("admin01"))));
    }

    @Test
    @DisplayName("본문에 스크립트·이벤트 속성 payload가 있어도 상세에서 이스케이프되어 실행되지 않는다")
    @WithMockUser
    void detail_xssPayloadContent_isEscaped() throws Exception {
        String payload = "<script>alert('xss')</script><img src=x onerror=\"alert('xss')\">";
        given(publicNoticeService.findPublishedNotice(1L))
                .willReturn(Optional.of(detail(1L, "제목", payload)));

        mockMvc.perform(get("/notices/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>alert('xss')</script>"))))
                .andExpect(content().string(not(containsString("<img"))))
                .andExpect(content().string(containsString("&lt;img")));
    }

    // ===================== 예외 처리 (범위 한정 advice 우선순위) =====================

    @Test
    @DisplayName("Service 실행 중 예외가 발생하면 JSON이 아니라 HTML 500(public/notice/error)으로 응답한다 — 전역 GlobalApiExceptionHandler보다 우선 적용")
    @WithMockUser
    void detail_serviceThrows_returnsHtml500NotJson() throws Exception {
        given(publicNoticeService.findPublishedNotice(anyLong())).willThrow(new RuntimeException("DB 장애 시뮬레이션"));

        mockMvc.perform(get("/notices/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("public/notice/error"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    @DisplayName("목록 조회 중 예외가 발생해도 JSON이 아니라 HTML 500으로 응답한다")
    @WithMockUser
    void list_serviceThrows_returnsHtml500NotJson() throws Exception {
        given(publicNoticeService.getPublishedNotices(eq(0))).willThrow(new RuntimeException("DB 장애 시뮬레이션"));

        mockMvc.perform(get("/notices"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("public/notice/error"));
    }
}
