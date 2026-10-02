package com.cms.admin.permission;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermissionId> {

    /** 허용 행 전체를 (role, feature, action) 값으로만 읽는다 — 영속성 컨텍스트에 엔티티를 올리지 않는다. */
    @Query("select r.role, r.feature, r.action from RolePermission r")
    List<Object[]> findAllGrantRows();

    /** 한 역할의 허용 행(권한관리 조회·저장용). {@code role = :role}도 DB 콜레이션(general_ci)으로 비교되므로 호출자가 역할 문자열 정확 일치를 다시 확인한다. */
    List<RolePermission> findByRole(String role);
}
