package com.cms.publicweb.notice.service;

import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.repository.NoticeRepository;
import com.cms.publicweb.notice.dto.PublicNoticeDetail;
import com.cms.publicweb.notice.dto.PublicNoticeSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PublicNoticeServiceTest {

    @Mock
    NoticeRepository noticeRepository;

    PublicNoticeService publicNoticeService;

    private Notice notice(Long id) {
        LocalDateTime now = LocalDateTime.now();
        return Notice.builder()
                .id(id).title("공지 " + id).content("본문").useYn(true).deleted(false)
                .authorId("admin01").createDate(now).updateDate(now).build();
    }

    private void setUp() {
        publicNoticeService = new PublicNoticeService(noticeRepository);
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
    @DisplayName("findPublishedNotice는 노출·미삭제 공지를 PublicNoticeDetail로 반환한다")
    void findPublishedNotice_presentReturnsDetail() {
        setUp();
        given(noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(1L)).willReturn(Optional.of(notice(1L)));

        Optional<PublicNoticeDetail> result = publicNoticeService.findPublishedNotice(1L);

        assertTrue(result.isPresent());
        assertEquals("공지 1", result.get().getTitle());
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
}
