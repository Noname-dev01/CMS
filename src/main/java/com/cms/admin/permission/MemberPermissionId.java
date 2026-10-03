package com.cms.admin.permission;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** {@link MemberPermission} 복합 키(memberId, feature, action). */
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class MemberPermissionId implements Serializable {

    private Long memberId;
    private String feature;
    private String action;
}
