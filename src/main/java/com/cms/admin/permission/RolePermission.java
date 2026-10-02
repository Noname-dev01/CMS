package com.cms.admin.permission;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 역할이 허용받은 (기능, 동작) 한 건. <b>행이 있으면 허용</b>이고 거부 행은 없다. ADMIN 행은 두지 않는다(코드 고정).
 * 컬럼은 DB enum이 아닌 VARCHAR다 — 카탈로그에 기능을 추가할 때 ALTER가 필요 없게 하고, 코드에서 지운 기능의 행이 남아도
 * 스냅샷 빌더가 무시하도록(관대한 파싱) 하기 위해서다.
 */
@Entity
@Table(name = "role_permission")
@IdClass(RolePermissionId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RolePermission {

    @Id
    private String role;

    @Id
    private String feature;

    @Id
    private String action;

    public RolePermission(String role, String feature, String action) {
        this.role = role;
        this.feature = feature;
        this.action = action;
    }
}
