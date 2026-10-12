package com.cms.admin.media.service;

import com.cms.admin.contentimage.domain.ContentImage;
import com.cms.admin.contentimage.repository.ContentImageRepository;
import com.cms.admin.contentimage.service.ContentImageService;
import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.AdminPermissionEvaluator;
import com.cms.admin.permission.PermissionAction;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.common.image.ImageFileValidator;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StorageFileNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 배너·팝업이 미디어 라이브러리에서 이미지를 <b>고를 때</b>의 공통 검증(PLAN-media-library.md D3·D4·D8).
 * 호출자의 트랜잭션에 참여하며 이미지 행을 {@code FOR SHARE}로 잡아 선택이 끝날 때까지 삭제를 막는다(카운터는 잡지 않는다 — 새 행이 없다).
 *
 * <p>규칙: 호출자의 현재 {@code MEDIA} 읽기 권한(ADMIN 항상) → 라이브러리 계열 출처만(게시판 출처는 존재하지 않는 것과 같은 404) → 용도 한도
 * 재검증(배너·팝업의 2MB·2560px 예산은 본문 이미지 한도보다 작다). 이미지 ID는 연속 정수라 짐작으로 넣을 수 있어 권한 판정이 먼저다.
 */
@Component
@RequiredArgsConstructor
public class MediaImageSelector {

    static final String NOT_FOUND = "이미지를 찾을 수 없습니다.";

    private final ContentImageRepository imageRepository;
    private final AdminPermissionEvaluator permissionEvaluator;
    private final FileStorage fileStorage;

    public ContentImage select(Long imageId, long maxBytes, ImageFileValidator.Limits limits, String purpose) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!permissionEvaluator.allows(authentication, AdminFeature.MEDIA, PermissionAction.READ)) {
            throw new AccessDeniedException("미디어 라이브러리 이미지를 사용할 권한이 없습니다.");
        }
        ContentImage image = imageRepository.findByIdForShare(imageId)
                .filter(found -> ContentImageService.isLibraryScope(found.getScopeType()))
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (image.getFileSize() > maxBytes) {
            throw new InvalidRequestException(purpose + "에 쓰기에는 이미지가 큽니다(" + (maxBytes / (1024 * 1024)) + "MB 이하만 사용할 수 있습니다).");
        }
        byte[] content;
        try {
            content = image.isRootNamespace()
                    ? fileStorage.load(image.getStorageKey())
                    : fileStorage.load(image.getStorageKey(), image.getNamespace());
        } catch (StorageFileNotFoundException e) {
            throw new ResourceNotFoundException("이미지 파일을 찾을 수 없습니다.");
        }
        ImageFileValidator.validate(content, image.getContentType(), limits);
        return image;
    }
}
