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

    /** 최상위(upMenuNo IS NULL) 형제 중 최대 ord (형제 없으면 null) */
    @Query("select max(m.ord) from Menu m where m.upMenuNo is null")
    Integer findMaxOrdByUpMenuNoIsNull();

    /** 지정 부모 아래 형제 중 최대 ord (형제 없으면 null) */
    @Query("select max(m.ord) from Menu m where m.upMenuNo = :upMenuNo")
    Integer findMaxOrdByUpMenuNo(@Param("upMenuNo") Long upMenuNo);

    /**
     * 생성/재활성화 시 부모 row, 비활성화(삭제 및 PATCH useYn=false) 시 대상 row를
     * PESSIMISTIC_WRITE로 잠근 뒤 조회한다. 잠금 획득 → 검증 → 상태 반영이 한 트랜잭션에서
     * 직렬화되도록 보장한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Menu m where m.menuNo = :menuNo")
    Optional<Menu> findByIdForUpdate(@Param("menuNo") Long menuNo);
}
