package com.cms.admin.contentimage.domain;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** {@link ContentImageRef} 복합 키(ownerType, ownerId, imageId). */
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ContentImageRefId implements Serializable {

    private String ownerType;
    private Long ownerId;
    private Long imageId;
}
