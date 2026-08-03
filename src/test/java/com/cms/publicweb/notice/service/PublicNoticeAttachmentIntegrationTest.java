package com.cms.publicweb.notice.service;

import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.domain.NoticeAttachment;
import com.cms.admin.notice.dto.response.NoticeAttachmentResponse;
import com.cms.admin.notice.repository.NoticeAttachmentRepository;
import com.cms.admin.notice.repository.NoticeRepository;
import com.cms.admin.notice.service.NoticeAttachmentService;
import com.cms.common.storage.FileStorage;
import com.cms.publicweb.notice.dto.PublicNoticeAttachmentDownload;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 공개 첨부 다운로드의 TOCTOU 재검증·IDOR 차단·StorageFileNotFoundException 경로를 실제
 * MariaDB로 검증한다(PLAN-public-notice-attachment.md 결정 2). 각 시나리오는 독립된 notice를
 * 사용한다 — 같은 fixture를 재사용하면 한 시나리오의 상태 변경(특히 useYn=false 전환)이
 * 다른 시나리오가 검증하려는 분기를 가려버릴 수 있다(codex 리뷰 3차 지적1).
 *
 * <p>Testcontainers가 띄우는 일회용 MariaDB로 실행된다 — Docker만 있으면 된다({@link MariaDbContainerSupport}).
 */
@SpringBootTest(classes = CmsTestApplication.class)
class PublicNoticeAttachmentIntegrationTest extends MariaDbContainerSupport {

    @Autowired
    NoticeRepository noticeRepository;

    @Autowired
    NoticeAttachmentRepository noticeAttachmentRepository;

    @Autowired
    NoticeAttachmentService noticeAttachmentService;

    @Autowired
    PublicNoticeService publicNoticeService;

    @Autowired
    FileStorage fileStorage;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final List<Long> createdNoticeIds = new ArrayList<>();
    private final List<String> createdStorageKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdNoticeIds) {
            try {
                noticeAttachmentRepository.findByNoticeIdOrderByIdAsc(id)
                        .forEach(a -> noticeAttachmentRepository.deleteById(a.getId()));
                noticeRepository.deleteById(id);
            } catch (Exception ignored) {
            }
        }
        for (String key : createdStorageKeys) {
            try {
                fileStorage.delete(key);
            } catch (Exception ignored) {
            }
        }
    }

    private Notice savePublishedNotice(String titlePrefix) {
        LocalDateTime now = LocalDateTime.now();
        Notice saved = noticeRepository.save(Notice.builder()
                .title(titlePrefix + "-" + System.nanoTime())
                .content("공개 첨부 통합 테스트 본문")
                .useYn(true)
                .deleted(false)
                .authorId("admin01")
                .createDate(now)
                .updateDate(now)
                .build());
        createdNoticeIds.add(saved.getId());
        return saved;
    }

    private NoticeAttachment uploadAttachment(Long noticeId) {
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "content".getBytes());
        NoticeAttachmentResponse response = noticeAttachmentService.upload(noticeId, file);
        NoticeAttachment saved = noticeAttachmentRepository.findById(response.getId()).orElseThrow();
        createdStorageKeys.add(saved.getStorageKey());
        return saved;
    }

    // ===================== 1. 다운로드 성공 =====================

    @Test
    @DisplayName("공개·미삭제 notice의 첨부는 다운로드에 성공한다")
    void downloadPublishedAttachment_success() {
        Notice notice = savePublishedNotice("다운로드성공");
        NoticeAttachment attachment = uploadAttachment(notice.getId());

        Optional<PublicNoticeAttachmentDownload> result =
                publicNoticeService.downloadPublishedAttachment(notice.getId(), attachment.getId());

        assertTrue(result.isPresent());
        assertArrayEquals("content".getBytes(), result.get().content());
    }

    // ===================== 2. TOCTOU =====================

    @Test
    @DisplayName("TOCTOU: useYn=false가 별도 트랜잭션에서 커밋된 뒤 재요청하면 empty를 반환한다")
    void downloadPublishedAttachment_toctou_hiddenAfterCommit_empty() {
        Notice notice = savePublishedNotice("TOCTOU검증");
        NoticeAttachment attachment = uploadAttachment(notice.getId());

        // 목록에서 본 뒤 관리자가 별도 트랜잭션에서 비공개로 전환·커밋하는 상황을 재현한다.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            Notice managed = noticeRepository.findById(notice.getId()).orElseThrow();
            managed.update(null, null, false);
            noticeRepository.save(managed);
        });

        Optional<PublicNoticeAttachmentDownload> result =
                publicNoticeService.downloadPublishedAttachment(notice.getId(), attachment.getId());

        assertTrue(result.isEmpty());
    }

    // ===================== 3. IDOR =====================

    @Test
    @DisplayName("IDOR: notice A의 id와 notice B 소유 attachmentId 조합은 empty를 반환한다")
    void downloadPublishedAttachment_idor_wrongNotice_empty() {
        Notice noticeA = savePublishedNotice("IDOR-A");
        Notice noticeB = savePublishedNotice("IDOR-B");
        NoticeAttachment attachmentOfB = uploadAttachment(noticeB.getId());

        Optional<PublicNoticeAttachmentDownload> result =
                publicNoticeService.downloadPublishedAttachment(noticeA.getId(), attachmentOfB.getId());

        assertTrue(result.isEmpty());
    }

    // ===================== 4. StorageFileNotFoundException =====================

    @Test
    @DisplayName("DB 행은 있는데 실파일이 없으면 empty를 반환한다(fail-closed)")
    void downloadPublishedAttachment_fileMissing_empty() {
        Notice notice = savePublishedNotice("파일없음검증");
        NoticeAttachment attachment = uploadAttachment(notice.getId());

        // 행은 유지한 채 실파일만 직접 제거한다.
        fileStorage.delete(attachment.getStorageKey());
        createdStorageKeys.remove(attachment.getStorageKey());

        Optional<PublicNoticeAttachmentDownload> result =
                publicNoticeService.downloadPublishedAttachment(notice.getId(), attachment.getId());

        assertTrue(result.isEmpty());
    }
}
