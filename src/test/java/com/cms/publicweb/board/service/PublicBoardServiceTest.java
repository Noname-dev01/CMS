package com.cms.publicweb.board.service;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.domain.Post;
import com.cms.admin.board.domain.PostAttachment;
import com.cms.admin.board.repository.BoardRepository;
import com.cms.admin.board.repository.PostAttachmentRepository;
import com.cms.admin.board.repository.PostRepository;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StorageFileNotFoundException;
import com.cms.common.storage.StoredFileStream;
import com.cms.publicweb.board.dto.PublicBoardListResult;
import com.cms.publicweb.board.dto.PublicPostAttachment;
import com.cms.publicweb.board.dto.PublicPostAttachmentDownload;
import com.cms.publicweb.board.dto.PublicPostAttachmentRef;
import com.cms.publicweb.board.dto.PublicPostDetail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 공개 게시판 서비스 단위 시험. 공지가 공지 게시판으로 흡수되며(PLAN-notice-to-board.md) 옛 {@code PublicNoticeServiceTest}의 단위 케이스
 * (페이지 보정·크기·정렬·검색어 규칙·첨부 조회 순서·열기 실패 처리·크기 표기 경계)를 이 서비스 기준으로 옮겼다 — 같은 규칙이 공개 공지의 계약이다.
 */
@ExtendWith(MockitoExtension.class)
class PublicBoardServiceTest {

    private static final Long BOARD_ID = 7L;

    @Mock
    BoardRepository boardRepository;

    @Mock
    PostRepository postRepository;

    @Mock
    PostAttachmentRepository postAttachmentRepository;

    @Mock
    FileStorage fileStorage;

    @InjectMocks
    PublicBoardService service;

    private Board board() {
        return Board.builder().id(BOARD_ID).name("공지사항").publicYn(true).attachmentYn(true).deleted(false).build();
    }

    private Post post(Long id) {
        LocalDateTime now = LocalDateTime.now();
        return Post.builder()
                .id(id).boardId(BOARD_ID).title("글 " + id).content("본문").useYn(true).deleted(false)
                .authorId("admin01").createDate(now).updateDate(now).build();
    }

    private PostAttachment attachment(Long id, Long postId) {
        return PostAttachment.builder()
                .id(id).postId(postId).originalFilename("file" + id + ".pdf")
                .contentType("application/pdf").fileSize(100L)
                .storageKey("2026/08/03/" + id + ".pdf").createDate(LocalDateTime.now())
                .build();
    }

    private void publicBoardExists() {
        given(boardRepository.findByIdAndDeletedFalseAndPublicYnTrue(BOARD_ID)).willReturn(Optional.of(board()));
    }

    // ===================== 목록 =====================

    @Test
    @DisplayName("공개 게시판이 아니면(없음·비공개·삭제) 목록은 empty이고 게시글 저장소를 조회하지 않는다")
    void list_boardNotPublic_emptyAndNoPostQuery() {
        given(boardRepository.findByIdAndDeletedFalseAndPublicYnTrue(BOARD_ID)).willReturn(Optional.empty());

        assertTrue(service.getPublishedPosts(BOARD_ID, 0, null).isEmpty());

        verifyNoInteractions(postRepository);
    }

    @Test
    @DisplayName("목록은 공개 게시글 조회 결과를 요약 DTO로 반환한다(저장소 필터에 위임)")
    void list_returnsPublishedSummaries() {
        publicBoardExists();
        given(postRepository.searchPublished(eq(BOARD_ID), eq(null), any())).willReturn(new PageImpl<>(List.of(post(1L))));

        PublicBoardListResult result = service.getPublishedPosts(BOARD_ID, 0, null).orElseThrow();

        assertEquals(1, result.page().getContent().size());
        assertEquals(1L, result.page().getContent().get(0).getId());
        assertEquals("공지사항", result.boardName());
    }

    @Test
    @DisplayName("음수 page는 0으로 보정된다")
    void list_negativePage_clampedToZero() {
        publicBoardExists();
        given(postRepository.searchPublished(any(), any(), any())).willReturn(new PageImpl<>(List.of()));

        service.getPublishedPosts(BOARD_ID, -5, null);

        assertEquals(0, capturedPageable(null).getPageNumber());
    }

    @Test
    @DisplayName("MAX_PAGE(1000) 초과 page는 0으로 보정된다 — 대형 OFFSET 방어")
    void list_pageBeyondMax_clampedToZero() {
        publicBoardExists();
        given(postRepository.searchPublished(any(), any(), any())).willReturn(new PageImpl<>(List.of()));

        service.getPublishedPosts(BOARD_ID, Integer.MAX_VALUE, null);

        assertEquals(0, capturedPageable(null).getPageNumber());
    }

