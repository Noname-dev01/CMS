package com.cms.admin.contentimage.service;

import com.cms.admin.contentimage.config.ContentImageProperties;
import com.cms.admin.contentimage.domain.ContentImage;
import com.cms.admin.contentimage.domain.ContentImageThumb;
import com.cms.admin.contentimage.domain.ContentImageUsage;
import com.cms.admin.contentimage.repository.ContentImageRepository;
import com.cms.admin.contentimage.repository.ContentImageThumbRepository;
import com.cms.admin.contentimage.repository.ContentImageUsageRepository;
import com.cms.admin.media.service.MediaImageRegistrar;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ServiceBusyException;
import com.cms.common.image.ImageThumbnailer;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.FileStorageTransactionSupport;
import com.cms.common.storage.StorageFileNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 대표 이미지 썸네일을 보장한다(PLAN-board-definition.md PR 5 D4·D6). 호출자(게시글 저장)의 트랜잭션에 참여한다 — 디코딩 실패 400·용량 409·
 * 자리 없음 503이 본문 저장까지 원자적으로 되돌리고, 파일은 {@code deleteOnRollback}이 정리한다.
 *
 * <p>순서: 이미 있으면 끝 → 생성 자리 획득(동시 {@value #MAX_CONCURRENT}개, {@value #WAIT_SECONDS}초 대기) → 원본 읽기·생성 → 파일 저장(+롤백 정리)
 * → {@code INSERT IGNORE}(0행이면 동시에 앞선 트랜잭션이 만들었으므로 자기 파일을 지운다) → 카운터 바이트(마지막 잠금). 작은 원본은 검증만 하고
 * 행·파일을 만들지 않는다(공개 {@code ?size=thumb}는 원본을 준다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentImageThumbnailService {

    /** 동시 생성 수. 축소 디코딩이라도 이미지 한 장당 수 MiB~수십 MiB를 쓰므로 작게 묶는다(자리가 없으면 503). */
    static final int MAX_CONCURRENT = 2;
    static final long WAIT_SECONDS = 2;

    private final ContentImageRepository imageRepository;
    private final ContentImageThumbRepository thumbRepository;
    private final ContentImageUsageRepository usageRepository;
    private final ContentImageProperties properties;
    private final FileStorage fileStorage;
    private final Clock clock;

    private final Semaphore slots = new Semaphore(MAX_CONCURRENT);

    /** 시험용 — 생성 자리를 미리 점유해 503 경로를 재현한다. 운영 코드는 쓰지 않는다. */
    public Semaphore generationSlots() {
        return slots;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void ensureThumbnail(Long imageId) {
        if (thumbRepository.existsById(imageId)) {
            return;
        }
        ContentImage image = imageRepository.findById(imageId)
                .orElseThrow(() -> new InvalidRequestException(ImageThumbnailer.FAILED_MESSAGE));

        Optional<ImageThumbnailer.Thumbnail> thumbnail = generate(image);
        if (thumbnail.isEmpty()) {
            return;
        }
        ImageThumbnailer.Thumbnail thumb = thumbnail.get();

        String filename = "thumb." + MediaImageRegistrar.extensionFor(thumb.contentType());
        String storageKey = fileStorage.store(thumb.content(), filename, ContentImageThumb.NAMESPACE);
        FileStorageTransactionSupport.deleteOnRollback(fileStorage, storageKey, ContentImageThumb.NAMESPACE, "image thumb id=" + imageId);

        int inserted = thumbRepository.insertIgnore(imageId, storageKey, thumb.contentType(), thumb.content().length, LocalDateTime.now(clock));
        if (inserted == 0) {
            // 같은 이미지를 동시에 대표로 지정한 다른 트랜잭션이 먼저 만들었다 — 내 파일은 어디에도 연결되지 않으므로 지금 지운다
            deleteOwnFile(storageKey);
            return;
        }
        ContentImageUsage usage = usageRepository.findByIdForUpdate(ContentImageUsage.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("content_image_usage 카운터 행이 없습니다(V2 시드 누락)."));
        usage.reserveBytes(thumb.content().length, properties.getMaxTotalBytes());
    }

    /** 자리 획득 → 원본 읽기 → 생성. 자리는 파일을 읽기 전에 잡는다(대기 중 5MB를 쥐고 있지 않게). */
    private Optional<ImageThumbnailer.Thumbnail> generate(ContentImage image) {
        if (image.getFileSize() == null || image.getFileSize() > ImageThumbnailer.MAX_INPUT_BYTES) {
            throw new InvalidRequestException(ImageThumbnailer.FAILED_MESSAGE);
        }
        boolean acquired;
        try {
            acquired = slots.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new ServiceBusyException("다른 대표 이미지를 만드는 중입니다. 잠시 후 다시 저장하세요.", WAIT_SECONDS);
        }
        try {
            byte[] original;
            try {
                original = image.isRootNamespace()
                        ? fileStorage.load(image.getStorageKey())
                        : fileStorage.load(image.getStorageKey(), image.getNamespace());
            } catch (StorageFileNotFoundException e) {
                // 파일이 없는 이미지는 공개 화면에서도 404다 — 대표로 쓸 수 없다
                throw new InvalidRequestException(ImageThumbnailer.FAILED_MESSAGE);
            }
            return ImageThumbnailer.create(original, image.getContentType());
        } finally {
            slots.release();
        }
    }

    private void deleteOwnFile(String storageKey) {
        try {
            fileStorage.delete(storageKey, ContentImageThumb.NAMESPACE);
        } catch (RuntimeException e) {
            // 지우지 못해도 "행 없는 파일"일 뿐이다(수동 회수 ⑤-5가 정리) — 저장 자체를 실패시키지 않는다
            log.warn("동시 생성으로 버려진 썸네일 파일 삭제 실패 — 수동 회수 대상. storageKey={}", storageKey, e);
        }
    }
}
