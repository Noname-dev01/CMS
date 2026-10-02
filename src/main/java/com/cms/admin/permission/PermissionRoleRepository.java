package com.cms.admin.permission;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PermissionRoleRepository extends JpaRepository<PermissionRole, String> {

    /**
     * 권한 매트릭스 저장의 첫 DB 조회로 쓰는 잠금 읽기. 허용 행이 하나도 없는 빈 집합에서도 잠글 대상(역할 기준 행)이 있어
     * 동시 저장이 직렬화된다. 잠금 뒤에 읽는 허용 행은 앞선 저장의 커밋을 본다(REPEATABLE READ 스냅샷이 잠금 이후에 고정).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PermissionRole p where p.role = :role")
    Optional<PermissionRole> findByIdForUpdate(@Param("role") String role);
}
