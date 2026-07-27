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
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StorageFileNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final int MAX_COUNT_PER_NOTICE = 5;
    private static final int MAX_FILENAME_LENGTH = 255;
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /**
     * 확장자 → 허용 Content-Type 집합. {@link #DEFAULT_CONTENT_TYPE}은 모든 확장자에 공통으로
     * 추가 허용된다(비표준 클라이언트의 제네릭 선언 대응, 쟁점 5).
     */
    private static final Map<String, Set<String>> ALLOWED_CONTENT_TYPES_BY_EXTENSION = buildAllowedContentTypes();

    private final NoticeRepository noticeRepository;
    private final NoticeAttachmentRepository noticeAttachmentRepository;
    private final FileStorage fileStorage;

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.NOTICE_ATTACHMENT_UPLOAD, targetType = "NOTICE_ATTACHMENT", targetIdExpression = "id")
    public NoticeAttachmentResponse upload(Long noticeId, MultipartFile file) {
        noticeRepository.findByIdAndDeletedFalseForUpdate(noticeId)
                .orElseThrow(() -> new ResourceNotFoundException("공지사항을 찾을 수 없습니다."));

        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("업로드할 파일을 선택해주세요.");
        }
        String filename = sanitizeFilename(file.getOriginalFilename());
        String extension = requireAllowedExtension(filename);
        validateContentType(extension, file.getContentType());
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new InvalidRequestException("첨부파일은 10MB 이하만 업로드할 수 있습니다.");
        }
        if (noticeAttachmentRepository.countByNoticeId(noticeId) >= MAX_COUNT_PER_NOTICE) {
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
        String contentType = file.getContentType() == null ? DEFAULT_CONTENT_TYPE : file.getContentType();

        String storageKey = fileStorage.store(content, filename);
        registerCleanupOnRollback(storageKey);

        NoticeAttachment saved = noticeAttachmentRepository.save(
                NoticeAttachment.builder()
                        .noticeId(noticeId)
                        .originalFilename(filename)
                        .contentType(contentType)
                        .fileSize(file.getSize())
                        .storageKey(storageKey)
                        .createDate(LocalDateTime.now())
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
     * store() 성공 직후 등록한다 — save()의 즉시 예외든 flush·커밋 단계의 지연 예외든 트랜잭션이
     * 커밋되지 못하는 모든 경로를 이 콜백 하나로 커버한다(쟁점 7). 등록 자체가 실패하면(극히
     * 드묾) 트랜잭션 상태를 신뢰할 수 없으므로 그 자리에서 즉시 파일을 정리하고 예외를 다시 던진다.
     */
    private void registerCleanupOnRollback(String storageKey) {
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        fileStorage.delete(storageKey);
                    }
                }
            });
        } catch (RuntimeException e) {
            fileStorage.delete(storageKey);
            throw e;
        }
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

    private String sanitizeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new InvalidRequestException("업로드할 파일을 선택해주세요.");
        }
        String normalized = originalFilename.replace('\\', '/');
        int slashIndex = normalized.lastIndexOf('/');
        String base = slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
        if (base.isBlank()) {
            throw new InvalidRequestException("업로드할 파일을 선택해주세요.");
        }
        if (base.length() > MAX_FILENAME_LENGTH) {
            throw new InvalidRequestException("파일명은 255자 이하만 허용됩니다.");
        }
        return base;
    }

    private String requireAllowedExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        String extension = dotIndex >= 0 && dotIndex < filename.length() - 1
                ? filename.substring(dotIndex + 1).toLowerCase()
                : "";
        if (!ALLOWED_CONTENT_TYPES_BY_EXTENSION.containsKey(extension)) {
            throw new InvalidRequestException("허용되지 않는 파일 형식입니다: ." + extension);
        }
        return extension;
    }

    private void validateContentType(String extension, String contentType) {
        if (contentType == null) {
            // 브라우저가 아예 값을 안 보내는 레거시 클라이언트 대응 — 확장자 화이트리스트
            // 통과만으로 허용한다(쟁점 5).
            return;
        }
        Set<String> allowed = ALLOWED_CONTENT_TYPES_BY_EXTENSION.get(extension);
        if (allowed.contains(contentType) || DEFAULT_CONTENT_TYPE.equals(contentType)) {
            return;
        }
        throw new InvalidRequestException("파일 형식과 확장자가 일치하지 않습니다.");
    }

    private static Map<String, Set<String>> buildAllowedContentTypes() {
        Map<String, Set<String>> map = new HashMap<>();
        map.put("pdf", Set.of("application/pdf"));
        map.put("doc", Set.of("application/msword"));
        map.put("docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        map.put("xls", Set.of("application/vnd.ms-excel"));
        map.put("xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        map.put("ppt", Set.of("application/vnd.ms-powerpoint"));
        map.put("pptx", Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation"));
        map.put("hwp", Set.of("application/x-hwp", "application/haansofthwp"));
        map.put("txt", Set.of("text/plain"));
        map.put("csv", Set.of("text/csv", "application/vnd.ms-excel"));
        map.put("zip", Set.of("application/zip", "application/x-zip-compressed"));
        map.put("png", Set.of("image/png"));
        map.put("jpg", Set.of("image/jpeg"));
        map.put("jpeg", Set.of("image/jpeg"));
        map.put("gif", Set.of("image/gif"));
        return Map.copyOf(map);
    }
}
