package com.cms.admin.notice.service;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.notice.domain.NoticeAttachment;
import com.cms.admin.notice.dto.response.NoticeAttachmentDownload;
import com.cms.admin.notice.dto.response.NoticeAttachmentResponse;
import com.cms.admin.notice.repository.NoticeAttachmentRepository;
import com.cms.admin.notice.repository.NoticeRepository;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.common.attachment.AttachmentFilePolicy;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.FileStorageTransactionSupport;
import com.cms.common.storage.StorageFileNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 공지사항 첨부파일 업로드/목록/다운로드/삭제.
 * 설계 결정 전문은 adversarial-review/plan/PLAN-notice-attachment.md(v6 — 적대적 리뷰 5라운드
 * ship) 참조.
 *
 * <p>업로드·삭제는 모두 notice 비관적 락({@link NoticeRepository#findByIdAndDeletedFalseForUpdate})을
 * 먼저 획득한다 — 동일 notice의 첨부 개수 상한(5개) 검사와 동시 삭제 경합을 이 락 하나로
 * 직렬화한다(쟁점 4). 목록·다운로드는 읽기 전용이라 락이 불필요하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoticeAttachmentService {

    private final NoticeRepository noticeRepository;
    private final NoticeAttachmentRepository noticeAttachmentRepository;
    private final FileStorage fileStorage;
    private final Clock clock;

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.NOTICE_ATTACHMENT_UPLOAD, targetType = "NOTICE_ATTACHMENT", targetIdExpression = "id")
    public NoticeAttachmentResponse upload(Long noticeId, MultipartFile file) {
        noticeRepository.findByIdAndDeletedFalseForUpdate(noticeId)
                .orElseThrow(() -> new ResourceNotFoundException("공지사항을 찾을 수 없습니다."));

        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("업로드할 파일을 선택해주세요.");
        }
        String filename = AttachmentFilePolicy.sanitizeFilename(file.getOriginalFilename());
        String extension = AttachmentFilePolicy.requireAllowedExtension(filename);
        AttachmentFilePolicy.validateContentType(extension, file.getContentType());
        if (file.getSize() > AttachmentFilePolicy.MAX_FILE_SIZE) {
            throw new InvalidRequestException("첨부파일은 10MB 이하만 업로드할 수 있습니다.");
        }
        if (noticeAttachmentRepository.countByNoticeId(noticeId) >= AttachmentFilePolicy.MAX_COUNT_PER_OWNER) {
            throw new ConflictException("공지사항당 첨부파일은 최대 5개까지 업로드할 수 있습니다.");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new InvalidRequestException("파일을 읽을 수 없습니다.");
        }

        // NOT NULL 컬럼이므로 선언 Content-Type이 null이면 저장 직전 정규화한다(검증은 원본 null
        // 기준으로 이미 통과시켰다 — 쟁점 5 [v4 정정]).
        String contentType = AttachmentFilePolicy.normalizeContentType(file.getContentType());

        String storageKey = fileStorage.store(content, filename);
        FileStorageTransactionSupport.deleteOnRollback(fileStorage, storageKey);

        NoticeAttachment saved = noticeAttachmentRepository.save(
                NoticeAttachment.builder()
                        .noticeId(noticeId)
                        .originalFilename(filename)
                        .contentType(contentType)
                        .fileSize(file.getSize())
                        .storageKey(storageKey)
                        .createDate(LocalDateTime.now(clock))
                        .build()
        );

        return NoticeAttachmentResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<NoticeAttachmentResponse> list(Long noticeId) {
        noticeRepository.findByIdAndDeletedFalse(noticeId)
                .orElseThrow(() -> new ResourceNotFoundException("공지사항을 찾을 수 없습니다."));

        return noticeAttachmentRepository.findByNoticeIdOrderByIdAsc(noticeId).stream()
                .map(NoticeAttachmentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public NoticeAttachmentDownload download(Long noticeId, Long attachmentId) {
        NoticeAttachment attachment = noticeAttachmentRepository.findByIdAndNoticeId(attachmentId, noticeId)
                .orElseThrow(() -> new ResourceNotFoundException("첨부파일을 찾을 수 없습니다."));

        // DB 행 조회 직후 다른 요청의 삭제 트랜잭션이 커밋되어 실파일이 이미 제거된 경우
        // StorageFileNotFoundException(파일 없음)만 404로 변환한다 — 그 외 I/O 실패는 그대로
        // IllegalStateException으로 전파해 500 처리된다(디스크 장애 등 실제 서버 오류와 구분).
        try {
            byte[] content = fileStorage.load(attachment.getStorageKey());
            return new NoticeAttachmentDownload(attachment.getOriginalFilename(), content);
        } catch (StorageFileNotFoundException e) {
            throw new ResourceNotFoundException("첨부파일을 찾을 수 없습니다.");
        }
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.NOTICE_ATTACHMENT_DELETE, targetType = "NOTICE_ATTACHMENT", targetIdExpression = "id")
    public NoticeAttachmentResponse delete(Long noticeId, Long attachmentId) {
        noticeRepository.findByIdAndDeletedFalseForUpdate(noticeId)
                .orElseThrow(() -> new ResourceNotFoundException("공지사항을 찾을 수 없습니다."));

        NoticeAttachment attachment = noticeAttachmentRepository.findByIdAndNoticeId(attachmentId, noticeId)
                .orElseThrow(() -> new ResourceNotFoundException("첨부파일을 찾을 수 없습니다."));

        noticeAttachmentRepository.delete(attachment);
        registerFileDeleteAfterCommit(attachment.getStorageKey(), attachmentId);

        return NoticeAttachmentResponse.from(attachment);
    }

    /**
     * 커밋 후(afterCommit)에만 실제 파일을 삭제한다 — AdminSessionRevokeListener와 동일한 AFTER_COMMIT
     * 원칙(쟁점 7). 삭제 실패는 try/catch + 구조화 로그로 기록하고 예외를 전파하지 않는다 — DB는
     * 이미 커밋되어 되돌릴 수 없으므로, 여기서 예외를 던져도 실질적 도움이 안 된다.
     */
    private void registerFileDeleteAfterCommit(String storageKey, Long attachmentId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    fileStorage.delete(storageKey);
                } catch (RuntimeException e) {
                    log.error("첨부파일 삭제 실패 — 수동 정리 필요. attachmentId={}, storageKey={}",
                            attachmentId, storageKey, e);
                }
            }
        });
    }
}
