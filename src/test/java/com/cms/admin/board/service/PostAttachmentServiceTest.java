package com.cms.admin.board.service;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.domain.PostAttachment;
import com.cms.admin.board.dto.response.PostAttachmentDownload;
import com.cms.admin.board.dto.response.PostAttachmentResponse;
import com.cms.admin.board.domain.Post;
import com.cms.admin.board.repository.BoardRepository;
import com.cms.admin.board.repository.PostAttachmentRepository;
import com.cms.admin.board.repository.PostRepository;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.common.storage.FileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 순수 Mockito 단위 테스트 — PostServiceTest와 동일한 패턴. 실제 커밋/롤백 시점에
 * TransactionSynchronization 콜백이 정확히 호출되는지는 이 테스트로 증명할 수 없어(실제 트랜잭션
 * 컨텍스트가 필요) PostAttachmentTransactionIntegrationTest가 별도로 검증한다 — 여기서는
 * "콜백이 등록됐는가"·"등록된 콜백을 수동 호출하면 기대한 부수효과가 나는가"까지만 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class PostAttachmentServiceTest {

    @Mock
    PostRepository postRepository;

    @Mock
    PostAttachmentRepository postAttachmentRepository;

    @Mock
    FileStorage fileStorage;

    static final Long BOARD_ID = 3L;

    @Mock
    BoardRepository boardRepository;

    /** UTC 2026-09-29 15:00 = KST 2026-09-30 00:00 — 시스템 시각·기본 시간대와 무관하게 저장 시각을 단언한다. */
    static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 9, 30, 0, 0);

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-09-29T15:00:00Z"), ZoneId.of("Asia/Seoul"));

    @InjectMocks
    PostAttachmentService postAttachmentService;

    @BeforeEach
    void initSynchronization() {
        // upload()/delete()는 TransactionSynchronizationManager.registerSynchronization()을
        // 호출한다 — 실제 트랜잭션 없이 이를 호출 가능하게 하려면 동기화를 수동 활성화해야 한다.
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization();
        }
        // 게시판은 살아 있고 첨부를 허용한다 — 게시글 락 조회 뒤의 게시판 확인과 목록·다운로드의 게시글 확인에 쓰인다(개별 시험이 필요하면 덮어쓴다)
        Board board = Board.builder().id(BOARD_ID).name("공지사항").publicYn(true).attachmentYn(true).deleted(false).build();
        lenient().when(boardRepository.findByIdAndDeletedFalse(BOARD_ID)).thenReturn(Optional.of(board));
        lenient().when(postRepository.findByIdAndBoardIdAndDeletedFalse(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(BOARD_ID)))
                .thenAnswer(invocation -> Optional.of(Post.builder().id(invocation.getArgument(0)).boardId(BOARD_ID).title("게시글")
                        .content("본문").useYn(true).deleted(false).authorId("admin01").build()));
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Post activePost() {
        return Post.builder()
                .id(1L).boardId(BOARD_ID).title("게시글").content("본문").useYn(true).deleted(false).authorId("admin01")
                .build();
    }

    private PostAttachment existingAttachment() {
        return PostAttachment.builder()
                .id(10L).postId(1L).originalFilename("file.pdf").contentType("application/pdf")
                .fileSize(100L).storageKey("2026/07/21/uuid.pdf").createDate(LocalDateTime.now())
                .build();
    }

    // ===================== upload =====================

    @Test
    @DisplayName("업로드 성공 — 게시글 락을 획득하고 storageKey로 저장한다")
    void upload_success_usesLock() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(activePost()));
        given(postAttachmentRepository.countByPostId(1L)).willReturn(0L);
        given(fileStorage.store(any(byte[].class), anyString())).willReturn("2026/07/21/uuid.pdf");
        given(postAttachmentRepository.save(any(PostAttachment.class))).willAnswer(inv -> {
            PostAttachment a = inv.getArgument(0);
            return PostAttachment.builder()
                    .id(10L).postId(a.getPostId()).originalFilename(a.getOriginalFilename())
                    .contentType(a.getContentType()).fileSize(a.getFileSize()).storageKey(a.getStorageKey())
                    .createDate(a.getCreateDate()).build();
        });

        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "content".getBytes());

        PostAttachmentResponse response = postAttachmentService.upload(BOARD_ID, 1L, file);

        assertEquals("report.pdf", response.getOriginalFilename());
        verify(postRepository).findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID);
        verify(fileStorage).store(any(byte[].class), anyString());

        // 첨부 createDate는 주입된 KST Clock에서 나온다
        ArgumentCaptor<PostAttachment> saved = ArgumentCaptor.forClass(PostAttachment.class);
        verify(postAttachmentRepository).save(saved.capture());
        assertEquals(FIXED_NOW, saved.getValue().getCreateDate());
    }

    @Test
    @DisplayName("존재하지 않는 게시글에 업로드 시 404")
    void upload_postNotFound() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(99L, BOARD_ID)).willReturn(Optional.empty());
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", "x".getBytes());

        assertThrows(ResourceNotFoundException.class, () -> postAttachmentService.upload(BOARD_ID, 99L, file));
        verify(fileStorage, never()).store(any(byte[].class), anyString());
    }

    @Test
    @DisplayName("빈 파일 업로드 시 400")
    void upload_emptyFile_invalidRequest() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(activePost()));
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[0]);

        assertThrows(InvalidRequestException.class, () -> postAttachmentService.upload(BOARD_ID, 1L, file));
    }

    @Test
    @DisplayName("허용되지 않는 확장자 업로드 시 400")
    void upload_disallowedExtension_invalidRequest() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(activePost()));
        MockMultipartFile file = new MockMultipartFile("file", "malware.exe", "application/octet-stream", "x".getBytes());

        assertThrows(InvalidRequestException.class, () -> postAttachmentService.upload(BOARD_ID, 1L, file));
        verify(fileStorage, never()).store(any(byte[].class), anyString());
    }

    // ===== 확장자 소문자화의 로케일 독립성 (adversarial-review/plan/PLAN-extension-locale-root.md 쟁점 3·4) =====

    /**
     * 기본 로케일을 잠시 바꿔 body를 실행하고 기본·DISPLAY·FORMAT 로케일을 모두 되돌린다.
     * JUnit 병렬 실행이 꺼져 있어(junit-platform.properties 없음) 메서드 안 복원으로 충분하다.
     */
    private static void withDefaultLocale(Locale locale, Runnable body) {
        Locale original = Locale.getDefault();
        Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(locale);
            body.run();
        } finally {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
        assertEquals(original, Locale.getDefault());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GIF", "ZIP"})
    @DisplayName("기본 로케일이 터키어여도 대문자 I가 든 확장자(GIF·ZIP)가 화이트리스트를 통과한다")
    void upload_uppercaseExtensionUnderTurkishLocale_allowed(String extension) {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(activePost()));
        given(postAttachmentRepository.countByPostId(1L)).willReturn(0L);
        given(fileStorage.store(any(byte[].class), anyString())).willReturn("2026/10/07/uuid");
        given(postAttachmentRepository.save(any(PostAttachment.class))).willAnswer(inv -> inv.getArgument(0));
        MockMultipartFile file = new MockMultipartFile("file", "FILE." + extension, "application/octet-stream", "x".getBytes());

        withDefaultLocale(Locale.forLanguageTag("tr-TR"),
                () -> assertDoesNotThrow(() -> postAttachmentService.upload(BOARD_ID, 1L, file)));

        verify(fileStorage).store(any(byte[].class), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GİF", "ZİP"})
    @DisplayName("기본 로케일이 터키어여도 점 있는 대문자 İ(U+0130) 확장자는 다른 로케일과 같이 거부된다")
    void upload_dottedCapitalIExtensionUnderTurkishLocale_rejected(String extension) {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(activePost()));
        MockMultipartFile file = new MockMultipartFile("file", "FILE." + extension, "application/octet-stream", "x".getBytes());

        withDefaultLocale(Locale.forLanguageTag("tr-TR"),
                () -> assertThrows(InvalidRequestException.class, () -> postAttachmentService.upload(BOARD_ID, 1L, file)));

        verify(fileStorage, never()).store(any(byte[].class), anyString());
    }

    @Test
    @DisplayName("확장자와 명백히 다른 카테고리의 Content-Type이면 400")
    void upload_mismatchedContentType_invalidRequest() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(activePost()));
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "text/html", "x".getBytes());

        assertThrows(InvalidRequestException.class, () -> postAttachmentService.upload(BOARD_ID, 1L, file));
    }

    @Test
    @DisplayName("Content-Type이 null이면 확장자 화이트리스트 통과만으로 허용되고, 저장 시 octet-stream으로 정규화된다")
    void upload_nullContentType_normalizedToOctetStream() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(activePost()));
        given(postAttachmentRepository.countByPostId(1L)).willReturn(0L);
        given(fileStorage.store(any(byte[].class), anyString())).willReturn("key");
        given(postAttachmentRepository.save(any(PostAttachment.class))).willAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", null, "x".getBytes());

        postAttachmentService.upload(BOARD_ID, 1L, file);

        ArgumentCaptor<PostAttachment> captor = ArgumentCaptor.forClass(PostAttachment.class);
        verify(postAttachmentRepository).save(captor.capture());
        assertEquals("application/octet-stream", captor.getValue().getContentType());
    }

    @Test
    @DisplayName("10MB 초과 파일 업로드 시 400")
    void upload_oversized_invalidRequest() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(activePost()));
        byte[] tooLarge = new byte[10 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", tooLarge);

        assertThrows(InvalidRequestException.class, () -> postAttachmentService.upload(BOARD_ID, 1L, file));
        verify(fileStorage, never()).store(any(byte[].class), anyString());
    }

    @Test
    @DisplayName("첨부 5개 초과 시 409 ConflictException")
    void upload_countExceeded_conflict() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(activePost()));
        given(postAttachmentRepository.countByPostId(1L)).willReturn(5L);
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", "x".getBytes());

        assertThrows(ConflictException.class, () -> postAttachmentService.upload(BOARD_ID, 1L, file));
        verify(fileStorage, never()).store(any(byte[].class), anyString());
    }

    @Test
    @DisplayName("파일명이 255자를 초과하면 400 — DB NOT NULL 길이 제약에 부딪혀 엉뚱한 409로 새는 것을 방지")
    void upload_filenameTooLong_invalidRequest() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(activePost()));
        String longName = "a".repeat(256) + ".pdf";
        MockMultipartFile file = new MockMultipartFile("file", longName, "application/pdf", "x".getBytes());

        assertThrows(InvalidRequestException.class, () -> postAttachmentService.upload(BOARD_ID, 1L, file));
        verify(fileStorage, never()).store(any(byte[].class), anyString());
    }

    @Test
    @DisplayName("저장(store) 실패 시 예외가 그대로 전파되고 DB에는 아무것도 저장되지 않는다")
    void upload_storeThrows_propagates() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(activePost()));
        given(postAttachmentRepository.countByPostId(1L)).willReturn(0L);
        given(fileStorage.store(any(byte[].class), anyString())).willThrow(new IllegalStateException("디스크 오류"));
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", "x".getBytes());

        assertThrows(IllegalStateException.class, () -> postAttachmentService.upload(BOARD_ID, 1L, file));
        verify(postAttachmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("CR/LF를 포함한 파일명도 업로드에 성공한다 (공개 다운로드 헤더 인젝션 회귀의 짝 — PLAN-public-notice-attachment.md)")
    void upload_crlfFilename_success() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(activePost()));
        given(postAttachmentRepository.countByPostId(1L)).willReturn(0L);
        given(fileStorage.store(any(byte[].class), anyString())).willReturn("2026/08/03/uuid.txt");
        given(postAttachmentRepository.save(any(PostAttachment.class))).willAnswer(inv -> inv.getArgument(0));

        String crlfFilename = "report\r\nX-Evil: injected.txt";
        MockMultipartFile file = new MockMultipartFile("file", crlfFilename, "text/plain", "content".getBytes());

        PostAttachmentResponse response = postAttachmentService.upload(BOARD_ID, 1L, file);

        assertEquals(crlfFilename, response.getOriginalFilename());
    }

    @Test
    @DisplayName("업로드 성공 시 롤백 정리 콜백이 등록되고, 커밋되지 않으면 파일이 삭제된다")
    void upload_registersRollbackCleanupCallback() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(activePost()));
        given(postAttachmentRepository.countByPostId(1L)).willReturn(0L);
        given(fileStorage.store(any(byte[].class), anyString())).willReturn("2026/07/21/uuid.pdf");
        given(postAttachmentRepository.save(any(PostAttachment.class))).willAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", "x".getBytes());
        postAttachmentService.upload(BOARD_ID, 1L, file);

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, syncs.size());

        // 커밋되지 않은 상태(롤백)를 시뮬레이션한다 — 파일이 정리되는지 확인.
        syncs.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(fileStorage).delete("2026/07/21/uuid.pdf");
    }

    // ===================== list =====================

    @Test
    @DisplayName("목록 조회 — 존재하지 않거나 삭제된 게시글이면 404")
    void list_postNotFound() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalse(99L, BOARD_ID)).willReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> postAttachmentService.list(BOARD_ID, 99L));
    }

    @Test
    @DisplayName("목록 조회 성공")
    void list_success() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalse(1L, BOARD_ID)).willReturn(Optional.of(activePost()));
        given(postAttachmentRepository.findByPostIdOrderByIdAsc(1L)).willReturn(List.of(existingAttachment()));

        List<PostAttachmentResponse> result = postAttachmentService.list(BOARD_ID, 1L);

        assertEquals(1, result.size());
        assertEquals("file.pdf", result.get(0).getOriginalFilename());
    }

    // ===================== download =====================

    @Test
    @DisplayName("다른 게시글의 attachmentId로 접근 시 404 (IDOR 방지)")
    void download_wrongPost_notFound() {
        given(postAttachmentRepository.findByIdAndPostId(10L, 2L)).willReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> postAttachmentService.download(BOARD_ID, 2L, 10L));
    }

    @Test
    @DisplayName("다운로드 성공 — storageKey로 파일을 읽어 원본 파일명과 함께 반환한다")
    void download_success() {
        given(postAttachmentRepository.findByIdAndPostId(10L, 1L)).willReturn(Optional.of(existingAttachment()));
        given(fileStorage.load("2026/07/21/uuid.pdf")).willReturn("content".getBytes());

        PostAttachmentDownload download = postAttachmentService.download(BOARD_ID, 1L, 10L);

        assertEquals("file.pdf", download.originalFilename());
        assertArrayEquals("content".getBytes(), download.content());
    }

    @Test
    @DisplayName("DB 행 조회 직후 동시 삭제로 실파일이 이미 사라진 경우 500이 아니라 404")
    void download_fileAlreadyDeletedConcurrently_notFound() {
        given(postAttachmentRepository.findByIdAndPostId(10L, 1L)).willReturn(Optional.of(existingAttachment()));
        given(fileStorage.load("2026/07/21/uuid.pdf"))
                .willThrow(new com.cms.common.storage.StorageFileNotFoundException("첨부파일을 찾을 수 없습니다: 2026/07/21/uuid.pdf", null));

        assertThrows(ResourceNotFoundException.class, () -> postAttachmentService.download(BOARD_ID, 1L, 10L));
    }

    @Test
    @DisplayName("파일 없음이 아닌 그 외 I/O 실패는 그대로 전파된다(500 유지)")
    void download_otherStorageFailure_propagates() {
        given(postAttachmentRepository.findByIdAndPostId(10L, 1L)).willReturn(Optional.of(existingAttachment()));
        given(fileStorage.load("2026/07/21/uuid.pdf")).willThrow(new IllegalStateException("디스크 읽기 오류"));

        assertThrows(IllegalStateException.class, () -> postAttachmentService.download(BOARD_ID, 1L, 10L));
    }

    // ===================== delete =====================

    @Test
    @DisplayName("존재하지 않는 게시글의 첨부 삭제 시 404")
    void delete_postNotFound() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(99L, BOARD_ID)).willReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> postAttachmentService.delete(BOARD_ID, 99L, 10L));
    }

    @Test
    @DisplayName("다른 게시글의 attachmentId로 삭제 시 404 (IDOR 방지)")
    void delete_wrongPost_notFound() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(activePost()));
        given(postAttachmentRepository.findByIdAndPostId(99L, 1L)).willReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> postAttachmentService.delete(BOARD_ID, 1L, 99L));
    }

    @Test
    @DisplayName("삭제 성공 — 게시글 락을 획득하고, 커밋 전에는 파일을 지우지 않고 커밋 후에만 지운다")
    void delete_success_registersAfterCommitCleanup() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(activePost()));
        PostAttachment attachment = existingAttachment();
        given(postAttachmentRepository.findByIdAndPostId(10L, 1L)).willReturn(Optional.of(attachment));

        PostAttachmentResponse response = postAttachmentService.delete(BOARD_ID, 1L, 10L);

        assertEquals(10L, response.getId());
        verify(postRepository).findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID);
        verify(postAttachmentRepository).delete(attachment);
        verify(fileStorage, never()).delete(anyString());

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, syncs.size());
        syncs.get(0).afterCommit();
        verify(fileStorage).delete("2026/07/21/uuid.pdf");
    }

    @Test
    @DisplayName("커밋 후 파일 삭제가 실패해도 예외를 전파하지 않는다(로그만 남김)")
    void delete_fileDeleteFailsAfterCommit_doesNotPropagate() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(activePost()));
        PostAttachment attachment = existingAttachment();
        given(postAttachmentRepository.findByIdAndPostId(10L, 1L)).willReturn(Optional.of(attachment));
        doThrow(new IllegalStateException("디스크 오류")).when(fileStorage).delete("2026/07/21/uuid.pdf");

        postAttachmentService.delete(BOARD_ID, 1L, 10L);

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertDoesNotThrow(() -> syncs.get(0).afterCommit());
    }

    // ===================== 게시판 규칙 (게시글 전용 — 공지에는 없던 것) =====================

    @Test
    @DisplayName("게시판이 첨부를 허용하지 않으면(attachmentYn=false) 새 업로드는 400이고 파일을 저장하지 않는다")
    void upload_attachmentNotAllowed_invalidRequest() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(activePost()));
        given(boardRepository.findByIdAndDeletedFalse(BOARD_ID)).willReturn(Optional.of(
                Board.builder().id(BOARD_ID).name("첨부 불가").publicYn(true).attachmentYn(false).deleted(false).build()));
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", "x".getBytes());

        InvalidRequestException e = assertThrows(InvalidRequestException.class, () -> postAttachmentService.upload(BOARD_ID, 1L, file));

        assertEquals("첨부를 허용하지 않는 게시판입니다.", e.getMessage());
        verify(fileStorage, never()).store(any(byte[].class), anyString());
    }

    @Test
    @DisplayName("삭제된 게시판의 게시글에는 업로드·삭제가 404다 — 게시글 락을 잡은 뒤 게시판 미삭제를 확인한다")
    void uploadAndDelete_boardDeleted_notFound() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(activePost()));
        given(boardRepository.findByIdAndDeletedFalse(BOARD_ID)).willReturn(Optional.empty());
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", "x".getBytes());

        assertThrows(ResourceNotFoundException.class, () -> postAttachmentService.upload(BOARD_ID, 1L, file));
        assertThrows(ResourceNotFoundException.class, () -> postAttachmentService.delete(BOARD_ID, 1L, 10L));
        verify(fileStorage, never()).store(any(byte[].class), anyString());
    }

    @Test
    @DisplayName("다른 게시판 경로로 접근한 게시글의 첨부는 어떤 동작이든 404다(IDOR) — 게시글 조회가 (postId, boardId) 조합이다")
    void otherBoardPath_notFound() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalse(1L, 99L)).willReturn(Optional.empty());
        given(boardRepository.findByIdAndDeletedFalse(99L)).willReturn(Optional.of(
                Board.builder().id(99L).name("다른 게시판").publicYn(true).attachmentYn(true).deleted(false).build()));
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, 99L)).willReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> postAttachmentService.list(99L, 1L));
        assertThrows(ResourceNotFoundException.class, () -> postAttachmentService.download(99L, 1L, 10L));
        assertThrows(ResourceNotFoundException.class, () -> postAttachmentService.delete(99L, 1L, 10L));
    }
}
