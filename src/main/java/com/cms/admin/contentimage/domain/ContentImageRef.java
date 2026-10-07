package com.cms.admin.contentimage.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 콘텐츠(owner)가 본문에서 이미지를 참조한다는 사실 한 건. 콘텐츠 저장 트랜잭션이 sanitize된 본문의 이미지 ID 집합으로
 * (ownerType, ownerId) 단위 행을 교체한다. 연관관계 매핑 없이 ID 값만 가진다(프로젝트 엔티티 규약).
 */
@Entity
@Table(name = "content_image_ref")
@IdClass(ContentImageRefId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentImageRef {

    @Id
    private String ownerType;

    @Id
    private Long ownerId;

    @Id
    private Long imageId;

    public ContentImageRef(String ownerType, Long ownerId, Long imageId) {
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.imageId = imageId;
    }
}
