package com.cms.admin.menu;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MenuRepository extends JpaRepository<Menu, Long> {

    /** 트리 조회 - 비활성 포함 전체 */
    List<Menu> findAllByOrderByOrdAscMenuNoAsc();

    /** 트리 조회 - 활성 메뉴만 */
    List<Menu> findAllByUseYnTrueOrderByOrdAscMenuNoAsc();

    /** 비활성화 시 활성 하위 메뉴 존재 여부 확인 */
    boolean existsByUpMenuNoAndUseYnTrue(Long upMenuNo);

    /** 영구삭제 시 하위 메뉴 존재 여부 확인 — 활성·비활성 무관(비활성 자식도 재활성화되면 부모가 필요하다) */
    boolean existsByUpMenuNo(Long upMenuNo);

    /** 최상위(upMenuNo IS NULL) 형제 중 최대 ord (형제 없으면 null) */
    @Query("select max(m.ord) from Menu m where m.upMenuNo is null")
    Integer findMaxOrdByUpMenuNoIsNull();

    /** 지정 부모 아래 형제 중 최대 ord (형제 없으면 null) */
    @Query("select max(m.ord) from Menu m where m.upMenuNo = :upMenuNo")
    Integer findMaxOrdByUpMenuNo(@Param("upMenuNo") Long upMenuNo);

    /**
     * 전체 메뉴 행을 menuNo 오름차순으로 PESSIMISTIC_WRITE 잠금하며 읽는다 — 구조 반영·생성이
     * 첫 조회로 쓴다(PLAN-menu-structure-apply.md 결정 2·9). 같은 순서로 전체를 잠그므로 이 경로들끼리는 직렬화되고
     * 교착이 없다. 메뉴는 관리자 전용 소규모 데이터라 전체 잠금 비용을 수용한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Menu m order by m.menuNo asc")
    List<Menu> findAllForUpdate();

    /**
     * 생성/재활성화 시 부모 row, 비활성화(PATCH useYn=false)·영구삭제(DELETE) 시 대상 row를
     * PESSIMISTIC_WRITE로 잠근 뒤 조회한다. 잠금 획득 → 검증 → 상태 반영이 한 트랜잭션에서
     * 직렬화되도록 보장한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Menu m where m.menuNo = :menuNo")
    Optional<Menu> findByIdForUpdate(@Param("menuNo") Long menuNo);
}
