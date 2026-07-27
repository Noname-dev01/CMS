package com.cms.admin.notice.service;

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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
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

    @InjectMocks
    NoticeService noticeService;

    private Notice existingNotice() {
        LocalDateTime now = LocalDateTime.now();
        return Notice.builder()
                .id(1L)
                .title("기존 제목")
                .content("기존 본문")
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
                .content("공지 본문")
                .build();

        NoticeResponse response = noticeService.createNotice(request);

        assertEquals("admin01", response.getAuthorId());
        assertTrue(response.getUseYn());

        ArgumentCaptor<Notice> captor = ArgumentCaptor.forClass(Notice.class);
        verify(noticeRepository).save(captor.capture());
        assertFalse(captor.getValue().getDeleted());
    }

    @Test
    @DisplayName("author를 확인할 수 없으면 AccessDeniedException")
    void createNotice_authorNull_accessDenied() {
        given(adminSecurityService.getCurrentAdminUserId()).willReturn(null);

        NoticeCreateRequest request = NoticeCreateRequest.builder()
                .title("공지 제목")
                .content("공지 본문")
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
        assertEquals("기존 본문", response.getContent());
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

        assertEquals("기존 본문", response.getContent());
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
