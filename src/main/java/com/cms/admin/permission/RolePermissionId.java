package com.cms.admin.permission;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** {@link RolePermission} 복합 키(role, feature, action). */
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class RolePermissionId implements Serializable {

    private String role;
    private String feature;
    private String action;
}
