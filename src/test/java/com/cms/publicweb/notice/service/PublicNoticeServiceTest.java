package com.cms.publicweb.notice.service;

import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.domain.NoticeAttachment;
import com.cms.admin.notice.repository.NoticeAttachmentRepository;
import com.cms.admin.notice.repository.NoticeRepository;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StorageFileNotFoundException;
import com.cms.publicweb.notice.dto.PublicNoticeAttachmentDownload;
import com.cms.publicweb.notice.dto.PublicNoticeDetail;
import com.cms.publicweb.notice.dto.PublicNoticeSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PublicNoticeServiceTest {

    @Mock
    NoticeRepository noticeRepository;

    @Mock
    NoticeAttachmentRepository noticeAttachmentRepository;

    @Mock
    FileStorage fileStorage;

    PublicNoticeService publicNoticeService;

    private Notice notice(Long id) {
        LocalDateTime now = LocalDateTime.now();
        return Notice.builder()
                .id(id).title("공지 " + id).content("본문").useYn(true).deleted(false)
                .authorId("admin01").createDate(now).updateDate(now).build();
    }

    private NoticeAttachment attachment(Long id, Long noticeId) {
        return NoticeAttachment.builder()
                .id(id).noticeId(noticeId).originalFilename("file" + id + ".pdf")
                .contentType("application/pdf").fileSize(100L)
                .storageKey("2026/08/03/" + id + ".pdf").createDate(LocalDateTime.now())
                .build();
    }

    private void setUp() {
        publicNoticeService = new PublicNoticeService(noticeRepository, noticeAttachmentRepository, fileStorage);
    }

    @Test
    @DisplayName("getPublishedNotices는 노출·미삭제 공지만 조회 결과로 반환한다 (Repository 필터에 위임)")
    void getPublishedNotices_returnsOnlyPublished() {
        setUp();
        given(noticeRepository.findByDeletedFalseAndUseYnTrue(any()))
                .willReturn(new PageImpl<>(List.of(notice(1L))));

        Page<PublicNoticeSummary> result = publicNoticeService.getPublishedNotices(0);

        assertEquals(1, result.getContent().size());
        assertEquals(1L, result.getContent().get(0).getId());
    }

    @Test
    @DisplayName("findPublishedNotice는 비노출·삭제 공지에 대해 Optional.empty()를 반환한다 (Repository가 이미 필터링)")
    void findPublishedNotice_absentReturnsEmpty() {
        setUp();
        given(noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(anyLong())).willReturn(Optional.empty());

        Optional<PublicNoticeDetail> result = publicNoticeService.findPublishedNotice(999L);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("findPublishedNotice는 비공개 공지면 첨부 Repository를 호출하지 않는다")
    void findPublishedNotice_absent_doesNotQueryAttachments() {
        setUp();
        given(noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(anyLong())).willReturn(Optional.empty());

        publicNoticeService.findPublishedNotice(999L);

        verifyNoInteractions(noticeAttachmentRepository);
    }

    @Test
    @DisplayName("findPublishedNotice는 노출·미삭제 공지를 PublicNoticeDetail로 반환한다")
    void findPublishedNotice_presentReturnsDetail() {
        setUp();
        given(noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(1L)).willReturn(Optional.of(notice(1L)));
        given(noticeAttachmentRepository.findByNoticeIdOrderByIdAsc(1L)).willReturn(List.of());

        Optional<PublicNoticeDetail> result = publicNoticeService.findPublishedNotice(1L);

        assertTrue(result.isPresent());
        assertEquals("공지 1", result.get().getTitle());
    }

    @Test
    @DisplayName("findPublishedNotice는 첨부를 id 오름차순으로 DTO에 조립한다")
    void findPublishedNotice_assemblesAttachmentsInIdOrder() {
        setUp();
        given(noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(1L)).willReturn(Optional.of(notice(1L)));
        given(noticeAttachmentRepository.findByNoticeIdOrderByIdAsc(1L))
                .willReturn(List.of(attachment(5L, 1L), attachment(7L, 1L)));

        Optional<PublicNoticeDetail> result = publicNoticeService.findPublishedNotice(1L);

        assertEquals(2, result.get().getAttachments().size());
        assertEquals(5L, result.get().getAttachments().get(0).getId());
        assertEquals(7L, result.get().getAttachments().get(1).getId());
    }

    @Test
    @DisplayName("첨부가 0건이면 attachments가 빈 리스트다 (null 아님)")
    void findPublishedNotice_noAttachments_emptyListNotNull() {
        setUp();
        given(noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(1L)).willReturn(Optional.of(notice(1L)));
        given(noticeAttachmentRepository.findByNoticeIdOrderByIdAsc(1L)).willReturn(List.of());

        Optional<PublicNoticeDetail> result = publicNoticeService.findPublishedNotice(1L);

        assertTrue(result.get().getAttachments().isEmpty());
    }

    @Test
    @DisplayName("음수 page는 0으로 보정된다")
    void getPublishedNotices_negativePage_clampedToZero() {
        setUp();
        given(noticeRepository.findByDeletedFalseAndUseYnTrue(any())).willReturn(new PageImpl<>(List.of()));

        publicNoticeService.getPublishedNotices(-5);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(noticeRepository).findByDeletedFalseAndUseYnTrue(captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
    }

    @Test
    @DisplayName("MAX_PAGE(1000) 초과 page는 0으로 보정된다 — 인덱스 없는 테이블의 대형 OFFSET 방어")
    void getPublishedNotices_pageBeyondMax_clampedToZero() {
        setUp();
        given(noticeRepository.findByDeletedFalseAndUseYnTrue(any())).willReturn(new PageImpl<>(List.of()));

        publicNoticeService.getPublishedNotices(Integer.MAX_VALUE);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(noticeRepository).findByDeletedFalseAndUseYnTrue(captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
    }

    @Test
    @DisplayName("페이지 크기는 항상 10으로 고정된다")
    void getPublishedNotices_pageSizeFixedToTen() {
        setUp();
        given(noticeRepository.findByDeletedFalseAndUseYnTrue(any())).willReturn(new PageImpl<>(List.of()));

        publicNoticeService.getPublishedNotices(0);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(noticeRepository).findByDeletedFalseAndUseYnTrue(captor.capture());
        assertEquals(10, captor.getValue().getPageSize());
    }

    @Test
    @DisplayName("정렬은 createDate desc, id desc로 고정된다")
    void getPublishedNotices_sortFixed() {
        setUp();
        given(noticeRepository.findByDeletedFalseAndUseYnTrue(any())).willReturn(new PageImpl<>(List.of()));

        publicNoticeService.getPublishedNotices(0);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(noticeRepository).findByDeletedFalseAndUseYnTrue(captor.capture());
        Sort sort = captor.getValue().getSort();
        assertEquals(Sort.Direction.DESC, sort.getOrderFor("createDate").getDirection());
        assertEquals(Sort.Direction.DESC, sort.getOrderFor("id").getDirection());
    }

    // ===================== downloadPublishedAttachment =====================

    @Test
    @DisplayName("다운로드 성공 — 파일명·바이트를 그대로 반환한다")
    void downloadPublishedAttachment_success() {
        setUp();
        given(noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(1L)).willReturn(Optional.of(notice(1L)));
        given(noticeAttachmentRepository.findByIdAndNoticeId(5L, 1L)).willReturn(Optional.of(attachment(5L, 1L)));
        given(fileStorage.load("2026/08/03/5.pdf")).willReturn("content".getBytes());

        Optional<PublicNoticeAttachmentDownload> result = publicNoticeService.downloadPublishedAttachment(1L, 5L);

        assertTrue(result.isPresent());
        assertEquals("file5.pdf", result.get().originalFilename());
        assertEquals("content", new String(result.get().content()));
    }

    @Test
    @DisplayName("TOCTOU: notice가 비공개/삭제면 empty를 반환하고 첨부 Repository·FileStorage는 호출하지 않는다")
    void downloadPublishedAttachment_noticeNotPublished_emptyAndNoFurtherCalls() {
        setUp();
        given(noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(1L)).willReturn(Optional.empty());

        Optional<PublicNoticeAttachmentDownload> result = publicNoticeService.downloadPublishedAttachment(1L, 5L);

        assertTrue(result.isEmpty());
        verifyNoInteractions(noticeAttachmentRepository, fileStorage);
    }

    @Test
    @DisplayName("IDOR: 다른 notice의 attachmentId면 empty를 반환하고 FileStorage는 호출하지 않는다")
    void downloadPublishedAttachment_wrongNotice_emptyAndNoFileStorageCall() {
        setUp();
        given(noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(1L)).willReturn(Optional.of(notice(1L)));
        given(noticeAttachmentRepository.findByIdAndNoticeId(5L, 1L)).willReturn(Optional.empty());

        Optional<PublicNoticeAttachmentDownload> result = publicNoticeService.downloadPublishedAttachment(1L, 5L);

        assertTrue(result.isEmpty());
        verifyNoInteractions(fileStorage);
    }

    @Test
    @DisplayName("호출 순서: notice 재검증 → 첨부 조회 → 파일 로드 순서로 이루어진다")
    void downloadPublishedAttachment_callOrder() {
        setUp();
        given(noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(1L)).willReturn(Optional.of(notice(1L)));
        given(noticeAttachmentRepository.findByIdAndNoticeId(5L, 1L)).willReturn(Optional.of(attachment(5L, 1L)));
        given(fileStorage.load("2026/08/03/5.pdf")).willReturn("content".getBytes());

        publicNoticeService.downloadPublishedAttachment(1L, 5L);

        InOrder order = inOrder(noticeRepository, noticeAttachmentRepository, fileStorage);
        order.verify(noticeRepository).findByIdAndDeletedFalseAndUseYnTrue(1L);
        order.verify(noticeAttachmentRepository).findByIdAndNoticeId(5L, 1L);
        order.verify(fileStorage).load("2026/08/03/5.pdf");
    }

    @Test
    @DisplayName("StorageFileNotFoundException은 Optional.empty()로 흡수된다(404 매핑)")
    void downloadPublishedAttachment_storageFileNotFound_empty() {
        setUp();
        given(noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(1L)).willReturn(Optional.of(notice(1L)));
        given(noticeAttachmentRepository.findByIdAndNoticeId(5L, 1L)).willReturn(Optional.of(attachment(5L, 1L)));
        given(fileStorage.load("2026/08/03/5.pdf"))
                .willThrow(new StorageFileNotFoundException("파일 없음", null));

        Optional<PublicNoticeAttachmentDownload> result = publicNoticeService.downloadPublishedAttachment(1L, 5L);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("그 외 IllegalStateException은 그대로 전파된다(500 유지)")
    void downloadPublishedAttachment_otherStorageFailure_propagates() {
        setUp();
        given(noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(1L)).willReturn(Optional.of(notice(1L)));
        given(noticeAttachmentRepository.findByIdAndNoticeId(5L, 1L)).willReturn(Optional.of(attachment(5L, 1L)));
        given(fileStorage.load("2026/08/03/5.pdf")).willThrow(new IllegalStateException("디스크 오류"));

        assertThrows(IllegalStateException.class,
                () -> publicNoticeService.downloadPublishedAttachment(1L, 5L));
    }

    // ===================== fileSizeText 경계값 (PublicNoticeAttachment.from) =====================

    private String sizeTextOf(long bytes) {
        NoticeAttachment attachment = NoticeAttachment.builder()
                .id(1L).noticeId(1L).originalFilename("f").contentType("application/pdf")
                .fileSize(bytes).storageKey("k").createDate(LocalDateTime.now()).build();
        return com.cms.publicweb.notice.dto.PublicNoticeAttachment.from(attachment).getFileSizeText();
    }

    @Test
    @DisplayName("fileSizeText: 0B는 '0 B'")
    void fileSizeText_zero() {
        assertEquals("0 B", sizeTextOf(0));
    }

    @Test
    @DisplayName("fileSizeText: 1023B는 '1023 B' (KB 미만)")
    void fileSizeText_belowKb() {
        assertEquals("1023 B", sizeTextOf(1023));
    }

    @Test
    @DisplayName("fileSizeText: 1024B는 '1.0 KB'")
    void fileSizeText_exactlyOneKb() {
        assertEquals("1.0 KB", sizeTextOf(1024));
    }

    @Test
    @DisplayName("fileSizeText: 1280B(=1.25KB)는 HALF_UP 반올림으로 '1.3 KB'")
    void fileSizeText_halfUpRounding() {
        assertEquals("1.3 KB", sizeTextOf(1280));
    }

    @Test
    @DisplayName("fileSizeText: 1048575B(1MB-1B)는 '1024.0 KB' (다음 단위로 올리지 않음)")
    void fileSizeText_justBelowMb() {
        assertEquals("1024.0 KB", sizeTextOf(1048575));
    }

    @Test
    @DisplayName("fileSizeText: 1048576B는 단위 전환 경계로 '1.0 MB'")
    void fileSizeText_exactlyOneMb() {
        assertEquals("1.0 MB", sizeTextOf(1048576));
    }
}
