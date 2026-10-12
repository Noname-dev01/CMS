package com.cms.admin.media.service;

import com.cms.admin.contentimage.domain.ContentImage;
import com.cms.admin.contentimage.domain.ContentImageRef;
import com.cms.admin.contentimage.repository.ContentImageRefRepository;
import com.cms.admin.contentimage.repository.ContentImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 배너·팝업 소유자 ID 집합의 이미지를 <b>한 번의 {@code IN} 쿼리 두 개</b>로 읽는 일괄 조회(PLAN-media-library.md D4·R3-2). 소유자 쿼리에 이미지를
 * 조인하지 않으므로 행 증식과 {@code LIMIT} 왜곡이 없고 N+1도 없다. 불변식을 어긴 데이터(참조 2개 이상)는 {@code image_id} 최솟값 하나로
 * 결정적으로 고른다. 호출자의 트랜잭션 안에서 쓴다.
 */
@Component
@RequiredArgsConstructor
public class MediaImageLookup {

    private final ContentImageRefRepository refRepository;
    private final ContentImageRepository imageRepository;

    public Map<Long, MediaImageInfo> forOwners(String ownerType, Collection<Long> ownerIds) {
        if (ownerIds.isEmpty()) {
            return Map.of();
        }
        List<ContentImageRef> refs = refRepository.findByOwnerTypeAndOwnerIdIn(ownerType, ownerIds);
        Map<Long, Long> imageIdByOwner = new HashMap<>();
        for (ContentImageRef ref : refs) {
            imageIdByOwner.merge(ref.getOwnerId(), ref.getImageId(), Math::min);
        }
        if (imageIdByOwner.isEmpty()) {
            return Map.of();
        }
        Map<Long, ContentImage> images = imageRepository.findAllById(imageIdByOwner.values()).stream()
                .collect(Collectors.toMap(ContentImage::getId, image -> image));
        Map<Long, MediaImageInfo> result = new HashMap<>();
        imageIdByOwner.forEach((ownerId, imageId) -> {
            ContentImage image = images.get(imageId);
            if (image != null) {
                result.put(ownerId, MediaImageInfo.from(image));
            }
        });
        return result;
    }

    public Optional<MediaImageInfo> forOwner(String ownerType, Long ownerId) {
        return Optional.ofNullable(forOwners(ownerType, List.of(ownerId)).get(ownerId));
    }
}
