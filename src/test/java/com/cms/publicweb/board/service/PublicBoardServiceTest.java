package com.cms.publicweb.board.service;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.domain.Post;
import com.cms.publicweb.board.dto.PublicPostSummary;
import org.springframework.test.util.ReflectionTestUtils;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
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
    private static final Long SITE_ID = 2L;

    /** 서비스가 쿼리에 넘기는 판정 시각 — 고정 Clock(KST 2026-10-09 12:00)의 값. 저장소 호출 인자로 단언해 "주입된 Clock으로 요청당 산출해 전달"을 고정한다. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 12, 0);

    @Mock
    BoardRepository boardRepository;

    @Mock
    PostRepository postRepository;

    @Mock
    PostAttachmentRepository postAttachmentRepository;

    @Mock
    FileStorage fileStorage;

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-10-09T03:00:00Z"), ZoneId.of("Asia/Seoul"));

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
        given(boardRepository.findPublicById(BOARD_ID, SITE_ID)).willReturn(Optional.of(board()));
    }

    // ===================== 목록 =====================

    @Test
    @DisplayName("공개 게시판이 아니면(없음·비공개·삭제) 목록은 empty이고 게시글 저장소를 조회하지 않는다")
    void list_boardNotPublic_emptyAndNoPostQuery() {
        given(boardRepository.findPublicById(BOARD_ID, SITE_ID)).willReturn(Optional.empty());

        assertTrue(service.getPublishedPosts(SITE_ID, BOARD_ID, 0, null).isEmpty());

        verifyNoInteractions(postRepository);
    }

    @Test
    @DisplayName("목록은 공개 게시글 조회 결과를 요약 DTO로 반환한다(저장소 필터에 위임)")
    void list_returnsPublishedSummaries() {
        publicBoardExists();
        given(postRepository.searchPublished(eq(BOARD_ID), eq(null), eq(NOW), any())).willReturn(new PageImpl<>(List.of(post(1L))));

        PublicBoardListResult result = service.getPublishedPosts(SITE_ID, BOARD_ID, 0, null).orElseThrow();

        assertEquals(1, result.page().getContent().size());
        assertEquals(1L, result.page().getContent().get(0).getId());
        assertEquals("공지사항", result.boardName());
    }

    @Test
    @DisplayName("음수 page는 0으로 보정된다")
    void list_negativePage_clampedToZero() {
        publicBoardExists();
        given(postRepository.searchPublished(any(), any(), any(), any())).willReturn(new PageImpl<>(List.of()));

        service.getPublishedPosts(SITE_ID, BOARD_ID, -5, null);

        assertEquals(0, capturedPageable(null).getPageNumber());
    }

    @Test
    @DisplayName("MAX_PAGE(1000) 초과 page는 0으로 보정된다 — 대형 OFFSET 방어")
    void list_pageBeyondMax_clampedToZero() {
        publicBoardExists();
        given(postRepository.searchPublished(any(), any(), any(), any())).willReturn(new PageImpl<>(List.of()));

        service.getPublishedPosts(SITE_ID, BOARD_ID, Integer.MAX_VALUE, null);

        assertEquals(0, capturedPageable(null).getPageNumber());
    }

    @Test
    @DisplayName("페이지 크기는 항상 10, 정렬은 createDate desc → id desc로 고정된다")
    void list_sizeAndSortFixed() {
        publicBoardExists();
        given(postRepository.searchPublished(any(), any(), any(), any())).willReturn(new PageImpl<>(List.of()));

        service.getPublishedPosts(SITE_ID, BOARD_ID, 0, null);

        Pageable pageable = capturedPageable(null);
        assertEquals(10, pageable.getPageSize());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("createDate").getDirection());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("id").getDirection());
    }

    // ----- 게시판 표시 설정(PLAN-board-definition.md PR 1) -----

    private void publicBoardExists(Board board) {
        given(boardRepository.findPublicById(BOARD_ID, SITE_ID)).willReturn(Optional.of(board));
    }

    private Board boardWithPageSize(int size) {
        return Board.builder().id(BOARD_ID).name("공지사항").publicYn(true).attachmentYn(true).deleted(false).listPageSize(size).build();
    }

    private int pageNumberSentToRepository(int size, int requestedPage) {
        publicBoardExists(boardWithPageSize(size));
        given(postRepository.searchPublished(any(), any(), any(), any())).willReturn(new PageImpl<>(List.of()));
        service.getPublishedPosts(SITE_ID, BOARD_ID, requestedPage, null);
        int sent = capturedPageable(null).getPageNumber();
        clearInvocations(postRepository);   // 한 시험에서 여러 번 부르므로 다음 검증이 이전 호출을 세지 않게 한다
        return sent;
    }

    @Test
    @DisplayName("목록 크기는 게시판 설정을 따른다")
    void list_pageSizeFollowsBoard() {
        publicBoardExists(boardWithPageSize(30));
        given(postRepository.searchPublished(any(), any(), any(), any())).willReturn(new PageImpl<>(List.of()));

        service.getPublishedPosts(SITE_ID, BOARD_ID, 0, null);

        assertEquals(30, capturedPageable(null).getPageSize());
    }

    @Test
    @DisplayName("허용 페이지는 OFFSET 10,000(포함) 기준 — 크기 10은 1000, 30은 333, 50은 200까지, 넘으면 0으로 보정")
    void list_pageLimitFollowsMaxOffset() {
        assertEquals(1000, pageNumberSentToRepository(10, 1000));
        assertEquals(333, pageNumberSentToRepository(30, 333));
        assertEquals(200, pageNumberSentToRepository(50, 200));
    }

    @Test
    @DisplayName("허용 페이지를 하나라도 넘으면 0으로 보정된다 — 크기 10은 1001, 30은 334, 50은 201")
    void list_pageOverLimitClamped() {
        assertEquals(0, pageNumberSentToRepository(10, 1001));
        assertEquals(0, pageNumberSentToRepository(30, 334));
        assertEquals(0, pageNumberSentToRepository(50, 201));
    }

    @Test
    @DisplayName("조회수 표시를 끈 게시판은 DTO에 조회수가 없고, 켠 게시판만 값을 싣는다")
    void list_viewCountFilteredByService() {
        Post viewed = post(1L);
        ReflectionTestUtils.setField(viewed, "viewCount", 42L);
        given(postRepository.searchPublished(any(), any(), any(), any())).willReturn(new PageImpl<>(List.of(viewed)));

        publicBoardExists();   // 기본: 조회수 표시 끔
        assertNull(service.getPublishedPosts(SITE_ID, BOARD_ID, 0, null).orElseThrow().page().getContent().get(0).getViewCount());

        publicBoardExists(Board.builder().id(BOARD_ID).name("공지사항").publicYn(true).attachmentYn(true).deleted(false)
                .showViewCount(true).build());
        assertEquals(42L, service.getPublishedPosts(SITE_ID, BOARD_ID, 0, null).orElseThrow().page().getContent().get(0).getViewCount());
    }

    @Test
    @DisplayName("새 글 배지: 기간 안의 글만 true, 기간 0이면 모두 false, create_date가 NULL인 글도 예외 없이 처리")
    void list_newBadge() {
        Post recent = post(1L);
        ReflectionTestUtils.setField(recent, "createDate", NOW.minusDays(1));
        Post old = post(2L);
        ReflectionTestUtils.setField(old, "createDate", NOW.minusDays(5));
        Post noDate = post(3L);
        ReflectionTestUtils.setField(noDate, "createDate", null);
        given(postRepository.searchPublished(any(), any(), any(), any())).willReturn(new PageImpl<>(List.of(recent, old, noDate)));

        publicBoardExists(Board.builder().id(BOARD_ID).name("공지사항").publicYn(true).attachmentYn(true).deleted(false)
                .newBadgeDays(3).build());
        List<PublicPostSummary> badged = service.getPublishedPosts(SITE_ID, BOARD_ID, 0, null).orElseThrow().page().getContent();
        assertEquals(List.of(true, false, false), badged.stream().map(PublicPostSummary::isNewBadge).toList());

        publicBoardExists();   // 기본 기간 0 = 끔
        List<PublicPostSummary> off = service.getPublishedPosts(SITE_ID, BOARD_ID, 0, null).orElseThrow().page().getContent();
        assertEquals(List.of(false, false, false), off.stream().map(PublicPostSummary::isNewBadge).toList());
    }

    @Test
    @DisplayName("설명과 작성자 표시명은 게시판 값을 그대로 결과에 싣는다 — 없으면 null")
    void list_descriptionAndAuthorLabel() {
        given(postRepository.searchPublished(any(), any(), any(), any())).willReturn(new PageImpl<>(List.of()));
        publicBoardExists(Board.builder().id(BOARD_ID).name("공지사항").publicYn(true).attachmentYn(true).deleted(false)
                .description("안내 <b>문구</b>").authorLabel("홍보팀").build());

        PublicBoardListResult result = service.getPublishedPosts(SITE_ID, BOARD_ID, 0, null).orElseThrow();

        assertEquals("안내 <b>문구</b>", result.description());   // 평문 — 이스케이프는 템플릿(th:text)이 한다
        assertEquals("홍보팀", result.authorLabel());
    }

    private Pageable capturedPageable(String keyword) {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(postRepository).searchPublished(eq(BOARD_ID), eq(keyword), eq(NOW), captor.capture());
        return captor.getValue();
    }

    // ===================== 제목 검색 =====================

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t　"})
    @DisplayName("키워드가 없거나 공백뿐이면 검색어 없이 조회하고 결과 키워드는 null이다")
    void list_blankKeyword_noKeyword(String keyword) {
        publicBoardExists();
        given(postRepository.searchPublished(any(), any(), any(), any())).willReturn(new PageImpl<>(List.of()));

        PublicBoardListResult result = service.getPublishedPosts(SITE_ID, BOARD_ID, 0, keyword).orElseThrow();

        assertNull(result.keyword());
        verify(postRepository).searchPublished(eq(BOARD_ID), eq(null), eq(NOW), any());
    }

    @Test
    @DisplayName("키워드는 앞뒤 공백을 제거해 검색으로 전달하고, 정규화한 값을 결과에 담는다")
    void list_keyword_strippedAndSearched() {
        publicBoardExists();
        given(postRepository.searchPublished(eq(BOARD_ID), eq("점검"), eq(NOW), any())).willReturn(new PageImpl<>(List.of(post(1L))));

        PublicBoardListResult result = service.getPublishedPosts(SITE_ID, BOARD_ID, 0, "  점검\t").orElseThrow();

        assertEquals("점검", result.keyword());
        assertEquals(1L, result.page().getContent().get(0).getId());
    }

    @Test
    @DisplayName("검색할 때도 page 보정·크기 10·정렬(createDate desc, id desc)이 같다")
    void list_keyword_sameRulesAsPlainList() {
        publicBoardExists();
        given(postRepository.searchPublished(eq(BOARD_ID), eq("점검"), eq(NOW), any())).willReturn(new PageImpl<>(List.of()));

        service.getPublishedPosts(SITE_ID, BOARD_ID, Integer.MAX_VALUE, "점검");

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
        given(postRepository.searchPublished(eq(BOARD_ID), eq(max), eq(NOW), any())).willReturn(new PageImpl<>(List.of()));

        service.getPublishedPosts(SITE_ID, BOARD_ID, 0, max);
        verify(postRepository).searchPublished(eq(BOARD_ID), eq(max), eq(NOW), any());

        PublicBoardListResult tooLong = service.getPublishedPosts(SITE_ID, BOARD_ID, 0, max + "가").orElseThrow();
        assertTrue(tooLong.page().isEmpty());
        assertEquals(0, tooLong.page().getTotalElements());
        assertEquals(max + "가", tooLong.keyword());
        verify(postRepository, never()).searchPublished(eq(BOARD_ID), eq(max + "가"), eq(NOW), any());
    }

    @Test
    @DisplayName("길이는 UTF-16 코드 유닛 기준이다 — 이모지 50개(100유닛)는 검색, 51개(102유닛)는 미조회 (HTML maxlength와 같은 단위)")
    void list_keywordLengthCountsUtf16Units() {
        publicBoardExists();
        String fifty = "😀".repeat(50);
        String fiftyOne = "😀".repeat(51);
        given(postRepository.searchPublished(eq(BOARD_ID), eq(fifty), eq(NOW), any())).willReturn(new PageImpl<>(List.of()));

        service.getPublishedPosts(SITE_ID, BOARD_ID, 0, fifty);
        service.getPublishedPosts(SITE_ID, BOARD_ID, 0, fiftyOne);

        verify(postRepository).searchPublished(eq(BOARD_ID), eq(fifty), eq(NOW), any());
        verify(postRepository, never()).searchPublished(eq(BOARD_ID), eq(fiftyOne), eq(NOW), any());
    }

    // ===================== 상세 =====================

    @Test
    @DisplayName("상세: 공개 게시판이 아니면 empty이고 첨부 저장소를 호출하지 않는다")
    void detail_boardNotPublic_empty() {
        given(boardRepository.findPublicById(BOARD_ID, SITE_ID)).willReturn(Optional.empty());

        assertTrue(service.findPublishedPost(SITE_ID, BOARD_ID, 1L).isEmpty());

        verifyNoInteractions(postAttachmentRepository);
    }

    @Test
    @DisplayName("상세: 미노출·삭제·다른 게시판 글이면 empty이고 첨부 저장소를 호출하지 않는다")
    void detail_postAbsent_emptyAndNoAttachmentQuery() {
        publicBoardExists();
        given(postRepository.findPublishedById(999L, BOARD_ID, NOW)).willReturn(Optional.empty());

        assertTrue(service.findPublishedPost(SITE_ID, BOARD_ID, 999L).isEmpty());

        verifyNoInteractions(postAttachmentRepository);
    }

    @Test
    @DisplayName("상세: 공개 글을 DTO로 반환하고 첨부를 id 오름차순으로 조립하며 첨부가 없으면 빈 리스트다(null 아님)")
    void detail_assemblesAttachmentsInIdOrder_orEmptyList() {
        publicBoardExists();
        given(postRepository.findPublishedById(1L, BOARD_ID, NOW)).willReturn(Optional.of(post(1L)));
        given(postAttachmentRepository.findByPostIdOrderByIdAsc(1L)).willReturn(List.of(attachment(5L, 1L), attachment(7L, 1L)));
        given(postRepository.findPublishedById(2L, BOARD_ID, NOW)).willReturn(Optional.of(post(2L)));
        given(postAttachmentRepository.findByPostIdOrderByIdAsc(2L)).willReturn(List.of());

        PublicPostDetail withAttachments = service.findPublishedPost(SITE_ID, BOARD_ID, 1L).orElseThrow();
        PublicPostDetail without = service.findPublishedPost(SITE_ID, BOARD_ID, 2L).orElseThrow();

        assertEquals("글 1", withAttachments.getTitle());
        assertEquals(List.of(5L, 7L), withAttachments.getAttachments().stream().map(PublicPostAttachment::getId).toList());
        assertTrue(without.getAttachments().isEmpty());
    }

    // ===================== findPublishedAttachment / openAttachment =====================

    @Test
    @DisplayName("공개 첨부 조회 성공 — 파일명·storageKey를 담은 참조를 반환하고 파일 스토리지는 건드리지 않는다")
    void findPublishedAttachment_success() {
        publicBoardExists();
        given(postRepository.findPublishedById(1L, BOARD_ID, NOW)).willReturn(Optional.of(post(1L)));
        given(postAttachmentRepository.findByIdAndPostId(5L, 1L)).willReturn(Optional.of(attachment(5L, 1L)));

        Optional<PublicPostAttachmentRef> result = service.findPublishedAttachment(SITE_ID, BOARD_ID, 1L, 5L);

        assertTrue(result.isPresent());
        assertEquals("file5.pdf", result.get().originalFilename());
        assertEquals("2026/08/03/5.pdf", result.get().storageKey());
        verifyNoInteractions(fileStorage);
    }

    @Test
    @DisplayName("TOCTOU: 게시글이 비공개/삭제면 empty를 반환하고 첨부 저장소·파일 스토리지는 호출하지 않는다")
    void findPublishedAttachment_postNotPublished_emptyAndNoFurtherCalls() {
        publicBoardExists();
        given(postRepository.findPublishedById(1L, BOARD_ID, NOW)).willReturn(Optional.empty());

        assertTrue(service.findPublishedAttachment(SITE_ID, BOARD_ID, 1L, 5L).isEmpty());

        verifyNoInteractions(postAttachmentRepository, fileStorage);
    }

    @Test
    @DisplayName("IDOR: 다른 게시글의 attachmentId면 empty를 반환하고 파일 스토리지는 호출하지 않는다")
    void findPublishedAttachment_wrongPost_emptyAndNoFileStorageCall() {
        publicBoardExists();
        given(postRepository.findPublishedById(1L, BOARD_ID, NOW)).willReturn(Optional.of(post(1L)));
        given(postAttachmentRepository.findByIdAndPostId(5L, 1L)).willReturn(Optional.empty());

        assertTrue(service.findPublishedAttachment(SITE_ID, BOARD_ID, 1L, 5L).isEmpty());

        verifyNoInteractions(fileStorage);
    }

    @Test
    @DisplayName("호출 순서: 게시판 → 게시글 재검증 → 첨부 조회(조회 단계), 그 뒤 별도 호출로 파일 open")
    void downloadCallOrder_verifyThenLookupThenOpen() {
        publicBoardExists();
        given(postRepository.findPublishedById(1L, BOARD_ID, NOW)).willReturn(Optional.of(post(1L)));
        given(postAttachmentRepository.findByIdAndPostId(5L, 1L)).willReturn(Optional.of(attachment(5L, 1L)));
        given(fileStorage.open("2026/08/03/5.pdf")).willReturn(storedStream("content"));

        PublicPostAttachmentRef ref = service.findPublishedAttachment(SITE_ID, BOARD_ID, 1L, 5L).orElseThrow();
        service.openAttachment(ref);

        InOrder order = inOrder(boardRepository, postRepository, postAttachmentRepository, fileStorage);
        order.verify(boardRepository).findPublicById(BOARD_ID, SITE_ID);
        order.verify(postRepository).findPublishedById(1L, BOARD_ID, NOW);
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
