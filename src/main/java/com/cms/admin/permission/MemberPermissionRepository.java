package com.cms.admin.permission;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MemberPermissionRepository extends JpaRepository<MemberPermission, MemberPermissionId> {

    /** 허용 행 전체를 (memberId, feature, action) 값으로만 읽는다 — 영속성 컨텍스트에 엔티티를 올리지 않는다. */
    @Query("select p.memberId, p.feature, p.action from MemberPermission p")
    List<Object[]> findAllGrantRows();

    /** 한 회원의 허용 행(권한관리 조회·저장용). feature·action은 DB 콜레이션(general_ci)으로 비교되므로 호출자가 정확 일치를 다시 확인한다. */
    List<MemberPermission> findByMemberId(Long memberId);

    /** 역할이 바뀐 회원의 개별 허용 행을 변형 행까지 전부 지운다(남길 행이 없다). 삭제 건수를 돌려준다. */
    @Modifying
    @Query("delete from MemberPermission p where p.memberId = :memberId")
    int deleteByMemberId(@Param("memberId") Long memberId);
}
