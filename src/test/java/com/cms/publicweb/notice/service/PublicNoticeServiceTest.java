package com.cms.publicweb.notice.service;

import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.domain.NoticeAttachment;
import com.cms.admin.notice.repository.NoticeAttachmentRepository;
import com.cms.admin.notice.repository.NoticeRepository;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StorageFileNotFoundException;
import com.cms.common.storage.StoredFileStream;
import com.cms.publicweb.notice.dto.PublicNoticeAttachmentDownload;
import com.cms.publicweb.notice.dto.PublicNoticeAttachmentRef;
import com.cms.publicweb.notice.dto.PublicNoticeDetail;
import com.cms.publicweb.notice.dto.PublicNoticeListResult;
import com.cms.publicweb.notice.dto.PublicNoticeSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
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

        Page<PublicNoticeSummary> result = publicNoticeService.getPublishedNotices(0, null).page();

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

        publicNoticeService.getPublishedNotices(-5, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(noticeRepository).findByDeletedFalseAndUseYnTrue(captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
    }

    @Test
    @DisplayName("MAX_PAGE(1000) 초과 page는 0으로 보정된다 — 인덱스 없는 테이블의 대형 OFFSET 방어")
    void getPublishedNotices_pageBeyondMax_clampedToZero() {
        setUp();
        given(noticeRepository.findByDeletedFalseAndUseYnTrue(any())).willReturn(new PageImpl<>(List.of()));

        publicNoticeService.getPublishedNotices(Integer.MAX_VALUE, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(noticeRepository).findByDeletedFalseAndUseYnTrue(captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
    }

    @Test
    @DisplayName("페이지 크기는 항상 10으로 고정된다")
    void getPublishedNotices_pageSizeFixedToTen() {
        setUp();
        given(noticeRepository.findByDeletedFalseAndUseYnTrue(any())).willReturn(new PageImpl<>(List.of()));

        publicNoticeService.getPublishedNotices(0, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(noticeRepository).findByDeletedFalseAndUseYnTrue(captor.capture());
        assertEquals(10, captor.getValue().getPageSize());
    }

    @Test
    @DisplayName("정렬은 createDate desc, id desc로 고정된다")
    void getPublishedNotices_sortFixed() {
        setUp();
        given(noticeRepository.findByDeletedFalseAndUseYnTrue(any())).willReturn(new PageImpl<>(List.of()));

        publicNoticeService.getPublishedNotices(0, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(noticeRepository).findByDeletedFalseAndUseYnTrue(captor.capture());
        Sort sort = captor.getValue().getSort();
        assertEquals(Sort.Direction.DESC, sort.getOrderFor("createDate").getDirection());
        assertEquals(Sort.Direction.DESC, sort.getOrderFor("id").getDirection());
    }

    // ===================== getPublishedNotices: 제목 검색 (PLAN-public-notice-search.md 쟁점 3·7) =====================

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t　"})
    @DisplayName("키워드가 없거나 공백뿐이면 기존 목록 경로를 쓰고 검색 메서드는 호출하지 않는다")
    void getPublishedNotices_blankKeyword_usesDefaultList(String keyword) {
        setUp();
        given(noticeRepository.findByDeletedFalseAndUseYnTrue(any())).willReturn(new PageImpl<>(List.of()));

        PublicNoticeListResult result = publicNoticeService.getPublishedNotices(0, keyword);

        assertNull(result.keyword());
        verify(noticeRepository).findByDeletedFalseAndUseYnTrue(any());
        verify(noticeRepository, never()).searchPublishedByTitle(any(), any());
    }

    @Test
    @DisplayName("키워드는 앞뒤 공백을 제거해 검색 메서드로 전달하고, 정규화한 값을 결과에 담는다")
    void getPublishedNotices_keyword_strippedAndSearched() {
        setUp();
        given(noticeRepository.searchPublishedByTitle(eq("점검"), any())).willReturn(new PageImpl<>(List.of(notice(1L))));

        PublicNoticeListResult result = publicNoticeService.getPublishedNotices(0, "  점검\t");

        assertEquals("점검", result.keyword());
        assertEquals(1L, result.page().getContent().get(0).getId());
        verify(noticeRepository, never()).findByDeletedFalseAndUseYnTrue(any());
    }

    @Test
    @DisplayName("검색할 때도 page 보정·크기 10·정렬(createDate desc, id desc)이 같다")
    void getPublishedNotices_keyword_sameRulesAsList() {
        setUp();
        given(noticeRepository.searchPublishedByTitle(eq("점검"), any())).willReturn(new PageImpl<>(List.of()));

        publicNoticeService.getPublishedNotices(Integer.MAX_VALUE, "점검");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(noticeRepository).searchPublishedByTitle(eq("점검"), captor.capture());
        Pageable pageable = captor.getValue();
        assertEquals(0, pageable.getPageNumber());
        assertEquals(10, pageable.getPageSize());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("createDate").getDirection());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("id").getDirection());
    }

    @Test
    @DisplayName("100 코드 유닛 키워드는 검색하고, 101 코드 유닛은 DB를 조회하지 않고 빈 페이지를 돌려준다")
    void getPublishedNotices_keywordLengthBoundary() {
        setUp();
        String max = "가".repeat(100);
        given(noticeRepository.searchPublishedByTitle(eq(max), any())).willReturn(new PageImpl<>(List.of()));

        publicNoticeService.getPublishedNotices(0, max);
        verify(noticeRepository).searchPublishedByTitle(eq(max), any());

        PublicNoticeListResult tooLong = publicNoticeService.getPublishedNotices(0, max + "가");
        assertTrue(tooLong.page().isEmpty());
        assertEquals(0, tooLong.page().getTotalElements());
        assertEquals(max + "가", tooLong.keyword());
        verify(noticeRepository, never()).searchPublishedByTitle(eq(max + "가"), any());
    }

    @Test
    @DisplayName("길이는 UTF-16 코드 유닛 기준이다 — 이모지 50개(100유닛)는 검색, 51개(102유닛)는 미조회 (HTML maxlength와 같은 단위)")
    void getPublishedNotices_keywordLengthCountsUtf16Units() {
        setUp();
        String emoji = "😀"; // 😀 — 코드포인트 1개, 코드 유닛 2개
        String fifty = emoji.repeat(50);
        String fiftyOne = emoji.repeat(51);
        given(noticeRepository.searchPublishedByTitle(eq(fifty), any())).willReturn(new PageImpl<>(List.of()));

        publicNoticeService.getPublishedNotices(0, fifty);
        publicNoticeService.getPublishedNotices(0, fiftyOne);

        verify(noticeRepository).searchPublishedByTitle(eq(fifty), any());
        verify(noticeRepository, never()).searchPublishedByTitle(eq(fiftyOne), any());
    }

    // ===================== findPublishedAttachment / openAttachment =====================

    @Test
    @DisplayName("공개 첨부 조회 성공 — 파일명·storageKey를 담은 참조를 반환하고 파일 스토리지는 건드리지 않는다")
    void findPublishedAttachment_success() {
        setUp();
        given(noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(1L)).willReturn(Optional.of(notice(1L)));
        given(noticeAttachmentRepository.findByIdAndNoticeId(5L, 1L)).willReturn(Optional.of(attachment(5L, 1L)));

        Optional<PublicNoticeAttachmentRef> result = publicNoticeService.findPublishedAttachment(1L, 5L);

        assertTrue(result.isPresent());
        assertEquals("file5.pdf", result.get().originalFilename());
        assertEquals("2026/08/03/5.pdf", result.get().storageKey());
        verifyNoInteractions(fileStorage);
    }

    @Test
    @DisplayName("TOCTOU: notice가 비공개/삭제면 empty를 반환하고 첨부 Repository·FileStorage는 호출하지 않는다")
    void findPublishedAttachment_noticeNotPublished_emptyAndNoFurtherCalls() {
        setUp();
        given(noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(1L)).willReturn(Optional.empty());

        Optional<PublicNoticeAttachmentRef> result = publicNoticeService.findPublishedAttachment(1L, 5L);

        assertTrue(result.isEmpty());
        verifyNoInteractions(noticeAttachmentRepository, fileStorage);
    }

    @Test
    @DisplayName("IDOR: 다른 notice의 attachmentId면 empty를 반환하고 FileStorage는 호출하지 않는다")
    void findPublishedAttachment_wrongNotice_emptyAndNoFileStorageCall() {
        setUp();
        given(noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(1L)).willReturn(Optional.of(notice(1L)));
        given(noticeAttachmentRepository.findByIdAndNoticeId(5L, 1L)).willReturn(Optional.empty());

        Optional<PublicNoticeAttachmentRef> result = publicNoticeService.findPublishedAttachment(1L, 5L);

        assertTrue(result.isEmpty());
        verifyNoInteractions(fileStorage);
    }

    @Test
    @DisplayName("호출 순서: notice 재검증 → 첨부 조회(조회 단계), 그 뒤 별도 호출로 파일 open")
    void downloadCallOrder_verifyThenLookupThenOpen() {
        setUp();
        given(noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(1L)).willReturn(Optional.of(notice(1L)));
        given(noticeAttachmentRepository.findByIdAndNoticeId(5L, 1L)).willReturn(Optional.of(attachment(5L, 1L)));
        given(fileStorage.open("2026/08/03/5.pdf")).willReturn(storedStream("content"));

        PublicNoticeAttachmentRef ref = publicNoticeService.findPublishedAttachment(1L, 5L).orElseThrow();
        publicNoticeService.openAttachment(ref);

        InOrder order = inOrder(noticeRepository, noticeAttachmentRepository, fileStorage);
        order.verify(noticeRepository).findByIdAndDeletedFalseAndUseYnTrue(1L);
        order.verify(noticeAttachmentRepository).findByIdAndNoticeId(5L, 1L);
        order.verify(fileStorage).open("2026/08/03/5.pdf");
    }

    @Test
    @DisplayName("open 성공 — 파일명·크기·스트림 내용을 그대로 반환하고 스트림은 아직 열려 있다")
    void openAttachment_success() throws Exception {
        setUp();
        given(fileStorage.open("2026/08/03/5.pdf")).willReturn(storedStream("content"));

        Optional<PublicNoticeAttachmentDownload> result =
                publicNoticeService.openAttachment(new PublicNoticeAttachmentRef("file5.pdf", "2026/08/03/5.pdf"));

        assertTrue(result.isPresent());
        try (PublicNoticeAttachmentDownload download = result.get()) {
            assertEquals("file5.pdf", download.originalFilename());
            assertEquals(7L, download.contentLength());
            assertEquals("content", new String(download.content().readAllBytes()));
        }
    }

    @Test
    @DisplayName("StorageFileNotFoundException은 Optional.empty()로 흡수된다(404 매핑)")
    void openAttachment_storageFileNotFound_empty() {
        setUp();
        given(fileStorage.open("2026/08/03/5.pdf"))
                .willThrow(new StorageFileNotFoundException("파일 없음", null));

        Optional<PublicNoticeAttachmentDownload> result =
                publicNoticeService.openAttachment(new PublicNoticeAttachmentRef("file5.pdf", "2026/08/03/5.pdf"));

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("그 외 IllegalStateException은 그대로 전파된다(500 유지)")
    void openAttachment_otherStorageFailure_propagates() {
        setUp();
        given(fileStorage.open("2026/08/03/5.pdf")).willThrow(new IllegalStateException("디스크 오류"));

        assertThrows(IllegalStateException.class,
                () -> publicNoticeService.openAttachment(new PublicNoticeAttachmentRef("file5.pdf", "2026/08/03/5.pdf")));
    }

    private StoredFileStream storedStream(String content) {
        byte[] bytes = content.getBytes();
        return new StoredFileStream(new ByteArrayInputStream(bytes), bytes.length);
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
