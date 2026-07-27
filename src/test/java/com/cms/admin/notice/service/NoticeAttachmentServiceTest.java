package com.cms.admin.notice.service;

import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.domain.NoticeAttachment;
import com.cms.admin.notice.dto.response.NoticeAttachmentDownload;
import com.cms.admin.notice.dto.response.NoticeAttachmentResponse;
import com.cms.admin.notice.repository.NoticeAttachmentRepository;
import com.cms.admin.notice.repository.NoticeRepository;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.common.storage.FileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 순수 Mockito 단위 테스트 — NoticeServiceTest와 동일한 패턴. 실제 커밋/롤백 시점에
 * TransactionSynchronization 콜백이 정확히 호출되는지는 이 테스트로 증명할 수 없어(실제 트랜잭션
 * 컨텍스트가 필요) NoticeAttachmentTransactionIntegrationTest가 별도로 검증한다 — 여기서는
 * "콜백이 등록됐는가"·"등록된 콜백을 수동 호출하면 기대한 부수효과가 나는가"까지만 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class NoticeAttachmentServiceTest {

    @Mock
    NoticeRepository noticeRepository;

    @Mock
    NoticeAttachmentRepository noticeAttachmentRepository;

    @Mock
    FileStorage fileStorage;

    @InjectMocks
    NoticeAttachmentService noticeAttachmentService;

    @BeforeEach
    void initSynchronization() {
        // upload()/delete()는 TransactionSynchronizationManager.registerSynchronization()을
        // 호출한다 — 실제 트랜잭션 없이 이를 호출 가능하게 하려면 동기화를 수동 활성화해야 한다.
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization();
        }
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Notice activeNotice() {
        return Notice.builder()
                .id(1L).title("공지").content("본문").useYn(true).deleted(false).authorId("admin01")
                .build();
    }

    private NoticeAttachment existingAttachment() {
        return NoticeAttachment.builder()
                .id(10L).noticeId(1L).originalFilename("file.pdf").contentType("application/pdf")
                .fileSize(100L).storageKey("2026/07/21/uuid.pdf").createDate(LocalDateTime.now())
                .build();
    }

    // ===================== upload =====================

    @Test
    @DisplayName("업로드 성공 — notice 락을 획득하고 storageKey로 저장한다")
    void upload_success_usesLock() {
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(1L)).willReturn(Optional.of(activeNotice()));
        given(noticeAttachmentRepository.countByNoticeId(1L)).willReturn(0L);
        given(fileStorage.store(any(byte[].class), anyString())).willReturn("2026/07/21/uuid.pdf");
        given(noticeAttachmentRepository.save(any(NoticeAttachment.class))).willAnswer(inv -> {
            NoticeAttachment a = inv.getArgument(0);
            return NoticeAttachment.builder()
                    .id(10L).noticeId(a.getNoticeId()).originalFilename(a.getOriginalFilename())
                    .contentType(a.getContentType()).fileSize(a.getFileSize()).storageKey(a.getStorageKey())
                    .createDate(a.getCreateDate()).build();
        });

        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "content".getBytes());

        NoticeAttachmentResponse response = noticeAttachmentService.upload(1L, file);

        assertEquals("report.pdf", response.getOriginalFilename());
        verify(noticeRepository).findByIdAndDeletedFalseForUpdate(1L);
        verify(fileStorage).store(any(byte[].class), anyString());
    }

    @Test
    @DisplayName("존재하지 않는 공지에 업로드 시 404")
    void upload_noticeNotFound() {
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(99L)).willReturn(Optional.empty());
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", "x".getBytes());

        assertThrows(ResourceNotFoundException.class, () -> noticeAttachmentService.upload(99L, file));
        verify(fileStorage, never()).store(any(byte[].class), anyString());
    }

    @Test
    @DisplayName("빈 파일 업로드 시 400")
    void upload_emptyFile_invalidRequest() {
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(1L)).willReturn(Optional.of(activeNotice()));
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[0]);

        assertThrows(InvalidRequestException.class, () -> noticeAttachmentService.upload(1L, file));
    }

    @Test
    @DisplayName("허용되지 않는 확장자 업로드 시 400")
    void upload_disallowedExtension_invalidRequest() {
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(1L)).willReturn(Optional.of(activeNotice()));
        MockMultipartFile file = new MockMultipartFile("file", "malware.exe", "application/octet-stream", "x".getBytes());

        assertThrows(InvalidRequestException.class, () -> noticeAttachmentService.upload(1L, file));
        verify(fileStorage, never()).store(any(byte[].class), anyString());
    }

    @Test
    @DisplayName("확장자와 명백히 다른 카테고리의 Content-Type이면 400")
    void upload_mismatchedContentType_invalidRequest() {
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(1L)).willReturn(Optional.of(activeNotice()));
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "text/html", "x".getBytes());

        assertThrows(InvalidRequestException.class, () -> noticeAttachmentService.upload(1L, file));
    }

    @Test
    @DisplayName("Content-Type이 null이면 확장자 화이트리스트 통과만으로 허용되고, 저장 시 octet-stream으로 정규화된다")
    void upload_nullContentType_normalizedToOctetStream() {
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(1L)).willReturn(Optional.of(activeNotice()));
        given(noticeAttachmentRepository.countByNoticeId(1L)).willReturn(0L);
        given(fileStorage.store(any(byte[].class), anyString())).willReturn("key");
        given(noticeAttachmentRepository.save(any(NoticeAttachment.class))).willAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", null, "x".getBytes());

        noticeAttachmentService.upload(1L, file);

        ArgumentCaptor<NoticeAttachment> captor = ArgumentCaptor.forClass(NoticeAttachment.class);
        verify(noticeAttachmentRepository).save(captor.capture());
        assertEquals("application/octet-stream", captor.getValue().getContentType());
    }

    @Test
    @DisplayName("10MB 초과 파일 업로드 시 400")
    void upload_oversized_invalidRequest() {
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(1L)).willReturn(Optional.of(activeNotice()));
        byte[] tooLarge = new byte[10 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", tooLarge);

        assertThrows(InvalidRequestException.class, () -> noticeAttachmentService.upload(1L, file));
        verify(fileStorage, never()).store(any(byte[].class), anyString());
    }

    @Test
    @DisplayName("첨부 5개 초과 시 409 ConflictException")
    void upload_countExceeded_conflict() {
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(1L)).willReturn(Optional.of(activeNotice()));
        given(noticeAttachmentRepository.countByNoticeId(1L)).willReturn(5L);
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", "x".getBytes());

        assertThrows(ConflictException.class, () -> noticeAttachmentService.upload(1L, file));
        verify(fileStorage, never()).store(any(byte[].class), anyString());
    }

    @Test
    @DisplayName("파일명이 255자를 초과하면 400 — DB NOT NULL 길이 제약에 부딪혀 엉뚱한 409로 새는 것을 방지")
    void upload_filenameTooLong_invalidRequest() {
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(1L)).willReturn(Optional.of(activeNotice()));
        String longName = "a".repeat(256) + ".pdf";
        MockMultipartFile file = new MockMultipartFile("file", longName, "application/pdf", "x".getBytes());

        assertThrows(InvalidRequestException.class, () -> noticeAttachmentService.upload(1L, file));
        verify(fileStorage, never()).store(any(byte[].class), anyString());
    }

    @Test
    @DisplayName("저장(store) 실패 시 예외가 그대로 전파되고 DB에는 아무것도 저장되지 않는다")
    void upload_storeThrows_propagates() {
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(1L)).willReturn(Optional.of(activeNotice()));
        given(noticeAttachmentRepository.countByNoticeId(1L)).willReturn(0L);
        given(fileStorage.store(any(byte[].class), anyString())).willThrow(new IllegalStateException("디스크 오류"));
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", "x".getBytes());

        assertThrows(IllegalStateException.class, () -> noticeAttachmentService.upload(1L, file));
        verify(noticeAttachmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("업로드 성공 시 롤백 정리 콜백이 등록되고, 커밋되지 않으면 파일이 삭제된다")
    void upload_registersRollbackCleanupCallback() {
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(1L)).willReturn(Optional.of(activeNotice()));
        given(noticeAttachmentRepository.countByNoticeId(1L)).willReturn(0L);
        given(fileStorage.store(any(byte[].class), anyString())).willReturn("2026/07/21/uuid.pdf");
        given(noticeAttachmentRepository.save(any(NoticeAttachment.class))).willAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", "x".getBytes());
        noticeAttachmentService.upload(1L, file);

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, syncs.size());

        // 커밋되지 않은 상태(롤백)를 시뮬레이션한다 — 파일이 정리되는지 확인.
        syncs.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(fileStorage).delete("2026/07/21/uuid.pdf");
    }

    // ===================== list =====================

    @Test
    @DisplayName("목록 조회 — 존재하지 않거나 삭제된 공지면 404")
    void list_noticeNotFound() {
        given(noticeRepository.findByIdAndDeletedFalse(99L)).willReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> noticeAttachmentService.list(99L));
    }

    @Test
    @DisplayName("목록 조회 성공")
    void list_success() {
        given(noticeRepository.findByIdAndDeletedFalse(1L)).willReturn(Optional.of(activeNotice()));
        given(noticeAttachmentRepository.findByNoticeIdOrderByIdAsc(1L)).willReturn(List.of(existingAttachment()));

        List<NoticeAttachmentResponse> result = noticeAttachmentService.list(1L);

        assertEquals(1, result.size());
        assertEquals("file.pdf", result.get(0).getOriginalFilename());
    }

    // ===================== download =====================

    @Test
    @DisplayName("다른 notice의 attachmentId로 접근 시 404 (IDOR 방지)")
    void download_wrongNotice_notFound() {
        given(noticeAttachmentRepository.findByIdAndNoticeId(10L, 2L)).willReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> noticeAttachmentService.download(2L, 10L));
    }

    @Test
    @DisplayName("다운로드 성공 — storageKey로 파일을 읽어 원본 파일명과 함께 반환한다")
    void download_success() {
        given(noticeAttachmentRepository.findByIdAndNoticeId(10L, 1L)).willReturn(Optional.of(existingAttachment()));
        given(fileStorage.load("2026/07/21/uuid.pdf")).willReturn("content".getBytes());

        NoticeAttachmentDownload download = noticeAttachmentService.download(1L, 10L);

        assertEquals("file.pdf", download.originalFilename());
        assertArrayEquals("content".getBytes(), download.content());
    }

    @Test
    @DisplayName("DB 행 조회 직후 동시 삭제로 실파일이 이미 사라진 경우 500이 아니라 404")
    void download_fileAlreadyDeletedConcurrently_notFound() {
        given(noticeAttachmentRepository.findByIdAndNoticeId(10L, 1L)).willReturn(Optional.of(existingAttachment()));
        given(fileStorage.load("2026/07/21/uuid.pdf"))
                .willThrow(new com.cms.common.storage.StorageFileNotFoundException("첨부파일을 찾을 수 없습니다: 2026/07/21/uuid.pdf", null));

        assertThrows(ResourceNotFoundException.class, () -> noticeAttachmentService.download(1L, 10L));
    }

    @Test
    @DisplayName("파일 없음이 아닌 그 외 I/O 실패는 그대로 전파된다(500 유지)")
    void download_otherStorageFailure_propagates() {
        given(noticeAttachmentRepository.findByIdAndNoticeId(10L, 1L)).willReturn(Optional.of(existingAttachment()));
        given(fileStorage.load("2026/07/21/uuid.pdf")).willThrow(new IllegalStateException("디스크 읽기 오류"));

        assertThrows(IllegalStateException.class, () -> noticeAttachmentService.download(1L, 10L));
    }

    // ===================== delete =====================

    @Test
    @DisplayName("존재하지 않는 공지의 첨부 삭제 시 404")
    void delete_noticeNotFound() {
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(99L)).willReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> noticeAttachmentService.delete(99L, 10L));
    }

    @Test
    @DisplayName("다른 notice의 attachmentId로 삭제 시 404 (IDOR 방지)")
    void delete_wrongNotice_notFound() {
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(1L)).willReturn(Optional.of(activeNotice()));
        given(noticeAttachmentRepository.findByIdAndNoticeId(99L, 1L)).willReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> noticeAttachmentService.delete(1L, 99L));
    }

    @Test
    @DisplayName("삭제 성공 — notice 락을 획득하고, 커밋 전에는 파일을 지우지 않고 커밋 후에만 지운다")
    void delete_success_registersAfterCommitCleanup() {
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(1L)).willReturn(Optional.of(activeNotice()));
        NoticeAttachment attachment = existingAttachment();
        given(noticeAttachmentRepository.findByIdAndNoticeId(10L, 1L)).willReturn(Optional.of(attachment));

        NoticeAttachmentResponse response = noticeAttachmentService.delete(1L, 10L);

        assertEquals(10L, response.getId());
        verify(noticeRepository).findByIdAndDeletedFalseForUpdate(1L);
        verify(noticeAttachmentRepository).delete(attachment);
        verify(fileStorage, never()).delete(anyString());

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, syncs.size());
        syncs.get(0).afterCommit();
        verify(fileStorage).delete("2026/07/21/uuid.pdf");
    }

    @Test
    @DisplayName("커밋 후 파일 삭제가 실패해도 예외를 전파하지 않는다(로그만 남김)")
    void delete_fileDeleteFailsAfterCommit_doesNotPropagate() {
        given(noticeRepository.findByIdAndDeletedFalseForUpdate(1L)).willReturn(Optional.of(activeNotice()));
        NoticeAttachment attachment = existingAttachment();
        given(noticeAttachmentRepository.findByIdAndNoticeId(10L, 1L)).willReturn(Optional.of(attachment));
        doThrow(new IllegalStateException("디스크 오류")).when(fileStorage).delete("2026/07/21/uuid.pdf");

        noticeAttachmentService.delete(1L, 10L);

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertDoesNotThrow(() -> syncs.get(0).afterCommit());
    }
}