    @Test
    @DisplayName("페이지 크기는 항상 10, 정렬은 createDate desc → id desc로 고정된다")
    void list_sizeAndSortFixed() {
        publicBoardExists();
        given(postRepository.searchPublished(any(), any(), any())).willReturn(new PageImpl<>(List.of()));

        service.getPublishedPosts(BOARD_ID, 0, null);

        Pageable pageable = capturedPageable(null);
        assertEquals(10, pageable.getPageSize());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("createDate").getDirection());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("id").getDirection());
    }

    private Pageable capturedPageable(String keyword) {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(postRepository).searchPublished(eq(BOARD_ID), eq(keyword), captor.capture());
        return captor.getValue();
    }

    // ===================== 제목 검색 =====================

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t　"})
    @DisplayName("키워드가 없거나 공백뿐이면 검색어 없이 조회하고 결과 키워드는 null이다")
    void list_blankKeyword_noKeyword(String keyword) {
        publicBoardExists();
        given(postRepository.searchPublished(any(), any(), any())).willReturn(new PageImpl<>(List.of()));

        PublicBoardListResult result = service.getPublishedPosts(BOARD_ID, 0, keyword).orElseThrow();

        assertNull(result.keyword());
        verify(postRepository).searchPublished(eq(BOARD_ID), eq(null), any());
    }

    @Test
    @DisplayName("키워드는 앞뒤 공백을 제거해 검색으로 전달하고, 정규화한 값을 결과에 담는다")
    void list_keyword_strippedAndSearched() {
        publicBoardExists();
        given(postRepository.searchPublished(eq(BOARD_ID), eq("점검"), any())).willReturn(new PageImpl<>(List.of(post(1L))));

        PublicBoardListResult result = service.getPublishedPosts(BOARD_ID, 0, "  점검\t").orElseThrow();

        assertEquals("점검", result.keyword());
        assertEquals(1L, result.page().getContent().get(0).getId());
    }

    @Test
    @DisplayName("검색할 때도 page 보정·크기 10·정렬(createDate desc, id desc)이 같다")
    void list_keyword_sameRulesAsPlainList() {
        publicBoardExists();
        given(postRepository.searchPublished(eq(BOARD_ID), eq("점검"), any())).willReturn(new PageImpl<>(List.of()));

        service.getPublishedPosts(BOARD_ID, Integer.MAX_VALUE, "점검");

        Pageable pageable = capturedPageable("점검");
        assertEquals(0, pageable.getPageNumber());
        assertEquals(10, pageable.getPageSize());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("createDate").getDirection());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("id").getDirection());
    }

    @Test
    @DisplayName("100 코드 유닛 키워드는 검색하고, 101 코드 유닛은 DB를 조회하지 않고 빈 페이지를 돌려준다")
    void list_keywordLengthBoundary() {
        publicBoardExists();
        String max = "가".repeat(100);
        given(postRepository.searchPublished(eq(BOARD_ID), eq(max), any())).willReturn(new PageImpl<>(List.of()));

        service.getPublishedPosts(BOARD_ID, 0, max);
        verify(postRepository).searchPublished(eq(BOARD_ID), eq(max), any());

        PublicBoardListResult tooLong = service.getPublishedPosts(BOARD_ID, 0, max + "가").orElseThrow();
        assertTrue(tooLong.page().isEmpty());
        assertEquals(0, tooLong.page().getTotalElements());
        assertEquals(max + "가", tooLong.keyword());
        verify(postRepository, never()).searchPublished(eq(BOARD_ID), eq(max + "가"), any());
    }

    @Test
    @DisplayName("길이는 UTF-16 코드 유닛 기준이다 — 이모지 50개(100유닛)는 검색, 51개(102유닛)는 미조회 (HTML maxlength와 같은 단위)")
    void list_keywordLengthCountsUtf16Units() {
        publicBoardExists();
        String fifty = "😀".repeat(50);
        String fiftyOne = "😀".repeat(51);
        given(postRepository.searchPublished(eq(BOARD_ID), eq(fifty), any())).willReturn(new PageImpl<>(List.of()));

        service.getPublishedPosts(BOARD_ID, 0, fifty);
        service.getPublishedPosts(BOARD_ID, 0, fiftyOne);

        verify(postRepository).searchPublished(eq(BOARD_ID), eq(fifty), any());
        verify(postRepository, never()).searchPublished(eq(BOARD_ID), eq(fiftyOne), any());
    }

    // ===================== 상세 =====================

    @Test
    @DisplayName("상세: 공개 게시판이 아니면 empty이고 첨부 저장소를 호출하지 않는다")
    void detail_boardNotPublic_empty() {
        given(boardRepository.findByIdAndDeletedFalseAndPublicYnTrue(BOARD_ID)).willReturn(Optional.empty());

        assertTrue(service.findPublishedPost(BOARD_ID, 1L).isEmpty());

        verifyNoInteractions(postAttachmentRepository);
    }

    @Test
    @DisplayName("상세: 미노출·삭제·다른 게시판 글이면 empty이고 첨부 저장소를 호출하지 않는다")
    void detail_postAbsent_emptyAndNoAttachmentQuery() {
        publicBoardExists();
        given(postRepository.findByIdAndBoardIdAndDeletedFalseAndUseYnTrue(999L, BOARD_ID)).willReturn(Optional.empty());

        assertTrue(service.findPublishedPost(BOARD_ID, 999L).isEmpty());

        verifyNoInteractions(postAttachmentRepository);
    }

    @Test
    @DisplayName("상세: 공개 글을 DTO로 반환하고 첨부를 id 오름차순으로 조립하며 첨부가 없으면 빈 리스트다(null 아님)")
    void detail_assemblesAttachmentsInIdOrder_orEmptyList() {
        publicBoardExists();
        given(postRepository.findByIdAndBoardIdAndDeletedFalseAndUseYnTrue(1L, BOARD_ID)).willReturn(Optional.of(post(1L)));
        given(postAttachmentRepository.findByPostIdOrderByIdAsc(1L)).willReturn(List.of(attachment(5L, 1L), attachment(7L, 1L)));
        given(postRepository.findByIdAndBoardIdAndDeletedFalseAndUseYnTrue(2L, BOARD_ID)).willReturn(Optional.of(post(2L)));
        given(postAttachmentRepository.findByPostIdOrderByIdAsc(2L)).willReturn(List.of());

        PublicPostDetail withAttachments = service.findPublishedPost(BOARD_ID, 1L).orElseThrow();
        PublicPostDetail without = service.findPublishedPost(BOARD_ID, 2L).orElseThrow();

        assertEquals("글 1", withAttachments.getTitle());
        assertEquals(List.of(5L, 7L), withAttachments.getAttachments().stream().map(PublicPostAttachment::getId).toList());
        assertTrue(without.getAttachments().isEmpty());
    }

    // ===================== findPublishedAttachment / openAttachment =====================

    @Test
    @DisplayName("공개 첨부 조회 성공 — 파일명·storageKey를 담은 참조를 반환하고 파일 스토리지는 건드리지 않는다")
    void findPublishedAttachment_success() {
        publicBoardExists();
        given(postRepository.findByIdAndBoardIdAndDeletedFalseAndUseYnTrue(1L, BOARD_ID)).willReturn(Optional.of(post(1L)));
        given(postAttachmentRepository.findByIdAndPostId(5L, 1L)).willReturn(Optional.of(attachment(5L, 1L)));

        Optional<PublicPostAttachmentRef> result = service.findPublishedAttachment(BOARD_ID, 1L, 5L);

        assertTrue(result.isPresent());
        assertEquals("file5.pdf", result.get().originalFilename());
        assertEquals("2026/08/03/5.pdf", result.get().storageKey());
        verifyNoInteractions(fileStorage);
    }

    @Test
    @DisplayName("TOCTOU: 게시글이 비공개/삭제면 empty를 반환하고 첨부 저장소·파일 스토리지는 호출하지 않는다")
    void findPublishedAttachment_postNotPublished_emptyAndNoFurtherCalls() {
        publicBoardExists();
        given(postRepository.findByIdAndBoardIdAndDeletedFalseAndUseYnTrue(1L, BOARD_ID)).willReturn(Optional.empty());

        assertTrue(service.findPublishedAttachment(BOARD_ID, 1L, 5L).isEmpty());

        verifyNoInteractions(postAttachmentRepository, fileStorage);
    }

    @Test
    @DisplayName("IDOR: 다른 게시글의 attachmentId면 empty를 반환하고 파일 스토리지는 호출하지 않는다")
    void findPublishedAttachment_wrongPost_emptyAndNoFileStorageCall() {
        publicBoardExists();
        given(postRepository.findByIdAndBoardIdAndDeletedFalseAndUseYnTrue(1L, BOARD_ID)).willReturn(Optional.of(post(1L)));
        given(postAttachmentRepository.findByIdAndPostId(5L, 1L)).willReturn(Optional.empty());

        assertTrue(service.findPublishedAttachment(BOARD_ID, 1L, 5L).isEmpty());

        verifyNoInteractions(fileStorage);
    }

    @Test
    @DisplayName("호출 순서: 게시판 → 게시글 재검증 → 첨부 조회(조회 단계), 그 뒤 별도 호출로 파일 open")
    void downloadCallOrder_verifyThenLookupThenOpen() {
        publicBoardExists();
        given(postRepository.findByIdAndBoardIdAndDeletedFalseAndUseYnTrue(1L, BOARD_ID)).willReturn(Optional.of(post(1L)));
        given(postAttachmentRepository.findByIdAndPostId(5L, 1L)).willReturn(Optional.of(attachment(5L, 1L)));
        given(fileStorage.open("2026/08/03/5.pdf")).willReturn(storedStream("content"));

        PublicPostAttachmentRef ref = service.findPublishedAttachment(BOARD_ID, 1L, 5L).orElseThrow();
        service.openAttachment(ref);

        InOrder order = inOrder(boardRepository, postRepository, postAttachmentRepository, fileStorage);
        order.verify(boardRepository).findByIdAndDeletedFalseAndPublicYnTrue(BOARD_ID);
        order.verify(postRepository).findByIdAndBoardIdAndDeletedFalseAndUseYnTrue(1L, BOARD_ID);
        order.verify(postAttachmentRepository).findByIdAndPostId(5L, 1L);
        order.verify(fileStorage).open("2026/08/03/5.pdf");
    }

    @Test
    @DisplayName("open 성공 — 파일명·크기·스트림 내용을 그대로 반환하고 스트림은 아직 열려 있다")
    void openAttachment_success() throws Exception {
        given(fileStorage.open("2026/08/03/5.pdf")).willReturn(storedStream("content"));

        Optional<PublicPostAttachmentDownload> result =
                service.openAttachment(new PublicPostAttachmentRef("file5.pdf", "2026/08/03/5.pdf"));

        assertTrue(result.isPresent());
        try (PublicPostAttachmentDownload download = result.get()) {
            assertEquals("file5.pdf", download.originalFilename());
            assertEquals(7L, download.contentLength());
            assertEquals("content", new String(download.content().readAllBytes()));
        }
    }

    @Test
    @DisplayName("StorageFileNotFoundException은 Optional.empty()로 흡수된다(404 매핑)")
    void openAttachment_storageFileNotFound_empty() {
        given(fileStorage.open("2026/08/03/5.pdf")).willThrow(new StorageFileNotFoundException("파일 없음", null));

        assertTrue(service.openAttachment(new PublicPostAttachmentRef("file5.pdf", "2026/08/03/5.pdf")).isEmpty());
    }

    @Test
    @DisplayName("그 외 IllegalStateException은 그대로 전파된다(500 유지)")
    void openAttachment_otherStorageFailure_propagates() {
        given(fileStorage.open("2026/08/03/5.pdf")).willThrow(new IllegalStateException("디스크 오류"));

        assertThrows(IllegalStateException.class,
                () -> service.openAttachment(new PublicPostAttachmentRef("file5.pdf", "2026/08/03/5.pdf")));
    }

    private StoredFileStream storedStream(String content) {
        byte[] bytes = content.getBytes();
        return new StoredFileStream(new ByteArrayInputStream(bytes), bytes.length);
    }

    // ===================== fileSizeText 경계값 (PublicPostAttachment.from) =====================

    private String sizeTextOf(long bytes) {
        PostAttachment attachment = PostAttachment.builder()
                .id(1L).postId(1L).originalFilename("f").contentType("application/pdf")
                .fileSize(bytes).storageKey("k").createDate(LocalDateTime.now()).build();
        return PublicPostAttachment.from(attachment).getFileSizeText();
    }

    @Test
    @DisplayName("fileSizeText 경계: 0B='0 B', 1023B='1023 B', 1024B='1.0 KB', 1280B=HALF_UP '1.3 KB', 1MB-1B='1024.0 KB', 1MB='1.0 MB'")
    void fileSizeText_boundaries() {
        assertEquals("0 B", sizeTextOf(0));
        assertEquals("1023 B", sizeTextOf(1023));
        assertEquals("1.0 KB", sizeTextOf(1024));
        assertEquals("1.3 KB", sizeTextOf(1280));
        assertEquals("1024.0 KB", sizeTextOf(1048575));
        assertEquals("1.0 MB", sizeTextOf(1048576));
    }
}
