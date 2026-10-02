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
 * 이 PR에서는 조회·잠금 경로가 없고(권한관리 API는 후속 PR) 스키마 검증({@code ddl-auto: validate})용 매핑이다.
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
}
