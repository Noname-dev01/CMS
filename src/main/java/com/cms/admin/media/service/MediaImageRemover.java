package com.cms.admin.media.service;

import com.cms.admin.contentimage.domain.ContentImage;
import com.cms.admin.contentimage.domain.ContentImageRef;
import com.cms.admin.contentimage.domain.ContentImageUsage;
import com.cms.admin.contentimage.repository.ContentImageRefRepository;
import com.cms.admin.contentimage.repository.ContentImageRepository;
import com.cms.admin.contentimage.repository.ContentImageUsageRepository;
import com.cms.admin.contentimage.service.ContentImageService;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.FileStorageTransactionSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 이미지 제거 공통 작업자(PLAN-media-library.md D5·D8·R4-2). 호출자의 트랜잭션에 참여하며 <b>격리 수준은 최상위 진입점이 선언한다</b>
 * (READ_COMMITTED — 참조 인덱스의 갭 잠금 순환 방지, R3-1). 감사 애너테이션은 없다.
 *
 * <p>잠금 순서는 이미지 행 {@code FOR UPDATE} → 자기 참조 삭제 → 남은 참조 <b>잠금 읽기</b> → 카운터다. 이미지 행을 먼저 잡으므로 같은 이미지를
 * 쓰는 다른 소유자는 자기 참조를 지우기 전에 이미지 잠금에서 대기한다 — 서로의 참조 행을 기다리는 순환이 생기지 않는다(R2-1).
 */
@Component
@RequiredArgsConstructor
public class MediaImageRemover {

    private final ContentImageRepository imageRepository;
    private final ContentImageRefRepository refRepository;
    private final ContentImageUsageRepository usageRepository;
    private final FileStorage fileStorage;

    /**
     * 배너·팝업을 지울 때 호출한다(소유자 행은 호출자가 이미 잠갔다). 소유자의 참조를 지우고, 그 이미지가 배너·팝업 직접 업로드 출처이며 다른 참조가
     * 없으면 이미지 행·파일도 함께 지운다. 라이브러리·게시판 출처나 다른 소유자가 쓰는 이미지는 남긴다.
     */
    public void detachOwner(String ownerType, Long ownerId, Long imageId) {
        Optional<ContentImage> locked = imageRepository.findByIdForUpdate(imageId);
        refRepository.deleteByOwner(ownerType, ownerId);
        if (locked.isEmpty()) {
            return;
        }
        ContentImage image = locked.get();
        if (!ContentImageService.isOwnedScope(image.getScopeType())) {
            return;
        }
        if (refRepository.lockByImageId(imageId).isEmpty()) {
            deleteLocked(image);
        }
    }

    /** 라이브러리 삭제 — 이미지 행을 잠근 호출자가 남은 참조를 먼저 확인한 뒤 부른다. 카운터를 되돌리고 행을 지우고 커밋 후 파일을 지운다. */
    public void deleteLocked(ContentImage image) {
        ContentImageUsage usage = usageRepository.findByIdForUpdate(ContentImageUsage.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("content_image_usage 카운터 행이 없습니다(V2 시드 누락)."));
        usage.release(image.getFileSize());
        imageRepository.delete(image);
        if (image.isRootNamespace()) {
            FileStorageTransactionSupport.deleteAfterCommit(fileStorage, image.getStorageKey(), "image id=" + image.getId());
        } else {
            FileStorageTransactionSupport.deleteAfterCommit(fileStorage, image.getStorageKey(), image.getNamespace(),
                    "image id=" + image.getId());
        }
    }

    /** 남은 참조를 잠금 읽기로 확인한다(라이브러리 삭제의 409 판정). */
    public List<ContentImageRef> lockRefs(Long imageId) {
        return refRepository.lockByImageId(imageId);
    }
}
