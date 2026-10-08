package com.cms.admin.notice.service;

import com.cms.admin.contentimage.service.ContentImageService;
import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.dto.request.NoticeCreateRequest;
import com.cms.admin.notice.dto.request.NoticeSearchRequest;
import com.cms.admin.notice.dto.request.NoticeUpdateRequest;
import com.cms.admin.notice.dto.response.NoticePageResponse;
import com.cms.admin.notice.dto.response.NoticeResponse;
import com.cms.admin.notice.repository.NoticeAttachmentRepository;
import com.cms.admin.notice.repository.NoticeRepository;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.config.auth.AdminSecurityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NoticeServiceTest {

    @Mock
    NoticeRepository noticeRepository;

    @Mock
    NoticeAttachmentRepository noticeAttachmentRepository;

    @Mock
    AdminSecurityService adminSecurityService;

    @Mock
    ContentImageService contentImageService;

    /** UTC 2026-09-29 15:00 = KST 2026-09-30 00:00 — 시스템 시각·기본 시간대와 무관하게 저장 시각을 단언한다. */
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 9, 30, 0, 0);

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-09-29T15:00:00Z"), KST);

    @InjectMocks
    NoticeService noticeService;

    private Notice existingNotice() {
        LocalDateTime now = LocalDateTime.now();
        return Notice.builder()
                .id(1L)
                .title("기존 제목")
                .content("<p>기존 본문</p>")
                .useYn(true)
                .deleted(false)
                .authorId("admin01")
                .createDate(now)
                .updateDate(now)
                .build();
    }

    // ===================== createNotice =====================

    @Test
    @DisplayName("생성 성공 시 author가 자동 채워지고 useYn 누락 시 true로 기본화된다")
    void createNotice_success_authorAutoFilled_defaultUseYnTrue() {
        given(adminSecurityService.getCurrentAdminUserId()).willReturn("admin01");
        given(noticeRepository.save(any(Notice.class))).willAnswer(invocation -> {
            Notice n = invocation.getArgument(0);
            return Notice.builder()
                    .id(1L)
                    .title(n.getTitle())
                    .content(n.getContent())
                    .useYn(n.getUseYn())
                    .deleted(n.getDeleted())
                    .authorId(n.getAuthorId())
                    .createDate(n.getCreateDate())
                    .updateDate(n.getUpdateDate())
                    .build();
        });

        NoticeCreateRequest request = NoticeCreateRequest.builder()
                .title("공지 제목")
                .content("<p>공지 본문</p>").contentFormat("HTML")
                .build();

        NoticeResponse response = noticeService.createNotice(request);

        assertEquals("admin01", response.getAuthorId());
        assertTrue(response.getUseYn());

        ArgumentCaptor<Notice> captor = ArgumentCaptor.forClass(Notice.class);
        verify(noticeRepository).save(captor.capture());
        assertFalse(captor.getValue().getDeleted());
        // 저장 시각은 주입된 KST Clock에서 나오며 createDate == updateDate
        assertEquals(FIXED_NOW, captor.getValue().getCreateDate());
        assertEquals(FIXED_NOW, captor.getValue().getUpdateDate());
    }

    // ===================== 본문 HTML 계약 (PLAN-html-editor.md) =====================

    private void givenSaveEchoesWithId() {
        given(adminSecurityService.getCurrentAdminUserId()).willReturn("admin01");
        given(noticeRepository.save(any(Notice.class))).willAnswer(invocation -> {
            Notice n = invocation.getArgument(0);
            return Notice.builder().id(7L).title(n.getTitle()).content(n.getContent()).useYn(n.getUseYn())
                    .deleted(n.getDeleted()).authorId(n.getAuthorId()).createDate(n.getCreateDate())
                    .updateDate(n.getUpdateDate()).build();
        });
    }

    @Test
    @DisplayName("생성 시 본문은 sanitize되어 저장되고 이미지 참조가 공지 ID로 교체된다")
    void createNotice_sanitizesAndReplacesRefs() {
        givenSaveEchoesWithId();
        NoticeCreateRequest request = NoticeCreateRequest.builder()
                .title("제목")
                .content("<p onclick=\"x\">안녕<script>alert(1)</script><img src=\"/content-images/3\"><img src=\"https://evil.test/a.png\"></p>")
                .contentFormat("HTML")
                .build();

        noticeService.createNotice(request);

        ArgumentCaptor<Notice> captor = ArgumentCaptor.forClass(Notice.class);
        verify(noticeRepository).save(captor.capture());
        assertEquals("<p>안녕<img src=\"/content-images/3\"></p>", captor.getValue().getContent());
        verify(contentImageService).replaceRefs("NOTICE", 7L, "NOTICE", null, java.util.Set.of(3L));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "TEXT", "html"})
    @DisplayName("형식 표식이 HTML이 아니면 400(배포 전 평문 편집 화면 차단, R2-2)")
    void createNotice_missingFormat_invalidRequest(String format) {
        given(adminSecurityService.getCurrentAdminUserId()).willReturn("admin01");
        NoticeCreateRequest request = NoticeCreateRequest.builder()
                .title("제목").content("<h2>안내</h2>").contentFormat(format).build();

        InvalidRequestException e = assertThrows(InvalidRequestException.class, () -> noticeService.createNotice(request));
        assertTrue(e.getMessage().contains("새로고침"));
        verify(noticeRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"<p><br></p>", "<p>&nbsp;</p>", "<script>x</script>", "<p><img src=\"https://evil.test/a.png\"></p>"})
    @DisplayName("보이는 텍스트도 이미지도 없는 본문은 400")
    void createNotice_blankHtml_invalidRequest(String content) {
        given(adminSecurityService.getCurrentAdminUserId()).willReturn("admin01");
        NoticeCreateRequest request = NoticeCreateRequest.builder()
                .title("제목").content(content).contentFormat("HTML").build();

        assertThrows(InvalidRequestException.class, () -> noticeService.createNotice(request));
    }

    @Test
    @DisplayName("이미지만 있는 본문은 허용된다")
    void createNotice_imageOnly_allowed() {
        givenSaveEchoesWithId();
        NoticeCreateRequest request = NoticeCreateRequest.builder()
                .title("제목").content("<p><img src=\"/content-images/1\"></p>").contentFormat("HTML").build();

        noticeService.createNotice(request);

        verify(noticeRepository).save(any(Notice.class));
    }

    @Test
    @DisplayName("보이는 텍스트 10,001자는 허용, 10,002자는 400(공백도 정규화 없이 센다)")
    void createNotice_textLengthBoundary() {
        givenSaveEchoesWithId();
        // "<p>" + 10,000자 + "</p>" = 10,000 + 블록 종료 1 = 10,001
        NoticeCreateRequest atLimit = NoticeCreateRequest.builder()
                .title("제목").content("<p>" + "가".repeat(10_000) + "</p>").contentFormat("HTML").build();
        noticeService.createNotice(atLimit);

        NoticeCreateRequest overBySpaces = NoticeCreateRequest.builder()
                .title("제목").content("<p>A" + " ".repeat(9_999) + "B</p>").contentFormat("HTML").build();
        assertThrows(InvalidRequestException.class, () -> noticeService.createNotice(overBySpaces));
    }

    @Test
    @DisplayName("정리된 HTML이 200,000바이트를 넘으면 400")
    void createNotice_tooManyBytes_invalidRequest() {
        given(adminSecurityService.getCurrentAdminUserId()).willReturn("admin01");
        // 글자 수는 상한 안(1,000개 링크 × 1자)이지만 링크 마크업으로 바이트가 넘친다
        String links = "<a href=\"https://example.com/" + "x".repeat(150) + "\">a</a>";
        NoticeCreateRequest request = NoticeCreateRequest.builder()
                .title("제목").content("<p>" + links.repeat(1_000) + "</p>").contentFormat("HTML").build();

        InvalidRequestException e = assertThrows(InvalidRequestException.class, () -> noticeService.createNotice(request));
        assertTrue(e.getMessage().contains("서식"));
    }

    @Test
    @DisplayName("useYn만 바꾸는 PATCH는 형식 표식 없이 허용되고 참조를 건드리지 않는다")
    void updateNotice_useYnOnly_noFormatNeeded() {
        Notice target = existingNotice();
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(1L)).willReturn(Optional.of(target));

        noticeService.updateNotice(1L, NoticeUpdateRequest.builder().useYn(false).build());

        assertFalse(target.getUseYn());
        verify(contentImageService, never()).replaceRefs(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("본문 수정은 형식 표식이 필요하고, 성공 시 참조를 교체한다")
    void updateNotice_content_requiresFormatAndReplacesRefs() {
        Notice target = existingNotice();
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(1L)).willReturn(Optional.of(target));

        assertThrows(InvalidRequestException.class,
                () -> noticeService.updateNotice(1L, NoticeUpdateRequest.builder().content("<p>새 본문</p>").build()));

        noticeService.updateNotice(1L, NoticeUpdateRequest.builder()
                .content("<p>새 본문<img src=\"/content-images/9\"></p>").contentFormat("HTML").build());

        assertEquals("<p>새 본문<img src=\"/content-images/9\"></p>", target.getContent());
        verify(contentImageService).replaceRefs("NOTICE", 1L, "NOTICE", null, java.util.Set.of(9L));
    }

    @Test
    @DisplayName("응답 본문은 출력 시에도 sanitize된다(저장 경로 밖 데이터 방어)")
    void response_sanitizesStoredContent() {
        Notice tainted = Notice.builder().id(1L).title("t").content("<p>x<img src=x onerror=alert(1)></p><script>y</script>")
                .useYn(true).deleted(false).authorId("a").build();
        given(noticeRepository.findByIdAndDeletedFalse(1L)).willReturn(Optional.of(tainted));

        assertEquals("<p>x</p>", noticeService.getNotice(1L).getContent());
    }

    @Test
    @DisplayName("author를 확인할 수 없으면 AccessDeniedException")
    void createNotice_authorNull_accessDenied() {
        given(adminSecurityService.getCurrentAdminUserId()).willReturn(null);

        NoticeCreateRequest request = NoticeCreateRequest.builder()
                .title("공지 제목")
                .content("<p>공지 본문</p>").contentFormat("HTML")
                .build();

        assertThrows(AccessDeniedException.class, () -> noticeService.createNotice(request));
        verify(noticeRepository, never()).save(any());
    }

    // ===================== updateNotice =====================

    @Test
    @DisplayName("수정 성공 — null 필드는 기존값 유지, 락 조회 메서드를 사용한다")
    void updateNotice_success_partialUpdate_usesLockQuery() {
        Notice target = existingNotice();
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(1L)).willReturn(Optional.of(target));

        NoticeUpdateRequest request = NoticeUpdateRequest.builder().title("변경된 제목").build();

        NoticeResponse response = noticeService.updateNotice(1L, request);

        assertEquals("변경된 제목", response.getTitle());
        assertEquals("<p>기존 본문</p>", response.getContent());
        assertEquals(FIXED_NOW, target.getUpdateDate()); // 수정 시각은 주입된 KST Clock
        verify(noticeRepository).findByIdAndDeletedFalseForUpdate(1L);
    }

    @Test
    @DisplayName("값이 온 title이 공백이면 400 INVALID_REQUEST")
    void updateNotice_blankTitle_invalidRequest() {
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(1L)).willReturn(Optional.of(existingNotice()));

        NoticeUpdateRequest request = NoticeUpdateRequest.builder().title("   ").build();

        assertThrows(InvalidRequestException.class, () -> noticeService.updateNotice(1L, request));
    }

    @Test
    @DisplayName("title·content·useYn 전체가 null인 PATCH는 400 INVALID_REQUEST")
    void updateNotice_allFieldsNull_invalidRequest() {
        NoticeUpdateRequest request = NoticeUpdateRequest.builder().build();

        assertThrows(InvalidRequestException.class, () -> noticeService.updateNotice(1L, request));
        // 전체 null 검증은 락 조회보다 먼저 수행되어 불필요한 락 획득을 피한다.
        verify(noticeRepository, never()).findByIdAndDeletedFalseForUpdate(anyLong());
    }

    @Test
    @DisplayName("존재하지 않거나 이미 삭제된 공지 수정 시 404")
    void updateNotice_notFound() {
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(99L)).willReturn(Optional.empty());

        NoticeUpdateRequest request = NoticeUpdateRequest.builder().title("변경").build();

        assertThrows(ResourceNotFoundException.class, () -> noticeService.updateNotice(99L, request));
    }

    // ===================== deleteNotice =====================

    @Test
    @DisplayName("삭제 성공 — deleted=true로 전환되고 락 조회 메서드를 사용하며 응답을 반환한다")
    void deleteNotice_success_softDelete_usesLockQuery_returnsResponse() {
        Notice target = existingNotice();
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(1L)).willReturn(Optional.of(target));

        NoticeResponse response = noticeService.deleteNotice(1L);

        assertEquals(1L, response.getId());
        assertTrue(target.getDeleted());
        assertEquals(FIXED_NOW, target.getUpdateDate()); // 삭제 시각도 주입된 KST Clock
        verify(noticeRepository).findByIdAndDeletedFalseForUpdate(1L);
    }

    @Test
    @DisplayName("존재하지 않거나 이미 삭제된 공지 삭제 시 404")
    void deleteNotice_notFound() {
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(99L)).willReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> noticeService.deleteNotice(99L));
    }

    @Test
    @DisplayName("첨부파일이 남아있으면 409 ConflictException — 오펀 방지(쟁점 14)")
    void deleteNotice_hasAttachments_conflict() {
        Notice target = existingNotice();
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(1L)).willReturn(Optional.of(target));
        given(noticeAttachmentRepository.countByNoticeId(1L)).willReturn(2L);

        assertThrows(ConflictException.class, () -> noticeService.deleteNotice(1L));
        assertFalse(target.getDeleted());
    }

    // ===================== getNotice =====================

    @Test
    @DisplayName("존재하지 않는 공지 조회 시 404")
    void getNotice_notFound() {
        given(noticeRepository.findByIdAndDeletedFalse(99L)).willReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> noticeService.getNotice(99L));
    }

    @Test
    @DisplayName("공지 상세 조회 성공 시 본문을 포함해 반환한다")
    void getNotice_success() {
        given(noticeRepository.findByIdAndDeletedFalse(1L)).willReturn(Optional.of(existingNotice()));

        NoticeResponse response = noticeService.getNotice(1L);

        assertEquals("<p>기존 본문</p>", response.getContent());
    }

    // ===================== getNotices =====================

    @Test
    @DisplayName("목록 조회는 요약 DTO(본문 제외)로 매핑된다")
    void getNotices_summaryMapping() {
        Page<Notice> page = new PageImpl<>(List.of(existingNotice()), PageRequest.of(0, 20), 1);
        given(noticeRepository.searchNotices(any(), any())).willReturn(page);

        NoticePageResponse response = noticeService.getNotices(NoticeSearchRequest.builder().build(), PageRequest.of(0, 20));

        assertEquals(1, response.getContent().size());
        assertEquals("기존 제목", response.getContent().get(0).getTitle());
    }

    @Test
    @DisplayName("size가 100을 초과하면 100으로 clamp되어 Repository에 전달된다")
    void getNotices_sizeClamp_over100() {
        given(noticeRepository.searchNotices(any(), any()))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        Pageable requested = PageRequest.of(2, 200, Sort.by("title").ascending());
        noticeService.getNotices(NoticeSearchRequest.builder().build(), requested);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(noticeRepository).searchNotices(any(), captor.capture());

        Pageable effective = captor.getValue();
        assertEquals(100, effective.getPageSize());
        assertEquals(2, effective.getPageNumber());
        assertEquals(Sort.by("title").ascending(), effective.getSort());
    }

    @Test
    @DisplayName("size가 100 이하면 clamp 없이 원래 Pageable이 그대로 전달된다")
    void getNotices_sizeWithinLimit_unchanged() {
        given(noticeRepository.searchNotices(any(), any()))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        Pageable requested = PageRequest.of(0, 50);
        noticeService.getNotices(NoticeSearchRequest.builder().build(), requested);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(noticeRepository).searchNotices(any(), captor.capture());

        assertEquals(requested, captor.getValue());
    }
}
