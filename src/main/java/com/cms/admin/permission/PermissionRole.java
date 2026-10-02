package com.cms.admin.permission;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 권한 매트릭스를 가진 역할의 기준 행 + 낙관적 버전(PLAN-menu-permission-management.md §4). 지금은 {@code ROLE_MANAGER} 한 행만 둔다.
 * 허용 행이 하나도 없는 빈 집합에서도 잠글 대상과 버전이 있어야 하기 때문에 두며, 나중에 역할 테이블로 확장할 자리이기도 하다.
 * 권한관리 저장은 {@link PermissionRoleRepository#findByIdForUpdate}로 이 행을 잠그고 요청의 version과 수동 비교한 뒤 {@link #increaseVersion}으로 올린다(JPA {@code @Version}이 아니다).
 */
@Entity
@Table(name = "permission_role")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PermissionRole {

    @Id
    private String role;

    private Long version;

    private LocalDateTime updateDate;

    /** 허용 집합이 실제로 바뀐 저장에서만 호출한다. @param now 앱 Clock 기준 현재 시각 */
    public void increaseVersion(LocalDateTime now) {
        this.version = this.version + 1;
        this.updateDate = now;
    }
}
