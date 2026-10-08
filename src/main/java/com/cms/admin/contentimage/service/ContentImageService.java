package com.cms.admin.contentimage.service;

import com.cms.admin.contentimage.config.ContentImageProperties;
import com.cms.admin.contentimage.domain.ContentImage;
import com.cms.admin.contentimage.domain.ContentImageRef;
import com.cms.admin.contentimage.domain.ContentImageUsage;
import com.cms.admin.contentimage.dto.ContentImageUploadResponse;
import com.cms.admin.contentimage.repository.ContentImageRefRepository;
import com.cms.admin.contentimage.repository.ContentImageRepository;
import com.cms.admin.contentimage.repository.ContentImageUsageRepository;
import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.AdminPermissionEvaluator;
import com.cms.admin.permission.PermissionAction;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.image.ImageFileValidator;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.FileStorageTransactionSupport;
import com.cms.config.auth.AdminSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 편집기 본문 이미지 업로드·참조 교체(PLAN-html-editor.md 쟁점 6·8·9·10).
 *
 * <p>업로드는 카운터 행({@link ContentImageUsage})을 비관적 락으로 잡고 바이트·개수 상한을 검사·증가시킨 뒤 파일을 저장한다.
 * 파일은 {@code FileStorage} 루트(네임스페이스 없음)에 두고, 커밋되지 못하면 정리한다. 카운터 증가도 같은 트랜잭션이라 함께 롤백된다.
 */
@Service
@RequiredArgsConstructor
public class ContentImageService {

    /** 공지 본문의 참조 owner_type. 공개 판정 쿼리({@code existsPublishedNoticeRef})의 리터럴과 같아야 한다. */
    public static final String OWNER_NOTICE = "NOTICE";

    /** 공지 편집기 업로드 출처(V27 기본값과 같다). 공개 판정 쿼리의 리터럴과 같아야 한다. */
    public static final String SCOPE_NOTICE = "NOTICE";

    static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

    /** 본문 이미지 상한: 한 변 4096px·총 4096² 픽셀, 헤더 검사만(전체 디코드는 힙 ~64MB라 하지 않음 — 쟁점 8). */
    static final ImageFileValidator.Limits LIMITS = new ImageFileValidator.Limits(4096, 4096L * 4096, false);

    private final ContentImageRepository imageRepository;
    private final ContentImageRefRepository refRepository;
    private final ContentImageUsageRepository usageRepository;
    private final ContentImageProperties properties;
    private final FileStorage fileStorage;
    private final AdminSecurityService adminSecurityService;
    private final AdminPermissionEvaluator permissionEvaluator;
    private final Clock clock;

    /**
     * 공지 편집기의 이미지 업로드. URL 게이트·핸들러 선언은 {@code NOTICE} READ이고, 여기서 CREATE 또는 UPDATE를
     * 다시 판정한다 — 작성 중(공지 ID 없음)과 수정 중 모두 이미지를 넣어야 하는데 {@code @RequirePermission}은 동작 하나만
     * 받기 때문이다(쟁점 10).
     */
    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.CONTENT_IMAGE_UPLOAD, targetType = "CONTENT_IMAGE", targetIdExpression = "id")
    public ContentImageUploadResponse uploadForNotice(MultipartFile file) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!permissionEvaluator.allows(authentication, AdminFeature.NOTICE, PermissionAction.CREATE)
                && !permissionEvaluator.allows(authentication, AdminFeature.NOTICE, PermissionAction.UPDATE)) {
            throw new AccessDeniedException("공지 작성 또는 수정 권한이 필요합니다.");
        }
        String uploaderId = adminSecurityService.getCurrentAdminUserId();
        if (uploaderId == null) {
            throw new AccessDeniedException("인증 정보를 확인할 수 없습니다.");
        }

        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("업로드할 이미지를 선택해주세요.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new InvalidRequestException("본문 이미지는 5MB 이하만 업로드할 수 있습니다.");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new InvalidRequestException("파일을 읽을 수 없습니다.");
        }
        String contentType = file.getContentType();
        ImageFileValidator.validate(content, contentType, LIMITS);

        ContentImageUsage usage = usageRepository.findByIdForUpdate(ContentImageUsage.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("content_image_usage 카운터 행이 없습니다(V25 시드 누락)."));
        usage.reserve(content.length, properties.getMaxTotalBytes(), properties.getMaxCount());

        String storageKey = fileStorage.store(content, "image." + extensionFor(contentType));
        FileStorageTransactionSupport.deleteOnRollback(fileStorage, storageKey);

        ContentImage saved = imageRepository.save(ContentImage.builder()
                .storageKey(storageKey)
                .contentType(contentType)
                .fileSize((long) content.length)
                .uploaderId(uploaderId)
                .scopeType(SCOPE_NOTICE)
                .scopeId(null)
                .createDate(LocalDateTime.now(clock))
                .build());
        return ContentImageUploadResponse.from(saved);
    }

    /**
     * (ownerType, ownerId)의 참조를 본문의 이미지 ID 집합으로 교체한다. 존재하지 않는 이미지 ID는 참조에서 뺀다(깨진 이미지로
     * 남지만 무해). 호출자(콘텐츠 저장)의 트랜잭션·행 잠금 안에서 실행되어야 한다.
     */
    @Transactional
    public void replaceRefs(String ownerType, Long ownerId, String scopeType, Long scopeId, Collection<Long> imageIds) {
        Set<Long> existing = new HashSet<>();
        if (!imageIds.isEmpty()) {
            // 참조 자격(PLAN-board.md 쟁점 9): 콘텐츠 출처와 다른 출처의 이미지는 참조할 수 없다 — 다른 게시판·공지의 비공개 이미지를
            // 자기 공개 글에 넣어 익명 공개하는 경로를 막는다. 삭제·쓰기보다 먼저 검사해 거부 시 기존 참조가 그대로 남는다.
            for (ContentImage image : imageRepository.findAllById(imageIds)) {
                if (!scopeType.equals(image.getScopeType()) || !Objects.equals(scopeId, image.getScopeId())) {
                    throw new InvalidRequestException("다른 게시판·공지에서 올린 이미지는 사용할 수 없습니다. 이미지를 다시 올려 주세요.");
                }
                existing.add(image.getId());
            }
        }
        refRepository.deleteByOwner(ownerType, ownerId);
        List<ContentImageRef> refs = imageIds.stream()
                .filter(existing::contains)
                .map(imageId -> new ContentImageRef(ownerType, ownerId, imageId))
                .toList();
        refRepository.saveAll(refs);
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            default -> throw new InvalidRequestException("이미지 파일만 업로드할 수 있습니다.");
        };
    }
}
