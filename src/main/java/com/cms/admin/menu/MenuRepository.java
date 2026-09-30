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

    /** 부모 이동 시 하위 메뉴(활성·비활성 무관) 존재 여부 확인 — 자식이 있는 메뉴를 옮기면 자식이 3단이 된다 */
    boolean existsByUpMenuNo(Long upMenuNo);

    /** 최상위(upMenuNo IS NULL) 형제 중 최대 ord (형제 없으면 null) */
    @Query("select max(m.ord) from Menu m where m.upMenuNo is null")
    Integer findMaxOrdByUpMenuNoIsNull();

    /** 지정 부모 아래 형제 중 최대 ord (형제 없으면 null) */
    @Query("select max(m.ord) from Menu m where m.upMenuNo = :upMenuNo")
    Integer findMaxOrdByUpMenuNo(@Param("upMenuNo") Long upMenuNo);

    /**
     * 형제 순서 재조정용 스냅샷 행 — 엔티티가 아니라 값 프로젝션이다. 잠금 전에 엔티티를
     * 영속성 컨텍스트에 올리면 오래된 값이 남아(Menu에는 @DynamicUpdate가 없어 전체 컬럼
     * UPDATE) 동시 수정을 덮어쓸 수 있으므로, 잠금 전에는 이 값 행만 읽는다.
     */
    record SiblingRow(Long menuNo, Integer ord, Boolean useYn) {}

    /** 지정 부모 아래 형제 스냅샷(비잠금). menuNo 오름차순 — 잠금 순서와 같다. */
    @Query("select new com.cms.admin.menu.MenuRepository$SiblingRow(m.menuNo, m.ord, m.useYn) "
            + "from Menu m where m.upMenuNo = :upMenuNo order by m.menuNo asc")
    List<SiblingRow> findSiblingRowsByUpMenuNo(@Param("upMenuNo") Long upMenuNo);

    /** 최상위(upMenuNo IS NULL) 형제 스냅샷(비잠금). menuNo 오름차순. */
    @Query("select new com.cms.admin.menu.MenuRepository$SiblingRow(m.menuNo, m.ord, m.useYn) "
            + "from Menu m where m.upMenuNo is null order by m.menuNo asc")
    List<SiblingRow> findRootSiblingRows();

    /**
     * 생성/재활성화 시 부모 row, 비활성화(삭제 및 PATCH useYn=false) 시 대상 row를
     * PESSIMISTIC_WRITE로 잠근 뒤 조회한다. 잠금 획득 → 검증 → 상태 반영이 한 트랜잭션에서
     * 직렬화되도록 보장한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Menu m where m.menuNo = :menuNo")
    Optional<Menu> findByIdForUpdate(@Param("menuNo") Long menuNo);
}
