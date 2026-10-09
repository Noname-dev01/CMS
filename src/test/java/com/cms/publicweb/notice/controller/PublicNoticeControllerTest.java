package com.cms.publicweb.notice.controller;

import com.cms.admin.menu.service.MenuService;
import com.cms.common.api.GlobalApiExceptionHandler;
import com.cms.config.auth.AdminSecurityService;
import com.cms.publicweb.board.dto.PublicBoardListResult;
import com.cms.publicweb.board.dto.PublicPostAttachmentDownload;
import com.cms.publicweb.board.dto.PublicPostAttachmentRef;
import com.cms.publicweb.board.dto.PublicPostDetail;
import com.cms.publicweb.board.dto.PublicPostSummary;
import com.cms.publicweb.notice.service.PublicNoticeService;
import com.cms.publicweb.support.PublicWebExceptionAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

        @Bean
        public com.cms.admin.permission.AdminPermissionEvaluator adminPermissionEvaluator() {
            return Mockito.mock(com.cms.admin.permission.AdminPermissionEvaluator.class);
        }
    }

    private PublicPostSummary summary(Long id, String title) {
        return PublicPostSummary.builder().id(id).title(title).createDate(LocalDateTime.now()).build();
    }

    private PublicPostDetail detail(Long id, String title, String content) {
        return PublicPostDetail.builder()
                .id(id).title(title).content(content)
                .createDate(LocalDateTime.now()).updateDate(LocalDateTime.now())
                .build();
    }

    private com.cms.publicweb.board.dto.PublicPostAttachment attachmentDto(Long id, String filename) {
        return com.cms.publicweb.board.dto.PublicPostAttachment.builder()
                .id(id).originalFilename(filename).fileSize(100L).fileSizeText("100 B")
                .build();
    }

    private PublicPostDetail detailWithAttachments(Long id, String title, String content,
                                                       List<com.cms.publicweb.board.dto.PublicPostAttachment> attachments) {
        return PublicPostDetail.builder()
                .id(id).title(title).content(content)
                .createDate(LocalDateTime.now()).updateDate(LocalDateTime.now())
                .attachments(attachments)
                .build();
    }

    // ===================== list =====================

    /** 서비스가 돌려주는 목록 결과(공지 게시판 공개 상태). */
    private static Optional<PublicBoardListResult> listResult(Page<PublicPostSummary> page, String keyword) {
        return Optional.of(new PublicBoardListResult(3L, "공지사항", page, keyword));
    }

    /** 검색하지 않은 목록 결과(keyword=null). */
    private static Optional<PublicBoardListResult> listOf(Page<PublicPostSummary> page) {
        return listResult(page, null);
    }

    @Test
    @DisplayName("공지 게시판이 공개 상태가 아니면(서비스가 empty) 목록은 상세와 같은 404 error/404 뷰다 — 200 빈 목록·500이 아님(PLAN-notice-to-board.md R2-1)")
    @WithMockUser
    void list_noticeBoardNotPublic_returns404() throws Exception {
        given(publicNoticeService.getPublishedNotices(0, null)).willReturn(Optional.empty());

        mockMvc.perform(get("/notices"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    @Test
    @DisplayName("공지 게시판이 공개 상태가 아니면 HEAD 목록도 404다")
    @WithMockUser
    void list_noticeBoardNotPublic_headReturns404() throws Exception {
        given(publicNoticeService.getPublishedNotices(0, null)).willReturn(Optional.empty());

        mockMvc.perform(head("/notices")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("목록 조회 성공 시 public/notice/list 뷰와 notices 모델을 반환한다")
    @WithMockUser
    void list_success_returnsViewAndModel() throws Exception {
        given(publicNoticeService.getPublishedNotices(0, null))
                .willReturn(listOf(new PageImpl<>(List.of(summary(1L, "공지 제목")), PageRequest.of(0, 10), 1)));

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
        given(publicNoticeService.getPublishedNotices(0, null))
                .willReturn(listOf(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0)));

        mockMvc.perform(get("/notices"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("등록된 공지사항이 없습니다")))
                .andExpect(content().string(not(containsString("전체 보기"))));
    }

    @Test
    @DisplayName("page=abc(비숫자)는 파싱 실패로 0으로 흡수되어 200을 반환한다")
    @WithMockUser
    void list_pageNonNumeric_zeroedAndOk() throws Exception {
        given(publicNoticeService.getPublishedNotices(0, null)).willReturn(listOf(new PageImpl<>(List.of())));

        mockMvc.perform(get("/notices").param("page", "abc"))
                .andExpect(status().isOk());

        verify(publicNoticeService).getPublishedNotices(0, null);
    }

    @Test
    @DisplayName("page가 정수 범위를 초과하는 문자열이면 파싱 실패로 0으로 흡수되어 200을 반환한다")
    @WithMockUser
    void list_pageIntegerOverflow_zeroedAndOk() throws Exception {
        given(publicNoticeService.getPublishedNotices(0, null)).willReturn(listOf(new PageImpl<>(List.of())));

        mockMvc.perform(get("/notices").param("page", "99999999999999"))
                .andExpect(status().isOk());

        verify(publicNoticeService).getPublishedNotices(0, null);
    }

    @Test
    @DisplayName("size 쿼리 파라미터가 있어도 무시된다 — 컨트롤러 시그니처에 바인딩 대상이 없음")
    @WithMockUser
    void list_sizeParamIgnored() throws Exception {
        given(publicNoticeService.getPublishedNotices(anyInt(), any())).willReturn(listOf(new PageImpl<>(List.of())));

        mockMvc.perform(get("/notices").param("size", "999"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("제목에 스크립트 태그가 있어도 목록에서 이스케이프되어 실행되지 않는다")
    @WithMockUser
    void list_xssPayloadTitle_isEscaped() throws Exception {
        given(publicNoticeService.getPublishedNotices(0, null))
                .willReturn(listOf(new PageImpl<>(List.of(summary(1L, "<script>alert(1)</script>")))));

        mockMvc.perform(get("/notices"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>alert(1)</script>"))))
                .andExpect(content().string(containsString("&lt;script&gt;")));
    }

    // ===================== list: 제목 검색 (PLAN-public-notice-search.md 쟁점 3~5·7) =====================

    @Test
    @DisplayName("keyword 원문을 그대로 서비스에 넘기고, 서비스가 정규화한 검색어를 모델·입력란 값으로 쓴다")
    @WithMockUser
    void list_keyword_passedRawAndNormalizedEchoed() throws Exception {
        given(publicNoticeService.getPublishedNotices(0, "  점검 "))
                .willReturn(listResult(
                        new PageImpl<>(List.of(summary(1L, "서버 점검 안내")), PageRequest.of(0, 10), 1), "점검"));

        mockMvc.perform(get("/notices").param("keyword", "  점검 "))
                .andExpect(status().isOk())
                .andExpect(model().attribute("keyword", "점검"))
                .andExpect(content().string(containsString("value=\"점검\"")))
                .andExpect(content().string(containsString("서버 점검 안내")))
                .andExpect(content().string(containsString("전체 보기")));

        verify(publicNoticeService).getPublishedNotices(0, "  점검 ");
    }

    @Test
    @DisplayName("검색 결과가 없으면 '검색 결과가 없습니다' 문구를 보인다")
    @WithMockUser
    void list_keywordNoResult_showsSearchEmptyState() throws Exception {
        given(publicNoticeService.getPublishedNotices(0, "없는말"))
                .willReturn(listResult(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0), "없는말"));

        mockMvc.perform(get("/notices").param("keyword", "없는말"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("검색 결과가 없습니다")))
                .andExpect(content().string(not(containsString("등록된 공지사항이 없습니다"))));
    }

    @Test
    @DisplayName("검색 중 페이지 링크에는 URL 인코딩된 keyword가 붙는다")
    @WithMockUser
    void list_keywordPagination_keepsEncodedKeyword() throws Exception {
        given(publicNoticeService.getPublishedNotices(1, "점검 & 공지"))
                .willReturn(listResult(
                        new PageImpl<>(List.of(summary(1L, "점검 & 공지 1")), PageRequest.of(1, 10), 25), "점검 & 공지"));

        String html = mockMvc.perform(get("/notices").param("page", "1").param("keyword", "점검 & 공지"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String encoded = "keyword=%EC%A0%90%EA%B2%80%20%26%20%EA%B3%B5%EC%A7%80";
        assertTrue(html.contains("/notices?page=0&amp;" + encoded), "이전 링크에 keyword가 없습니다:\n" + html);
        assertTrue(html.contains("/notices?page=2&amp;" + encoded), "다음 링크에 keyword가 없습니다:\n" + html);
    }

    @Test
    @DisplayName("검색하지 않은 목록의 페이지 링크에는 keyword가 붙지 않는다")
    @WithMockUser
    void list_noKeywordPagination_hasNoKeywordParam() throws Exception {
        given(publicNoticeService.getPublishedNotices(1, null))
                .willReturn(listOf(new PageImpl<>(List.of(summary(1L, "공지")), PageRequest.of(1, 10), 25)));

        String html = mockMvc.perform(get("/notices").param("page", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("href=\"/notices?page=0\""), html);
        assertTrue(html.contains("href=\"/notices?page=2\""), html);
        assertFalse(html.contains("keyword="), "검색하지 않았는데 keyword 파라미터가 있습니다:\n" + html);
    }

    @Test
    @DisplayName("검색어의 스크립트·속성 탈출 페이로드는 입력란 value와 링크에서 이스케이프된다")
    @WithMockUser
    void list_keywordXssPayloads_areEscaped() throws Exception {
        String scriptPayload = "<script>alert(1)</script>";
        String attrPayload = "\" autofocus onfocus=\"alert(1)";
        for (String payload : List.of(scriptPayload, attrPayload)) {
            given(publicNoticeService.getPublishedNotices(eq(0), eq(payload)))
                    .willReturn(listResult(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0), payload));

            String html = mockMvc.perform(get("/notices").param("keyword", payload))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertFalse(html.contains(payload), "페이로드가 이스케이프되지 않았습니다: " + payload);
            assertFalse(html.contains("onfocus=\"alert(1)"), "속성 탈출이 성공했습니다:\n" + html);
        }
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
    @DisplayName("본문의 스크립트·이벤트 속성 payload는 PublicPostDetail.from의 sanitize로 제거되고 허용 서식만 HTML로 출력된다")
    @WithMockUser
    void detail_xssPayloadContent_isSanitized() throws Exception {
        // 본문은 HTML이다(PLAN-html-editor.md) — 상세는 th:utext로 출력하므로 방어선은 DTO 생성 시 sanitize다.
        // 그래서 빌더가 아니라 실제 생성 경로(from)로 DTO를 만든다.
        String payload = "<p><strong>굵게</strong><script>alert('xss')</script><img src=x onerror=\"alert('xss')\">"
                + "<a href=\"javascript:alert(1)\">링크</a></p>";
        com.cms.admin.board.domain.Post post = com.cms.admin.board.domain.Post.builder()
                .id(1L).boardId(3L).title("제목").content(payload).useYn(true).deleted(false).authorId("admin01").build();
        com.cms.admin.board.domain.Board board = com.cms.admin.board.domain.Board.builder().id(3L).name("공지사항").publicYn(true).attachmentYn(true).deleted(false).build();
        given(publicNoticeService.findPublishedNotice(1L))
                .willReturn(Optional.of(PublicPostDetail.from(board, post, java.util.List.of())));

        mockMvc.perform(get("/notices/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>alert('xss')</script>"))))
                .andExpect(content().string(not(containsString("onerror"))))
                .andExpect(content().string(not(containsString("javascript:"))))
                .andExpect(content().string(not(containsString("<img"))))
                .andExpect(content().string(containsString("<strong>굵게</strong>")));
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
        given(publicNoticeService.getPublishedNotices(eq(0), any())).willThrow(new RuntimeException("DB 장애 시뮬레이션"));

        mockMvc.perform(get("/notices"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("public/notice/error"));
    }

    // ===================== detail: 첨부 목록 렌더링 =====================

    @Test
    @DisplayName("상세에 첨부 링크와 파일명이 렌더링된다")
    @WithMockUser
    void detail_rendersAttachmentLink() throws Exception {
        given(publicNoticeService.findPublishedNotice(1L))
                .willReturn(Optional.of(detailWithAttachments(1L, "제목", "본문",
                        List.of(attachmentDto(7L, "report.pdf")))));

        mockMvc.perform(get("/notices/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/notices/1/attachments/7")))
                .andExpect(content().string(containsString("report.pdf")));
    }

    @Test
    @DisplayName("첨부가 0건이면 '첨부파일' 섹션 문구가 없다")
    @WithMockUser
    void detail_noAttachments_noAttachmentSection() throws Exception {
        given(publicNoticeService.findPublishedNotice(1L))
                .willReturn(Optional.of(detail(1L, "제목", "본문")));

        mockMvc.perform(get("/notices/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("첨부파일"))));
    }

    @Test
    @DisplayName("첨부 파일명에 스크립트 태그가 있어도 이스케이프되어 실행되지 않는다")
    @WithMockUser
    void detail_attachmentFilenameXss_isEscaped() throws Exception {
        given(publicNoticeService.findPublishedNotice(1L))
                .willReturn(Optional.of(detailWithAttachments(1L, "제목", "본문",
                        List.of(attachmentDto(7L, "<script>alert(1)</script>.txt")))));

        mockMvc.perform(get("/notices/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>alert(1)</script>"))))
                .andExpect(content().string(containsString("&lt;script&gt;")));
    }

    @Test
    @DisplayName("상세 응답 본문에 storageKey(서버 내부 경로)가 노출되지 않는다")
    @WithMockUser
    void detail_doesNotExposeStorageKey() throws Exception {
        given(publicNoticeService.findPublishedNotice(1L))
                .willReturn(Optional.of(detailWithAttachments(1L, "제목", "본문",
                        List.of(attachmentDto(7L, "report.pdf")))));

        mockMvc.perform(get("/notices/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("storageKey"))));
    }

    // ===================== attachment (다운로드, 스트리밍) =====================

    private static final PublicPostAttachmentRef REF = new PublicPostAttachmentRef("report.pdf", "2026/08/03/7.pdf");

    /** 읽기·닫힘을 관측하는 스텁 스트림. failAfter 바이트를 넘겨 읽으려 하면 IOException을 던진다(-1이면 안 던짐). */
    static class TrackingInputStream extends InputStream {
        private final byte[] data;
        private final int failAfter;
        private int position = 0;
        int readCalls = 0;
        boolean closed = false;
        boolean failOnClose = false;

        TrackingInputStream(byte[] data, int failAfter) {
            this.data = data;
            this.failAfter = failAfter;
        }

        @Override
        public int read() throws IOException {
            readCalls++;
            if (failAfter >= 0 && position >= failAfter) {
                throw new IOException("디스크 읽기 실패 시뮬레이션");
            }
            return position < data.length ? data[position++] & 0xff : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            readCalls++;
            if (failAfter >= 0 && position >= failAfter) {
                throw new IOException("디스크 읽기 실패 시뮬레이션");
            }
            if (position >= data.length) {
                return -1;
            }
            int limit = failAfter >= 0 ? Math.min(data.length, failAfter) : data.length;
            int n = Math.min(len, limit - position);
            System.arraycopy(data, position, b, off, n);
            position += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            if (failOnClose) {
                throw new IOException("close 실패 시뮬레이션");
            }
        }
    }

    private TrackingInputStream stubDownload(String filename, byte[] bytes, int failAfter) {
        TrackingInputStream stream = new TrackingInputStream(bytes, failAfter);
        given(publicNoticeService.findPublishedAttachment(1L, 7L)).willReturn(Optional.of(REF));
        given(publicNoticeService.openAttachment(REF))
                .willReturn(Optional.of(new PublicPostAttachmentDownload(filename, bytes.length, stream)));
        return stream;
    }

    @Test
    @DisplayName("다운로드 성공 시 octet-stream·Content-Disposition·nosniff·no-store·Content-Length 헤더와 바디를 반환하고 스트림을 닫는다")
    @WithMockUser
    void attachment_success_returnsFileWithHeaders() throws Exception {
        TrackingInputStream stream = stubDownload("report.pdf", "content".getBytes(), -1);

        mockMvc.perform(get("/notices/1/attachments/7"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(header().string("Content-Disposition", containsString("report.pdf")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().longValue("Content-Length", 7L))
                .andExpect(content().bytes("content".getBytes()));

        assertTrue(stream.closed, "전송이 끝나면 스트림이 닫혀야 한다");
    }

    @Test
    @DisplayName("버퍼(8KB)보다 큰 파일도 전량 그대로 전송된다")
    @WithMockUser
    void attachment_largeFile_streamedInFull() throws Exception {
        byte[] big = new byte[50_000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i % 251);
        }
        TrackingInputStream stream = stubDownload("big.bin", big, -1);

        mockMvc.perform(get("/notices/1/attachments/7"))
                .andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", 50_000L))
                .andExpect(content().bytes(big));

        assertTrue(stream.closed);
    }

    @Test
    @DisplayName("빈 파일은 Content-Length 0의 200 응답이다")
    @WithMockUser
    void attachment_emptyFile_ok() throws Exception {
        TrackingInputStream stream = stubDownload("empty.txt", new byte[0], -1);

        mockMvc.perform(get("/notices/1/attachments/7"))
                .andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", 0L));

        assertTrue(stream.closed);
    }

    @Test
    @DisplayName("공개 조건 재검증이 empty를 반환하면(비공개 notice·없는 첨부·타 notice 첨부 공통) 404이고 파일은 열지 않는다")
    @WithMockUser
    void attachment_findReturnsEmpty_notFoundWithoutOpen() throws Exception {
        given(publicNoticeService.findPublishedAttachment(1L, 7L)).willReturn(Optional.empty());

        mockMvc.perform(get("/notices/1/attachments/7"))
                .andExpect(status().isNotFound());

        verify(publicNoticeService, never()).openAttachment(any());
    }

    @Test
    @DisplayName("파일 open이 empty(실파일 없음)이면 404")
    @WithMockUser
    void attachment_openReturnsEmpty_notFound() throws Exception {
        given(publicNoticeService.findPublishedAttachment(1L, 7L)).willReturn(Optional.of(REF));
        given(publicNoticeService.openAttachment(REF)).willReturn(Optional.empty());

        mockMvc.perform(get("/notices/1/attachments/7"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("비숫자 id/attachmentId는 Service를 거치지 않고 404(JSON 아님)")
    @WithMockUser
    void attachment_nonNumericIds_notFoundWithoutServiceCall() throws Exception {
        mockMvc.perform(get("/notices/abc/attachments/1")).andExpect(status().isNotFound());
        mockMvc.perform(get("/notices/1/attachments/abc")).andExpect(status().isNotFound());
        mockMvc.perform(head("/notices/abc/attachments/1")).andExpect(status().isNotFound());

        Mockito.verifyNoInteractions(publicNoticeService);
    }

    @Test
    @DisplayName("다운로드 중 Service 예외는 HTML 500 + public/notice/error 뷰로 응답한다(JSON 아님)")
    @WithMockUser
    void attachment_serviceThrows_returnsHtml500NotJson() throws Exception {
        given(publicNoticeService.findPublishedAttachment(anyLong(), anyLong()))
                .willThrow(new RuntimeException("디스크 오류 시뮬레이션"));

        mockMvc.perform(get("/notices/1/attachments/7"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("public/notice/error"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    @DisplayName("파일 open 실패(IllegalStateException)도 HTML 500 + public/notice/error 뷰다")
    @WithMockUser
    void attachment_openThrows_returnsHtml500() throws Exception {
        given(publicNoticeService.findPublishedAttachment(1L, 7L)).willReturn(Optional.of(REF));
        given(publicNoticeService.openAttachment(REF)).willThrow(new IllegalStateException("디스크 오류"));

        mockMvc.perform(get("/notices/1/attachments/7"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("public/notice/error"));
    }

    @Test
    @DisplayName("첫 청크 읽기 실패(응답 무손대 구간)는 HTML 500이며 스트림을 닫는다")
    @WithMockUser
    void attachment_firstReadFails_html500AndClosed() throws Exception {
        TrackingInputStream stream = stubDownload("report.pdf", new byte[20_000], 0);

        mockMvc.perform(get("/notices/1/attachments/7"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("public/notice/error"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().doesNotExist("Content-Disposition"));

        assertTrue(stream.closed);
    }

    @Test
    @DisplayName("첫 청크(4KB, 컨테이너 버퍼 미만) 이후 읽기 실패(미커밋이지만 오염된 구간)는 reset 후 HTML 500이고 첨부 헤더·본문이 남지 않으며 스트림을 닫는다")
    @WithMockUser
    void attachment_midStreamFailureBeforeCommit_resetThenHtml500() throws Exception {
        byte[] data = new byte[20_000];
        java.util.Arrays.fill(data, (byte) 'A');
        TrackingInputStream stream = stubDownload("report.pdf", data, 4096);

        mockMvc.perform(get("/notices/1/attachments/7"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("public/notice/error"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(header().doesNotExist("Content-Length"))
                .andExpect(content().string(not(containsString("AAAA"))));

        assertTrue(stream.closed);
    }

    @Test
    @DisplayName("응답 커밋 후 읽기 실패는 HTML을 렌더링하지 않고 예외를 컨테이너로 전파하며 스트림을 닫는다 (실제 연결 중단은 실서버 테스트가 검증)")
    @WithMockUser
    void attachment_failureAfterCommit_propagatesWithoutView() throws Exception {
        byte[] data = new byte[40_000];
        java.util.Arrays.fill(data, (byte) 'A');
        // MockHttpServletResponse의 버퍼(4KB)를 넘겨 커밋된 뒤(2번째 청크 이후) 읽기가 실패하게 한다.
        TrackingInputStream stream = stubDownload("report.pdf", data, 12288);

        Exception thrown = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> mockMvc.perform(get("/notices/1/attachments/7")));

        Throwable root = thrown;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertTrue(root instanceof IOException, "원인 IOException이 그대로 전파돼야 한다: " + root);
        assertTrue(stream.closed);
    }

    @Test
    @DisplayName("GET에서 스트림 close가 실패해도(미커밋) 첨부 헤더·Content-Length가 남지 않은 HTML 500이다")
    @WithMockUser
    void attachment_closeFails_get_resetThenHtml500() throws Exception {
        TrackingInputStream stream = stubDownload("report.pdf", "SECRETBODY".getBytes(), -1);
        stream.failOnClose = true;

        mockMvc.perform(get("/notices/1/attachments/7"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("public/notice/error"))
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(header().doesNotExist("Content-Length"))
                .andExpect(content().string(not(containsString("SECRETBODY"))));

        assertTrue(stream.closed);
    }

    @Test
    @DisplayName("HEAD에서 스트림 close가 실패해도 첨부 헤더·Content-Length가 남지 않은 HTML 500이다")
    @WithMockUser
    void attachment_closeFails_head_resetThenHtml500() throws Exception {
        TrackingInputStream stream = stubDownload("report.pdf", "content".getBytes(), -1);
        stream.failOnClose = true;

        mockMvc.perform(head("/notices/1/attachments/7"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(header().doesNotExist("Content-Length"));

        assertTrue(stream.closed);
    }

    @Test
    @DisplayName("HEAD는 200·GET과 같은 Content-Length·빈 본문이며 본문 바이트를 읽지 않고 스트림을 닫는다")
    @WithMockUser
    void attachment_head_headersOnlyAndNoBodyRead() throws Exception {
        TrackingInputStream stream = stubDownload("report.pdf", "content".getBytes(), -1);

        mockMvc.perform(head("/notices/1/attachments/7"))
                .andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", 7L))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(new byte[0]));

        verify(publicNoticeService).findPublishedAttachment(1L, 7L);
        verify(publicNoticeService).openAttachment(REF);
        assertEquals(0, stream.readCalls, "HEAD는 본문 바이트를 읽지 않는다");
        assertTrue(stream.closed, "HEAD도 열었던 핸들을 곧바로 닫는다");
    }

    @Test
    @DisplayName("HEAD의 404도 GET과 동일하다(공개 조건 재검증 empty)")
    @WithMockUser
    void attachment_head_notFoundSameAsGet() throws Exception {
        given(publicNoticeService.findPublishedAttachment(1L, 7L)).willReturn(Optional.empty());

        mockMvc.perform(head("/notices/1/attachments/7"))
                .andExpect(status().isNotFound());

        verify(publicNoticeService, never()).openAttachment(any());
    }

    @Test
    @DisplayName("CR/LF 포함 파일명 다운로드는 정확히 200이며 별도 헤더가 생기지 않고 raw CR/LF가 노출되지 않는다")
    @WithMockUser
    void attachment_crlfFilename_encodedSafelyWithoutHeaderInjection() throws Exception {
        String crlfFilename = "report\r\nX-Evil: injected.txt";
        stubDownload(crlfFilename, "content".getBytes(), -1);

        mockMvc.perform(get("/notices/1/attachments/7"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Evil"))
                .andExpect(result -> {
                    String contentDisposition = result.getResponse().getHeader("Content-Disposition");
                    assertNotNull(contentDisposition);
                    assertFalse(contentDisposition.contains("\r\n"));
                });
    }
}
